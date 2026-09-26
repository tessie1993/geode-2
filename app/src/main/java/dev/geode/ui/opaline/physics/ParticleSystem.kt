package dev.geode.ui.opaline.physics

import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** particles.js `WindField`: directional forcing, travelling gust, height shear, disturbance. */
internal class WindField(
    var direction: DoubleArray = doubleArrayOf(1.0, 0.0, .2),
    var strength: Double = .6,
    var gust: Double = .35,
    var frequency: Double = .7,
    var shear: Double = 0.0,
    var turbulence: Double = .15,
    var scale: Double = .7,
) : ForceField {
    override var metadata: FieldMetadata? = null

    override fun sample(
        x: Double,
        y: Double,
        z: Double,
        t: Double,
        out: DoubleArray,
    ): DoubleArray {
        val s = scale
        val g = strength * (1 + gust * sin(t * frequency - x * .35)) + shear * y
        out[0] = direction[0] * g + turbulence * sin(y * s + t * .53) * cos(z * s - t * .41)
        out[1] = direction[1] * g + turbulence * sin(z * s + t * .37) * cos(x * s + t * .29)
        out[2] = direction[2] * g + turbulence * sin(x * s - t * .43) * cos(y * s + t * .31)
        return out
    }

    override fun assign(options: Map<String, Any?>) {
        direction = options.vector("direction") ?: direction
        strength = options.number("strength") ?: strength
        gust = options.number("gust") ?: gust
        frequency = options.number("frequency") ?: frequency
        shear = options.number("shear") ?: shear
        turbulence = options.number("turbulence") ?: turbulence
        scale = options.number("scale") ?: scale
    }
}

/** particles.js `VortexField`: swirl about a 3D axis, radial attraction and axial lift. */
internal class VortexField(
    var center: DoubleArray = doubleArrayOf(0.0, 0.0, 0.0),
    axis: DoubleArray = doubleArrayOf(0.0, 1.0, 0.0),
    var strength: Double = 1.0,
    var radial: Double = .1,
    var radius: Double = 2.0,
    var lift: Double = .2,
) : ForceField {
    override var metadata: FieldMetadata? = null

    /** Normalised on assignment; a zero axis is rejected. */
    var axis: DoubleArray = unit(axis)
        set(value) {
            field = unit(value)
        }

    override fun sample(
        x: Double,
        y: Double,
        z: Double,
        t: Double,
        out: DoubleArray,
    ): DoubleArray {
        val a = axis
        var rx = x - center[0]
        var ry = y - center[1]
        var rz = z - center[2]
        val along = rx * a[0] + ry * a[1] + rz * a[2]
        rx -= along * a[0]
        ry -= along * a[1]
        rz -= along * a[2]
        val d = hypot(rx, ry, rz)
        val fall = exp(-(d / radius * (d / radius)))
        val s = strength * fall / max(d, .08)
        out[0] = (a[1] * rz - a[2] * ry) * s - rx * radial + lift * a[0] * fall
        out[1] = (a[2] * rx - a[0] * rz) * s - ry * radial + lift * a[1] * fall
        out[2] = (a[0] * ry - a[1] * rx) * s - rz * radial + lift * a[2] * fall
        return out
    }

    override fun assign(options: Map<String, Any?>) {
        center = options.vector("center") ?: center
        axis = options.vector("axis") ?: axis
        strength = options.number("strength") ?: strength
        radial = options.number("radial") ?: radial
        radius = options.number("radius") ?: radius
        lift = options.number("lift") ?: lift
    }

    private fun unit(v: DoubleArray): DoubleArray {
        val n = hypot(v[0], v[1], v[2])
        require(!(n < EPS)) { "vortex axis cannot be zero" }
        return DoubleArray(3) { v[it] / n }
    }
}

