package dev.geode.ui.opaline.physics

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/** fluid.js `remove(index)` result. */
internal class RemovedParticle(
    val mass: Double,
    val position: DoubleArray,
    val velocity: DoubleArray,
    val pigment: DoubleArray,
)

/** fluid.js `massLedger`. */
internal class MassLedger(
    val initial: Double,
) {
    var injected = 0.0
    var removed = 0.0
}

/**
 * fluid.js `PBFFluid`: Poly6 density, Spiky gradients, Jacobi position correction, pairwise
 * viscosity and conservative pigment diffusion, vorticity confinement. [bounds] is the inside box
 * the JavaScript builds from `bounds` and puts first among the colliders. Draw [count] particles;
 * [pigment] holds three concentration channels per particle.
 */
internal class PBFFluid(
    positions: FloatArray,
    capacity: Int? = null,
    val particleRadius: Double = .025,
    smoothingRadius: Double = .12,
    val restDensity: Double = 1000.0,
    particleMass: Double? = null,
    var iterations: Int = 5,
    gravity: DoubleArray = doubleArrayOf(0.0, -9.81, 0.0),
    bounds: BoxCollider? = null,
    colliders: List<Collider> = emptyList(),
    var viscosity: Double = .2,
    var pigmentDiffusion: Double = .08,
    var vorticity: Double = .015,
    var artificialPressure: Double = .0003,
    var lambdaEpsilon: Double = 1e-6,
    var damping: Double = .04,
    fields: List<ForceField> = emptyList(),
) : ParticleBody {
    init {
        requireFinite(positions, 3, "positions")
    }

    override var count = positions.size / 3
        private set
    val capacity = capacity ?: max(count * 2, 256)

    init {
        require(this.capacity >= count) { "capacity must hold all particles" }
        require(smoothingRadius > 0 && particleRadius > 0 && restDensity > 0) {
            "positive fluid dimensions and density required"
        }
    }

    override val positions = FloatArray(this.capacity * 3).also { positions.copyInto(it) }
    override val velocities = FloatArray(this.capacity * 3)
    val previous = FloatArray(this.capacity * 3)
    val beforeVelocity = FloatArray(this.capacity * 3)
    val pigment = FloatArray(this.capacity * 3)
    private val delta = FloatArray(this.capacity * 3)
    private val omega = FloatArray(this.capacity * 3)
    val density = FloatArray(this.capacity)
    val lambda = FloatArray(this.capacity)
    val h = smoothingRadius
    private val h2 = h * h
    private val poly6 = 315 / (64 * PI * h.pow(9))
    private val spiky = -45 / (PI * h.pow(6))
    val gravity: DoubleArray = gravity.copyOf()
    val colliders: MutableList<Collider> = (listOfNotNull(bounds) + colliders).toMutableList()
    val fields: MutableList<ForceField> = fields.toMutableList()
    private val hash = SpatialHash(h)
    private val neighbors = IntList()
    private val force = DoubleArray(3)
    private val pairI = IntList()
    private val pairJ = IntList()
    private var pairWeights = DoubleArray(64)
    private val degree = IntArray(this.capacity)
    val contacts = LinkedHashMap<Int, BodyContact>()
    var time = 0.0
        private set
    var correctionClamps = 0
        private set
    val particleMass: Double
    val massLedger: MassLedger

    init {
        hash.build(this.positions, count)
        var maxKernel = 0.0
        for (i in 0 until count) {
            val k = 3 * i
            hash.query(this.positions(k), this.positions(k + 1), this.positions(k + 2), neighbors)
            var rho = 0.0
            for (n in 0 until neighbors.size) rho += kernel(distanceSquared(k, 3 * neighbors[n]))
            maxKernel = max(maxKernel, rho)
        }
        this.particleMass = particleMass ?: (restDensity / max(maxKernel, kernel(0.0)))
        require(this.particleMass > 0) { "particleMass must be positive" }
        massLedger = MassLedger(count * this.particleMass)
    }

    override val couplingRadius: Double
        get() = particleRadius

    override fun couplingInverseMass(index: Int): Double = 1 / particleMass

    fun kernel(r2: Double): Double = if (r2 >= h2) 0.0 else poly6 * (h2 - r2).pow(3)

    val mass: Double
        get() = count * particleMass

    val pigmentMass: DoubleArray
        get() {
            val sum = DoubleArray(3)
            for (k in 0 until count * 3) sum[k % 3] += pigment(k) * particleMass
            return sum
        }

    /** Adds one particle; -1 without changing mass when the domain is full. */
    fun inject(
        position: DoubleArray,
        velocity: DoubleArray = DoubleArray(3),
        pigment: DoubleArray = DoubleArray(3),
    ): Int {
        if (count == capacity) return -1
        requireFinite(position, 3, "position")
        requireFinite(velocity, 3, "velocity")
        val i = count++
        for (a in 0 until 3) {
            positions[3 * i + a] = position[a].toFloat()
            previous[3 * i + a] = position[a].toFloat()
            velocities[3 * i + a] = velocity[a].toFloat()
            this.pigment[3 * i + a] = clamp(pigment[a], 0.0, 1.0).toFloat()
        }
        massLedger.injected += particleMass
        return i
    }

    /** Swap-removes particle [index]; indices are not permanent identities. */
    fun remove(index: Int): RemovedParticle {
        require(index in 0 until count) { "particle index out of range" }
        val k = index * 3
        val last = (count - 1) * 3
        val result =
            RemovedParticle(
                particleMass,
                DoubleArray(3) { positions(k + it) },
                DoubleArray(3) { velocities(k + it) },
                DoubleArray(3) { pigment(k + it) },
            )
        for (array in listOf(positions, previous, velocities, pigment)) {
            for (a in 0 until 3) {
                array[k + a] = array[last + a]
                array[last + a] = 0f
            }
        }
        count--
        massLedger.removed += particleMass
        return result
    }

    /** Moves particle [index] into the equal-mass [target]; false when the target is full. */
    fun transferTo(
        target: PBFFluid,
        index: Int,
    ): Boolean {
        if (target.capacity == target.count) return false
        require(abs(target.particleMass - particleMass) <= 1e-8) {
            "transfer requires equal particle mass"
        }
        require(index in 0 until count) { "particle index out of range" }
        val k = 3 * index
        target.inject(
            DoubleArray(3) { positions(k + it) },
            DoubleArray(3) { velocities(k + it) },
            DoubleArray(3) { pigment(k + it) },
        )
        remove(index)
        return true
    }

    fun addImpulse(
        point: DoubleArray,
        radius: Double,
        impulse: DoubleArray,
    ) {
        val weights = impulseWeights(positions, count, point, radius, impulse)
        val sum = weights.sumOf { it.toDouble() }
        if (!(sum > 0)) return
        for (k in 0 until count * 3) {
            velocities[k] =
                (velocities(k) + impulse[k % 3] * weights(k / 3) / (sum * particleMass)).toFloat()
        }
    }

    private fun distanceSquared(
        k: Int,
        l: Int,
    ): Double {
        val x = positions(k) - positions(l)
        val y = positions(k + 1) - positions(l + 1)
        val z = positions(k + 2) - positions(l + 2)
        return x * x + y * y + z * z
    }

    private fun collide() {
        for (i in 0 until count) {
            for (c in colliders.indices) {
                val hit = colliders[c].project(positions, 3 * i, particleRadius) ?: continue
                contacts[i * colliders.size + c] = BodyContact(i, hit)
            }
        }
    }

    private fun densityPass() {
        val p = positions
        val mr = particleMass / restDensity
        for (i in 0 until count) {
            val k = i * 3
            hash.query(p(k), p(k + 1), p(k + 2), neighbors)
            var rho = 0.0
            var gx = 0.0
            var gy = 0.0
            var gz = 0.0
            var sum = 0.0
            for (n in 0 until neighbors.size) {
                val j = neighbors[n]
                val x = p(k) - p(3 * j)
                val y = p(k + 1) - p(3 * j + 1)
                val z = p(k + 2) - p(3 * j + 2)
                val r2 = x * x + y * y + z * z
                if (r2 >= h2) continue
                rho += particleMass * kernel(r2)
                if (j != i && r2 >= EPS) {
                    val r = sqrt(r2)
                    val g = mr * spiky * ((h - r) * (h - r)) / r
                    val dx = g * x
                    val dy = g * y
                    val dz = g * z
                    gx += dx
                    gy += dy
                    gz += dz
                    sum += dx * dx + dy * dy + dz * dz
                }
            }
            density[i] = rho.toFloat()
            sum += gx * gx + gy * gy + gz * gz
            lambda[i] = (-max(0.0, rho / restDensity - 1) / (sum + lambdaEpsilon)).toFloat()
        }
    }

    private fun correctionPass() {
        val p = positions
        val mr = particleMass / restDensity
        val wq = kernel(.3 * h * (.3 * h))
        val limit = .2 * h
        delta.fill(0f, 0, count * 3)
        for (i in 0 until count) {
            val k = i * 3
            hash.query(p(k), p(k + 1), p(k + 2), neighbors)
            for (n in 0 until neighbors.size) {
                val j = neighbors[n]
                val x = p(k) - p(3 * j)
                val y = p(k + 1) - p(3 * j + 1)
                val z = p(k + 2) - p(3 * j + 2)
                val r2 = x * x + y * y + z * z
                if (i == j || r2 >= h2 || r2 < EPS) continue
                val r = sqrt(r2)
                val sc = -artificialPressure * (kernel(r2) / wq).pow(4)
                val g = mr * (lambda(i) + lambda(j) + sc) * spiky * ((h - r) * (h - r)) / r
                delta[k] = (delta(k) + g * x).toFloat()
                delta[k + 1] = (delta(k + 1) + g * y).toFloat()
                delta[k + 2] = (delta(k + 2) + g * z).toFloat()
            }
            val len = hypot(delta(k), delta(k + 1), delta(k + 2))
            if (len > limit) {
                correctionClamps++
                for (a in 0 until 3) delta[k + a] = (delta(k + a) * (limit / len)).toFloat()
            }
        }
        for (k in 0 until count * 3) p[k] = (p(k) + delta(k)).toFloat()
    }

    private fun diffuse(dt: Double) {
        val p = positions
        val w0 = kernel(0.0)
        pairI.clear()
        pairJ.clear()
        degree.fill(0, 0, count)
        for (i in 0 until count) {
            hash.query(p(3 * i), p(3 * i + 1), p(3 * i + 2), neighbors)
            for (n in 0 until neighbors.size) {
                val j = neighbors[n]
                if (j <= i) continue
                val r2 = distanceSquared(3 * i, 3 * j)
                if (r2 < h2) addPair(i, j, kernel(r2) / w0)
            }
        }
        var maxDegree = 1
        for (i in 0 until count) maxDegree = max(maxDegree, degree[i])
        exchange(velocities, viscosity, dt, maxDegree)
        exchange(pigment, pigmentDiffusion, dt, maxDegree)
    }

    private fun addPair(
        i: Int,
        j: Int,
        weight: Double,
    ) {
        if (pairJ.size == pairWeights.size) pairWeights = pairWeights.copyOf(pairWeights.size * 2)
        pairWeights[pairJ.size] = weight
        pairI.add(i)
        pairJ.add(j)
        degree[i]++
        degree[j]++
    }

    /** Pairwise symmetric exchange: conserves the summed [array] up to float roundoff. */
    private fun exchange(
        array: FloatArray,
        rate: Double,
        dt: Double,
        maxDegree: Int,
    ) {
        if (rate <= 0) return
        delta.fill(0f, 0, count * 3)
        val coeff = (1 - exp(-rate * dt)) / maxDegree
        for (e in 0 until pairJ.size) {
            val i = 3 * pairI[e]
            val j = 3 * pairJ[e]
            for (a in 0 until 3) {
                val d = (array(j + a) - array(i + a)) * pairWeights[e] * coeff
                delta[i + a] = (delta(i + a) + d).toFloat()
                delta[j + a] = (delta(j + a) - d).toFloat()
            }
        }
        for (k in 0 until count * 3) array[k] = (array(k) + delta(k)).toFloat()
    }

    private fun confineVorticity(dt: Double) {
        if (vorticity <= 0) return
        val p = positions
        val v = velocities
        val o = omega
        o.fill(0f, 0, count * 3)
        for (i in 0 until count) {
            val k = 3 * i
            hash.query(p(k), p(k + 1), p(k + 2), neighbors)
            for (n in 0 until neighbors.size) {
                val l = 3 * neighbors[n]
                val x = p(k) - p(l)
                val y = p(k + 1) - p(l + 1)
                val z = p(k + 2) - p(l + 2)
                val r = hypot(x, y, z)
                if (r < EPS || r >= h) continue
                val m = particleMass / max(density(neighbors[n]), restDensity * .1)
                val s = m * spiky * ((h - r) * (h - r)) / r
                val dx = v(l) - v(k)
                val dy = v(l + 1) - v(k + 1)
                val dz = v(l + 2) - v(k + 2)
                o[k] = (o(k) + (dy * (z * s) - dz * (y * s))).toFloat()
                o[k + 1] = (o(k + 1) + (dz * (x * s) - dx * (z * s))).toFloat()
                o[k + 2] = (o(k + 2) + (dx * (y * s) - dy * (x * s))).toFloat()
            }
        }
        delta.fill(0f, 0, count * 3)
        for (i in 0 until count) confine(i, dt)
        for (k in 0 until count * 3) v[k] = (v(k) + delta(k)).toFloat()
    }

    private fun confine(
        i: Int,
        dt: Double,
    ) {
        val p = positions
        val o = omega
        val k = 3 * i
        val om = hypot(o(k), o(k + 1), o(k + 2))
        var ex = 0.0
        var ey = 0.0
        var ez = 0.0
        hash.query(p(k), p(k + 1), p(k + 2), neighbors)
        for (n in 0 until neighbors.size) {
            val l = 3 * neighbors[n]
            val x = p(k) - p(l)
            val y = p(k + 1) - p(l + 1)
            val z = p(k + 2) - p(l + 2)
            val r = hypot(x, y, z)
            if (r < EPS || r >= h) continue
            val m = particleMass / max(density(neighbors[n]), restDensity * .1)
            val s = m * (hypot(o(l), o(l + 1), o(l + 2)) - om) * spiky * ((h - r) * (h - r)) / r
            ex += x * s
            ey += y * s
            ez += z * s
        }
        val len = hypot(ex, ey, ez)
        if (len > EPS) {
            val s = vorticity * dt / len
            delta[k] = (s * (ey * o(k + 2) - ez * o(k + 1))).toFloat()
            delta[k + 1] = (s * (ez * o(k) - ex * o(k + 2))).toFloat()
            delta[k + 2] = (s * (ex * o(k + 1) - ey * o(k))).toFloat()
        }
    }

    override fun step(dt: Double) {
        requireValidDt(dt)
        positions.copyInto(previous)
        contacts.clear()
        val decay = exp(-damping * dt)
        for (i in 0 until count) {
            integrate(positions, velocities, 3 * i, gravity, fields, time, dt, decay, force)
        }
        velocities.copyInto(beforeVelocity)
        collide()
        repeat(iterations) {
            hash.build(positions, count)
            densityPass()
            correctionPass()
            collide()
        }
        for (k in 0 until count * 3) velocities[k] = ((positions(k) - previous(k)) / dt).toFloat()
        hash.build(positions, count)
        densityPass()
        diffuse(dt)
        confineVorticity(dt)
        for (contact in contacts.values) {
            resolveContactVelocity(velocities, 3 * contact.index, beforeVelocity, contact.hit)
        }
        time += dt
    }
}

/** fluid.js `makeParticleBlock`: an nx × ny × nz lattice, layers from [origin] upward. */
internal fun makeParticleBlock(
    nx: Int = 8,
    ny: Int = 8,
    nz: Int = 8,
    spacing: Double = .075,
    origin: DoubleArray = doubleArrayOf(0.0, 0.0, 0.0),
): FloatArray {
    val p = FloatArray(nx * ny * nz * 3)
    for (v in 0 until nx * ny * nz) {
        val x = v % nx
        val z = v / nx % nz
        val y = v / (nx * nz)
        p[3 * v] = (origin[0] + (x - (nx - 1) / 2.0) * spacing).toFloat()
        p[3 * v + 1] = (origin[1] + y * spacing).toFloat()
        p[3 * v + 2] = (origin[2] + (z - (nz - 1) / 2.0) * spacing).toFloat()
    }
    return p
}
