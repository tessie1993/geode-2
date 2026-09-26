package dev.geode.ui.opaline.physics

import kotlin.math.exp

/** A common.js `pin`: the mount target and the inverse mass it replaced. */
internal class Pin(
    val target: DoubleArray,
    val inverseMass: Float,
)

/** A common.js `grab`: a footprint keeping its offsets while its centroid is pulled to [target]. */
internal class Grab(
    val indices: IntArray,
    var target: DoubleArray,
    val offsets: FloatArray,
    val compliance: Double,
    val lambda: FloatArray,
)

/** A common.js `contacts` value `{i, ...hit}`. */
internal class BodyContact(
    val index: Int,
    val hit: CollisionHit,
)

/** common.js `distanceConstraints` result. */
internal class DistanceConstraints(
    val indices: IntArray,
    val rest: FloatArray,
    val lambda: FloatArray,
)

/**
 * common.js `ConstraintBody`: position-particle plumbing shared by the soft body, cloth, rod and
 * film. [inverseMass] null fills every particle with [uniformInverseMass] (the scalar option).
 */
internal abstract class ConstraintBody(
    positions: FloatArray,
    inverseMass: FloatArray?,
    uniformInverseMass: Float,
    gravity: DoubleArray,
    var damping: Double,
    var radius: Double,
    colliders: List<Collider>,
    fields: List<ForceField>,
    var iterations: Int,
) : ParticleBody {
    init {
        requireFinite(positions, 3, "positions")
    }

    override val positions: FloatArray = positions.copyOf()
    override val count = positions.size / 3
    override val velocities = FloatArray(positions.size)
    val previous: FloatArray = positions.copyOf()
    val beforeVelocity = FloatArray(positions.size)
    val inverseMass: FloatArray = inverseMass?.copyOf() ?: FloatArray(count) { uniformInverseMass }

    init {
        val valid = this.inverseMass.all { it.isFinite() && it >= 0f }
        require(this.inverseMass.size == count && valid) { "invalid inverse masses" }
    }

    val gravity: DoubleArray = gravity.copyOf()
    val colliders: MutableList<Collider> = colliders.toMutableList()
    val fields: MutableList<ForceField> = fields.toMutableList()
    val pins = LinkedHashMap<Int, Pin>()
    val grabs = LinkedHashMap<String, Grab>()
    val contacts = LinkedHashMap<Int, BodyContact>()
    var time = 0.0
    private val force = DoubleArray(3)

    override val couplingRadius: Double?
        get() = radius

    override fun couplingInverseMass(index: Int): Double = inverseMass(index)

    /** Mounts particle [index] at [target] (its current position when null). */
    fun pin(
        index: Int,
        target: DoubleArray? = null,
    ) {
        checkIndex(index)
        val point = target ?: DoubleArray(3) { positions(3 * index + it) }
        requireFinite(point, 3, "pin target")
        val saved = pins[index]?.inverseMass ?: inverseMass[index]
        pins[index] = Pin(point.copyOf(), saved)
        inverseMass[index] = 0f
        applyPins()
    }

    fun unpin(index: Int) {
        val pin = pins.remove(index) ?: return
        inverseMass[index] = pin.inverseMass
    }

    fun checkIndex(index: Int) {
        require(index in 0 until count) { "particle index out of range" }
    }

    fun grab(
        id: String,
        indices: IntArray,
        target: DoubleArray,
        compliance: Double = 1e-6,
    ) {
        requireFinite(target, 3, "grab target")
        require(compliance >= 0) { "grab compliance must be nonnegative" }
        require(indices.isNotEmpty()) { "grab needs vertices" }
        val center = DoubleArray(3)
        for (i in indices) {
            checkIndex(i)
            for (a in 0 until 3) center[a] += positions(3 * i + a) / indices.size
        }
        val offsets = FloatArray(indices.size * 3)
        for (j in indices.indices) {
            for (a in 0 until 3) {
                offsets[j * 3 + a] = (positions(indices[j] * 3 + a) - center[a]).toFloat()
            }
        }
        val lambda = FloatArray(indices.size * 3)
        grabs[id] = Grab(indices.copyOf(), target.copyOf(), offsets, compliance, lambda)
    }

    fun moveGrab(
        id: String,
        target: DoubleArray,
    ) {
        requireFinite(target, 3, "grab target")
        grabs[id]?.target = target.copyOf()
    }

    fun releaseGrab(id: String) {
        grabs.remove(id)
    }

    /** Releases every grab while keeping deformation and velocity; pins stay mounted. */
    fun cancelInteractions() {
        grabs.clear()
    }

    fun impulse(
        index: Int,
        impulse: DoubleArray,
    ) {
        checkIndex(index)
        for (a in 0 until 3) {
            velocities[3 * index + a] =
                (velocities(3 * index + a) + impulse[a] * inverseMass[index]).toFloat()
        }
    }

    protected fun applyPins() {
        for ((i, pin) in pins) {
            for (a in 0 until 3) {
                positions[3 * i + a] = pin.target[a].toFloat()
                velocities[3 * i + a] = 0f
            }
        }
    }

    protected fun begin(dt: Double) {
        requireValidDt(dt)
        positions.copyInto(previous)
        contacts.clear()
        for (grab in grabs.values) grab.lambda.fill(0f)
        val attenuation = exp(-damping * dt)
        for (i in 0 until count) {
            if (inverseMass[i] == 0f) continue
            integrate(positions, velocities, 3 * i, gravity, fields, time, dt, attenuation, force)
        }
        velocities.copyInto(beforeVelocity)
        applyPins()
    }

    protected fun solveGrabs(dt: Double) {
        for (grab in grabs.values) {
            val alpha = grab.compliance / (dt * dt)
            for (j in grab.indices.indices) {
                val i = grab.indices[j]
                val w = inverseMass(i)
                if (w == 0.0) continue
                for (a in 0 until 3) {
                    val s = j * 3 + a
                    val k = i * 3 + a
                    val c = positions(k) - grab.target[a] - grab.offsets(s)
                    val dl = (-c - alpha * grab.lambda(s)) / (w + alpha)
                    grab.lambda[s] = (grab.lambda(s) + dl).toFloat()
                    positions[k] = (positions(k) + w * dl).toFloat()
                }
            }
        }
    }

    protected fun collide() {
        for (i in 0 until count) {
            if (inverseMass[i] == 0f) continue
            for (c in colliders.indices) {
                val hit = colliders[c].project(positions, 3 * i, radius) ?: continue
                contacts[i * colliders.size + c] = BodyContact(i, hit)
            }
        }
    }

    protected fun finish(dt: Double) {
        for (k in 0 until count * 3) velocities[k] = ((positions(k) - previous(k)) / dt).toFloat()
        for (contact in contacts.values) {
            resolveContactVelocity(velocities, 3 * contact.index, beforeVelocity, contact.hit)
        }
        applyPins()
        time += dt
    }
}