/** particles.js `RadialField`: bounded attractor or repulsor with quadratic falloff. */
internal class RadialField(
    var center: DoubleArray = doubleArrayOf(0.0, 0.0, 0.0),
    var strength: Double = 1.0,
    var radius: Double = 1.0,
    var attract: Boolean = false,
) : ForceField {
    override var metadata: FieldMetadata? = null

    override fun sample(
        x: Double,
        y: Double,
        z: Double,
        t: Double,
        out: DoubleArray,
    ): DoubleArray {
        val dx = x - center[0]
        val dy = y - center[1]
        val dz = z - center[2]
        val r = hypot(dx, dy, dz)
        val fall = max(0.0, 1 - r / radius)
        val s = (if (attract) -1 else 1) * strength * (fall * fall) / max(r, .02)
        out[0] = dx * s
        out[1] = dy * s
        out[2] = dz * s
        return out
    }

    override fun assign(options: Map<String, Any?>) {
        center = options.vector("center") ?: center
        strength = options.number("strength") ?: strength
        radius = options.number("radius") ?: radius
        attract = options["attract"] as? Boolean ?: attract
    }
}

/** particles.js `VortexRingField`: toroidal circulation around a rising ring centre. */
internal class VortexRingField(
    var center: DoubleArray = doubleArrayOf(0.0, 0.0, 0.0),
    var radius: Double = .7,
    var core: Double = .3,
    var strength: Double = 1.0,
    var speed: Double = .1,
) : ForceField {
    override var metadata: FieldMetadata? = null

    override fun sample(
        x: Double,
        y: Double,
        z: Double,
        t: Double,
        out: DoubleArray,
    ): DoubleArray {
        val px = x - center[0]
        val pz = z - center[2]
        val py = y - (center[1] + t * speed)
        val r = sqrt(px * px + pz * pz)
        val dr = r - radius
        val d2 = dr * dr + py * py
        val s = strength * exp(-d2 / (core * core)) / max(sqrt(d2), .025)
        val rad = -py * s
        out[0] = rad * px / max(r, EPS)
        out[1] = dr * s
        out[2] = rad * pz / max(r, EPS)
        return out
    }

    override fun assign(options: Map<String, Any?>) {
        center = options.vector("center") ?: center
        radius = options.number("radius") ?: radius
        core = options.number("core") ?: core
        strength = options.number("strength") ?: strength
        speed = options.number("speed") ?: speed
    }
}

/** particles.js `BuoyancyField`: submerged-fraction lift below a horizontal surface. */
internal class BuoyancyField(
    var surfaceY: Double = 0.0,
    var density: Double = 1000.0,
    var bodyDensity: Double = 600.0,
    var gravity: Double = 9.81,
    var thickness: Double = .1,
) : ForceField {
    override var metadata: FieldMetadata? = null

    override fun sample(
        x: Double,
        y: Double,
        z: Double,
        t: Double,
        out: DoubleArray,
    ): DoubleArray {
        out[0] = 0.0
        out[2] = 0.0
        val submerged = clamp((surfaceY - y + thickness * .5) / thickness, 0.0, 1.0)
        out[1] = gravity * density / bodyDensity * submerged
        return out
    }

    override fun assign(options: Map<String, Any?>) {
        surfaceY = options.number("surfaceY") ?: surfaceY
        density = options.number("density") ?: density
        bodyDensity = options.number("bodyDensity") ?: bodyDensity
        gravity = options.number("gravity") ?: gravity
        thickness = options.number("thickness") ?: thickness
    }
}

/**
 * particles.js `ParticleSystem`: inertial particles with fields, static colliders, pairwise
 * impulses, quaternion spin, optional XPBD [links], threshold adhesion and trails. The latest
 * trail sample of particle i is `trails[(trailHead * capacity + i) * 3 + axis]`.
 */