/** common.js `distanceConstraints(positions, pairs)`: Float32 rest lengths of index pairs. */
internal fun distanceConstraints(
    positions: FloatArray,
    pairs: List<IntArray>,
): DistanceConstraints {
    val indices = IntArray(pairs.size * 2)
    val rest = FloatArray(pairs.size)
    for (e in pairs.indices) {
        val i = 3 * pairs[e][0]
        val j = 3 * pairs[e][1]
        indices[2 * e] = pairs[e][0]
        indices[2 * e + 1] = pairs[e][1]
        val x = positions(i) - positions(j)
        val y = positions(i + 1) - positions(j + 1)
        val z = positions(i + 2) - positions(j + 2)
        rest[e] = hypot(x, y, z).toFloat()
    }
    return DistanceConstraints(indices, rest, FloatArray(pairs.size))
}

/** common.js `solveDistances`: one XPBD pass over every distance constraint. */
internal fun solveDistances(
    p: FloatArray,
    w: FloatArray,
    constraints: DistanceConstraints,
    compliance: Double,
    dt: Double,
) {
    val alpha = compliance / (dt * dt)
    for (e in constraints.rest.indices) solveDistance(p, w, constraints, e, alpha)
}

private fun solveDistance(
    p: FloatArray,
    w: FloatArray,
    constraints: DistanceConstraints,
    e: Int,
    alpha: Double,
) {
    val ia = constraints.indices[2 * e]
    val ib = constraints.indices[2 * e + 1]
    val i = 3 * ia
    val j = 3 * ib
    val wx = w(ia)
    val wy = w(ib)
    if (wx + wy == 0.0) return
    val x = p(i) - p(j)
    val y = p(i + 1) - p(j + 1)
    val z = p(i + 2) - p(j + 2)
    val len = hypot(x, y, z)
    if (len < EPS) return
    val lambda = constraints.lambda
    val dl = (-(len - constraints.rest(e)) - alpha * lambda(e)) / (wx + wy + alpha) / len
    lambda[e] = (lambda(e) + dl * len).toFloat()
    val d = doubleArrayOf(x * dl, y * dl, z * dl)
    for (a in 0 until 3) {
        p[i + a] = (p(i + a) + wx * d[a]).toFloat()
        p[j + a] = (p(j + a) - wy * d[a]).toFloat()
    }
}