internal class ParticleSystem(
    val capacity: Int = 256,
    seed: Int = 1,
    gravity: DoubleArray = doubleArrayOf(0.0, -.12, 0.0),
    var drag: Double = .5,
    fields: List<ForceField> = emptyList(),
    colliders: List<Collider> = emptyList(),
    var particleCollisions: Boolean = true,
    var restitution: Double = .35,
    var friction: Double = .2,
    val trailLength: Int = 0,
) : ParticleBody {
    init {
        require(capacity >= 1) { "capacity must be a positive integer" }
    }

    override var count = 0
        private set
    override val positions = FloatArray(capacity * 3)
    override val velocities = FloatArray(capacity * 3)
    override val radii = FloatArray(capacity)
    val inverseMass = FloatArray(capacity)
    val ages = FloatArray(capacity)
    val lifetimes = FloatArray(capacity)
    val colors = FloatArray(capacity * 3)
    val orientations = FloatArray(capacity * 4)
    val angularVelocities = FloatArray(capacity * 3)
    val gravity: DoubleArray = gravity.copyOf()
    val fields: MutableList<ForceField> = fields.toMutableList()
    val colliders: MutableList<Collider> = colliders.toMutableList()
    val random = SeededRandom(seed)
    var time = 0.0
        private set
    val trails = FloatArray(capacity * trailLength * 3)
    var trailHead = 0
        private set
    val beforeVelocity = FloatArray(capacity * 3)
    private val linkPrevious = FloatArray(capacity * 3)
    var links: DistanceConstraints? = null
        private set
    var linkCompliance = 1e-7
    val stuck = BooleanArray(capacity)
    var adhesionAcceleration = 0.0
    var stickSpeed = 0.0
    var emittedMass = 0.0
        private set
    var expiredMass = 0.0
        private set

    /** presets.js `metadata`: the catalogue preset this system was created from. */
    var preset: ParticlePreset? = null
    private val force = DoubleArray(3)
    private val adhesion = DoubleArray(3)
    private val neighbors = IntList()

    override val couplingRadius: Double?
        get() = null

    override fun couplingInverseMass(index: Int): Double = inverseMass(index)

    val mass: Double
        get() = (0 until count).sumOf { 1 / inverseMass(it) }

    /** Adds one particle; -1 when the system is full. */
    fun emit(
        position: DoubleArray = DoubleArray(3),
        velocity: DoubleArray = DoubleArray(3),
        radius: Double = .025,
        mass: Double = .001,
        color: DoubleArray = doubleArrayOf(.45, .85, 1.0),
        lifetime: Double = Double.POSITIVE_INFINITY,
        spin: DoubleArray = DoubleArray(3),
    ): Int {
        if (count == capacity) return -1
        requireFinite(position, 3, "position")
        requireFinite(velocity, 3, "velocity")
        requireFinite(color, 3, "color")
        requireFinite(spin, 3, "spin")
        require(radius > 0 && mass > 0 && lifetime > 0) {
            "radius, mass and lifetime must be positive"
        }
        val i = count++
        val k = 3 * i
        for (a in 0 until 3) {
            positions[k + a] = position[a].toFloat()
            velocities[k + a] = velocity[a].toFloat()
            colors[k + a] = color[a].toFloat()
            angularVelocities[k + a] = spin[a].toFloat()
            for (s in 0 until trailLength) trails[(s * capacity + i) * 3 + a] = positions[k + a]
        }
        orientations.fill(0f, i * 4, i * 4 + 3)
        orientations[i * 4 + 3] = 1f
        stuck[i] = false
        radii[i] = radius.toFloat()
        inverseMass[i] = (1 / mass).toFloat()
        ages[i] = 0f
        lifetimes[i] = lifetime.toFloat()
        emittedMass += mass
        return i
    }

    /** Swap-removes particle [i], re-indexing the links. */
    fun remove(i: Int) {
        if (i < 0 || i >= count) return
        expiredMass += 1 / inverseMass(i)
        val last = --count
        links?.let { links = relink(it, i, last) }
        stuck[i] = stuck[last]
        val arrays =
            listOf(
                positions to 3,
                velocities to 3,
                colors to 3,
                angularVelocities to 3,
                orientations to 4,
                radii to 1,
                inverseMass to 1,
                ages to 1,
                lifetimes to 1,
            )
        for ((array, n) in arrays) array.copyInto(array, i * n, last * n, last * n + n)
        for (s in 0 until trailLength) {
            val from = (s * capacity + last) * 3
            trails.copyInto(trails, (s * capacity + i) * 3, from, from + 3)
        }
    }

    private fun relink(
        links: DistanceConstraints,
        i: Int,
        last: Int,
    ): DistanceConstraints {
        val indices = ArrayList<Int>()
        val rest = ArrayList<Float>()
        for (e in links.rest.indices) {
            val a = links.indices[2 * e]
            val b = links.indices[2 * e + 1]
            if (a == i || b == i) continue
            indices.add(if (a == last) i else a)
            indices.add(if (b == last) i else b)
            rest.add(links.rest[e])
        }
        return DistanceConstraints(indices.toIntArray(), rest.toFloatArray(), FloatArray(rest.size))
    }

    /** Emits [count] seeded particles from [point] around [normal]. */
    fun burst(
        point: DoubleArray,
        normal: DoubleArray = doubleArrayOf(0.0, 1.0, 0.0),
        strength: Double = 1.0,
        count: Int = 24,
    ) {
        repeat(count) {
            val d = DoubleArray(3) { random.next() - .5 }
            val h = hypot(d[0], d[1], d[2])
            val len = if (h > 0) h else 1.0
            val velocity =
                DoubleArray(3) {
                    (d[it] / len * .5 + normal[it]) * strength * (.45 + random.next() * .55)
                }
            emit(
                position = point.copyOf(),
                velocity = velocity,
                radius = .012 + random.next() * .025,
                lifetime = 2 + random.next() * 4,
                spin = DoubleArray(3) { d[it] * 6 },
            )
        }
    }

    fun addImpulse(
        point: DoubleArray,
        radius: Double,
        impulse: DoubleArray,
    ) = addRadialImpulse(positions, velocities, inverseMass, count, point, radius, impulse)

    /** Replaces the XPBD tether links. */
    fun connect(
        pairs: List<IntArray>,
        compliance: Double = 1e-7,
    ) {
        links = distanceConstraints(positions, pairs)
        linkCompliance = compliance
    }

    fun releaseParticle(i: Int) {
        stuck[i] = false
    }

    private fun collidePairs() {
        if (count == 0) return
        var largest = 0.0
        for (i in 0 until count) largest = max(largest, radii(i))
        val hash = SpatialHash(2 * largest)
        hash.build(positions, count)
        for (i in 0 until count) {
            hash.query(positions(3 * i), positions(3 * i + 1), positions(3 * i + 2), neighbors)
            for (n in 0 until neighbors.size) if (neighbors[n] > i) collidePair(i, neighbors[n])
        }
    }

    private fun collidePair(
        i: Int,
        j: Int,
    ) {
        val p = positions
        val v = velocities
        val w = inverseMass
        val k = 3 * i
        val l = 3 * j
        var x = p(k) - p(l)
        var y = p(k + 1) - p(l + 1)
        var z = p(k + 2) - p(l + 2)
        var d = hypot(x, y, z)
        val r = radii(i) + radii(j)
        if (d >= r) return
        if (d < EPS) {
            x = 1.0
            y = 0.0
            z = 0.0
            d = 1e-5
        }
        val n = doubleArrayOf(x / d, y / d, z / d)
        if (n[0] > 1) n[0] = 1.0
        val sum = w(i) + w(j)
        for (a in 0 until 3) {
            val delta = n[a] * (r - d) / sum
            p[k + a] = (p(k + a) + delta * w(i)).toFloat()
            p[l + a] = (p(l + a) - delta * w(j)).toFloat()
        }
        val vn = normalSpeed(k, l, n)
        if (!(vn < 0)) return
        val impulse = -(1 + restitution) * vn / sum
        for (a in 0 until 3) {
            v[k + a] = (v(k + a) + n[a] * impulse * w(i)).toFloat()
            v[l + a] = (v(l + a) - n[a] * impulse * w(j)).toFloat()
        }
        val current = normalSpeed(k, l, n)
        val tangent = DoubleArray(3) { v(k + it) - v(l + it) - current * n[it] }
        val tl = hypot(tangent[0], tangent[1], tangent[2])
        val fr = min(friction * impulse, tl / sum)
        if (!(tl > EPS)) return
        for (a in 0 until 3) {
            v[k + a] = (v(k + a) - tangent[a] / tl * fr * w(i)).toFloat()
            v[l + a] = (v(l + a) + tangent[a] / tl * fr * w(j)).toFloat()
        }
    }

    private fun normalSpeed(
        k: Int,
        l: Int,
        n: DoubleArray,
    ): Double {
        val v = velocities
        return (v(k) - v(l)) * n[0] + (v(k + 1) - v(l + 1)) * n[1] + (v(k + 2) - v(l + 2)) * n[2]
    }

    override fun step(dt: Double) {
        requireValidDt(dt)
        val decay = exp(-drag * dt)
        var i = 0
        while (i < count) {
            ages[i] = (ages(i) + dt).toFloat()
            if (ages[i] >= lifetimes[i]) {
                remove(i)
                continue
            }
            advance(i, dt, decay)
            i++
        }
        if (particleCollisions) collidePairs()
        links?.let { solveLinks(it, dt) }
        if (trailLength > 0) {
            trailHead = (trailHead + 1) % trailLength
            positions.copyInto(trails, trailHead * capacity * 3, 0, count * 3)
        }
        time += dt
    }

    private fun advance(
        i: Int,
        dt: Double,
        decay: Double,
    ) {
        val k = 3 * i
        if (stuck[i]) {
            gravity.copyInto(adhesion)
            for (field in fields) {
                force.fill(0.0)
                field.sample(positions(k), positions(k + 1), positions(k + 2), time, force)
                for (a in 0 until 3) adhesion[a] += force[a]
            }
            if (hypot(adhesion[0], adhesion[1], adhesion[2]) < adhesionAcceleration) return
            stuck[i] = false
        }
        integrate(positions, velocities, k, gravity, fields, time, dt, decay, force)
        velocities.copyInto(beforeVelocity, k, k, k + 3)
        for (collider in colliders) {
            val hit = collider.project(positions, k, radii(i)) ?: continue
            resolveContactVelocity(velocities, k, beforeVelocity, hit)
            val speed = hypot(beforeVelocity(k), beforeVelocity(k + 1), beforeVelocity(k + 2))
            if (adhesionAcceleration > 0 && speed < stickSpeed) {
                stuck[i] = true
                velocities.fill(0f, k, k + 3)
            }
        }
        spin(i, dt)
    }

    private fun spin(
        i: Int,
        dt: Double,
    ) {
        val o = orientations
        val q = i * 4
        val x = o(q)
        val y = o(q + 1)
        val z = o(q + 2)
        val w = o(q + 3)
        val a = angularVelocities(3 * i)
        val b = angularVelocities(3 * i + 1)
        val c = angularVelocities(3 * i + 2)
        val h = dt * .5
        o[q] = (x + h * (a * w + b * z - c * y)).toFloat()
        o[q + 1] = (y + h * (b * w + c * x - a * z)).toFloat()
        o[q + 2] = (z + h * (c * w + a * y - b * x)).toFloat()
        o[q + 3] = (w + h * (-a * x - b * y - c * z)).toFloat()
        val xy = o(q) * o(q) + o(q + 1) * o(q + 1)
        val norm = sqrt(xy + o(q + 2) * o(q + 2) + o(q + 3) * o(q + 3))
        for (j in 0 until 4) o[q + j] = (o(q + j) / norm).toFloat()
    }

    private fun solveLinks(
        links: DistanceConstraints,
        dt: Double,
    ) {
        positions.copyInto(linkPrevious)
        links.lambda.fill(0f)
        repeat(8) { solveDistances(positions, inverseMass, links, linkCompliance, dt) }
        for (k in 0 until count * 3) {
            velocities[k] = (velocities(k) + (positions(k) - linkPrevious(k)) / dt).toFloat()
        }
    }
}
