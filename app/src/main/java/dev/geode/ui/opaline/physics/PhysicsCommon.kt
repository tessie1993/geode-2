package dev.geode.ui.opaline.physics

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** src/physics/common.js `EPS`. */
internal const val EPS = 1e-10

private const val CELL_MASK = 0x1FFFFFL

/** What world.js `PhysicsWorld.add(system)` steps. */
internal interface PhysicsSystem {
    fun step(dt: Double)
}

/** A world.js `couplings` entry, stepped after the systems and removed with either side. */
internal interface PhysicsCoupling {
    val a: PhysicsSystem
    val b: PhysicsSystem

    fun step(dt: Double)
}

/** What world.js `ParticleCoupling` reads from each side. */
internal interface ParticleBody : PhysicsSystem {
    val positions: FloatArray
    val velocities: FloatArray
    val count: Int
    val radii: FloatArray?
        get() = null

    /** `particleRadius ?? radius`; null falls back to .015. */
    val couplingRadius: Double?

    /** `inverseMass?.[i] ?? 1 / particleMass`. */
    fun couplingInverseMass(index: Int): Double
}

/** particles.js field contract: a world-space acceleration written into [out]. */
internal interface ForceField {
    var metadata: FieldMetadata?

    fun sample(
        x: Double,
        y: Double,
        z: Double,
        t: Double,
        out: DoubleArray,
    ): DoubleArray

    /** The constructor's `Object.assign` of preset options over its defaults (presets.js). */
    fun assign(options: Map<String, Any?>)
}

/** presets.js `field.metadata`. */
internal class FieldMetadata(
    val id: String,
    val name: String,
    val limitation: String?,
)

/** A Float32 element as the double the JavaScript computes with; stores round back to float. */
internal operator fun FloatArray.invoke(index: Int): Double = this[index].toDouble()

/** common.js `clamp`. */
internal fun clamp(
    value: Double,
    low: Double,
    high: Double,
): Double = max(low, min(high, value))

/** `Math.hypot(x, y, z)`. */
internal fun hypot(
    x: Double,
    y: Double,
    z: Double,
): Double = sqrt(x * x + y * y + z * z)

/** common.js `finiteArray`. */
internal fun requireFinite(
    values: FloatArray,
    multiple: Int,
    name: String,
) {
    require(values.size % multiple == 0) { "$name length must be a multiple of $multiple" }
    require(values.all { it.isFinite() }) { "$name is not finite" }
}

/** common.js `finiteArray`. */
internal fun requireFinite(
    values: DoubleArray,
    multiple: Int,
    name: String,
) {
    require(values.size % multiple == 0) { "$name length must be a multiple of $multiple" }
    require(values.all { it.isFinite() }) { "$name is not finite" }
}

/** common.js `validDt`. */
internal fun requireValidDt(dt: Double) {
    require(dt > 0 && dt.isFinite()) { "dt must be finite and positive" }
}

/**
 * The explicit prediction every solver step opens with (common.js `_begin`, fluid.js and
 * particles.js `step`): gravity, exponential [decay], field accelerations, then drift.
 */
internal fun integrate(
    positions: FloatArray,
    velocities: FloatArray,
    k: Int,
    gravity: DoubleArray,
    fields: List<ForceField>,
    time: Double,
    dt: Double,
    decay: Double,
    force: DoubleArray,
) {
    for (a in 0 until 3) {
        velocities[k + a] = ((velocities(k + a) + gravity[a] * dt) * decay).toFloat()
    }
    for (field in fields) {
        force.fill(0.0)
        field.sample(positions(k), positions(k + 1), positions(k + 2), time, force)
        for (a in 0 until 3) velocities[k + a] = (velocities(k + a) + force[a] * dt).toFloat()
    }
    for (a in 0 until 3) positions[k + a] = (positions(k + a) + velocities(k + a) * dt).toFloat()
}

/**
 * The Float32 linear-falloff weights `max(0, 1 - d / radius)` every `addImpulse` of
 * soft-body.js, fluid.js and particles.js distributes its momentum with.
 */
internal fun impulseWeights(
    positions: FloatArray,
    count: Int,
    point: DoubleArray,
    radius: Double,
    impulse: DoubleArray,
): FloatArray {
    require(radius > 0) { "impulse radius must be positive" }
    requireFinite(impulse, 3, "impulse")
    return FloatArray(count) {
        val x = positions(3 * it) - point[0]
        val y = positions(3 * it + 1) - point[1]
        val z = positions(3 * it + 2) - point[2]
        max(0.0, 1 - hypot(x, y, z) / radius).toFloat()
    }
}

/** soft-body.js / particles.js `addImpulse`: total momentum shared by linear radial falloff. */
internal fun addRadialImpulse(
    positions: FloatArray,
    velocities: FloatArray,
    inverseMass: FloatArray,
    count: Int,
    point: DoubleArray,
    radius: Double,
    impulse: DoubleArray,
) {
    val weights = impulseWeights(positions, count, point, radius, impulse)
    val total = weights.sumOf { it.toDouble() }
    if (!(total > 0)) return
    for (k in 0 until count * 3) {
        val dv = impulse[k % 3] * weights(k / 3) / total * inverseMass(k / 3)
        velocities[k] = (velocities(k) + dv).toFloat()
    }
}

/** A JSON number option, or null when absent. */
internal fun Map<*, *>.number(key: String): Double? = (this[key] as? Number)?.toDouble()

/** A JSON number-array option, or null when absent. */
internal fun Map<*, *>.vector(key: String): DoubleArray? =
    (this[key] as? List<*>)?.map { (it as Number).toDouble() }?.toDoubleArray()

/** common.js `seededRandom(seed)` (mulberry32 in 32-bit integer arithmetic). */
internal class SeededRandom(
    seed: Int,
) {
    private var state = seed

    fun next(): Double {
        state += 0x6D2B79F5
        var t = state
        t = (t xor (t ushr 15)) * (t or 1)
        t = t xor (t + (t xor (t ushr 7)) * (t or 61))
        return ((t xor (t ushr 14)).toLong() and 0xFFFFFFFFL) / 4294967296.0
    }
}

/** A reusable growable int buffer (the JavaScript neighbour arrays). */
internal class IntList {
    var size = 0
        private set
    private var items = IntArray(16)

    operator fun get(index: Int) = items[index]

    fun add(value: Int) {
        if (size == items.size) items = items.copyOf(size * 2)
        items[size++] = value
    }

    fun clear() {
        size = 0
    }
}

/** common.js `SpatialHash`: floored cells, each list in insertion order. */
internal class SpatialHash(
    private val cellSize: Double,
) {
    private val cells = HashMap<Long, IntList>()
    private val spare = ArrayList<IntList>()

    init {
        require(cellSize > 0) { "positive cellSize required" }
    }

    fun build(
        positions: FloatArray,
        count: Int,
    ) {
        for (cell in cells.values) {
            cell.clear()
            spare.add(cell)
        }
        cells.clear()
        for (i in 0 until count) {
            val k = 3 * i
            val key = key(cell(positions(k)), cell(positions(k + 1)), cell(positions(k + 2)))
            cells.getOrPut(key) { spare.removeLastOrNull() ?: IntList() }.add(i)
        }
    }

    /** The 27 cells around the point, visited in the JavaScript order. */
    fun query(
        x: Double,
        y: Double,
        z: Double,
        out: IntList,
    ) {
        out.clear()
        val ix = cell(x)
        val iy = cell(y)
        val iz = cell(z)
        for (dx in -1L..1L) {
            for (dy in -1L..1L) {
                for (dz in -1L..1L) append(key(ix + dx, iy + dy, iz + dz), out)
            }
        }
    }

    private fun append(
        key: Long,
        out: IntList,
    ) {
        val cell = cells[key] ?: return
        for (j in 0 until cell.size) out.add(cell[j])
    }

    private fun cell(value: Double): Long = floor(value / cellSize).toLong()

    private fun key(
        x: Long,
        y: Long,
        z: Long,
    ): Long = ((x and CELL_MASK) shl 42) or ((y and CELL_MASK) shl 21) or (z and CELL_MASK)
}

/** A common.js collider `project` result. */
internal class CollisionHit(
    val normal: DoubleArray,
    val depth: Double,
    val collider: Collider,
)

/** A static common.js boundary with its contact restitution and friction. */
internal interface Collider {
    val restitution: Double
    val friction: Double

    /** Moves particle `p[k..k+2]` of radius [r] back into the allowed region; null when clear. */
    fun project(
        p: FloatArray,
        k: Int,
        r: Double,
    ): CollisionHit?
}

/** common.js `PlaneCollider`: the allowed half-space `dot(normal, p) >= offset`. */
internal class PlaneCollider(
    normal: DoubleArray = doubleArrayOf(0.0, 1.0, 0.0),
    offset: Double = 0.0,
    override val restitution: Double = .08,
    override val friction: Double = .3,
) : Collider {
    private val length = hypot(normal[0], normal[1], normal[2])
    val normal = DoubleArray(3) { normal[it] / length }
    val offset = offset / length

    init {
        require(length > EPS) { "plane normal must be nonzero" }
    }

    override fun project(
        p: FloatArray,
        k: Int,
        r: Double,
    ): CollisionHit? {
        val n = normal
        val d = p(k) * n[0] + p(k + 1) * n[1] + p(k + 2) * n[2] - offset - r
        if (d >= 0) return null
        for (a in 0 until 3) p[k + a] = (p(k + a) - d * n[a]).toFloat()
        return CollisionHit(n, -d, this)
    }
}

/** common.js `SphereCollider`: excludes a solid sphere, or contains particles [inside] it. */
internal class SphereCollider(
    center: DoubleArray = doubleArrayOf(0.0, 0.0, 0.0),
    val radius: Double = 1.0,
    val inside: Boolean = false,
    override val restitution: Double = .08,
    override val friction: Double = .25,
) : Collider {
    val center = center.copyOf()

    init {
        require(radius > 0) { "radius must be positive" }
    }

    override fun project(
        p: FloatArray,
        k: Int,
        r: Double,
    ): CollisionHit? {
        var x = p(k) - center[0]
        var y = p(k + 1) - center[1]
        var z = p(k + 2) - center[2]
        var d = hypot(x, y, z)
        require(!(inside && r >= radius)) {
            "particle radius must be smaller than containment sphere"
        }
        val limit = if (inside) radius - r else radius + r
        if (if (inside) d <= limit else d >= limit) return null
        if (d < EPS) {
            x = 1.0
            y = 0.0
            z = 0.0
            d = 1.0
        }
        val nx = x / d
        val ny = y / d
        val nz = z / d
        p[k] = (center[0] + nx * limit).toFloat()
        p[k + 1] = (center[1] + ny * limit).toFloat()
        p[k + 2] = (center[2] + nz * limit).toFloat()
        val sign = if (inside) -1.0 else 1.0
        return CollisionHit(doubleArrayOf(nx * sign, ny * sign, nz * sign), abs(d - limit), this)
    }
}

/** common.js `BoxCollider`: contains particles [inside] the box, or excludes it. */
internal class BoxCollider(
    min: DoubleArray = doubleArrayOf(-1.0, 0.0, -1.0),
    max: DoubleArray = doubleArrayOf(1.0, 2.0, 1.0),
    val inside: Boolean = true,
    override val restitution: Double = .03,
    override val friction: Double = .2,
) : Collider {
    val min = min.copyOf()
    val max = max.copyOf()

    init {
        for (a in 0 until 3) require(max[a] > min[a]) { "box max must exceed min" }
    }

    override fun project(
        p: FloatArray,
        k: Int,
        r: Double,
    ): CollisionHit? = if (inside) projectInside(p, k, r) else projectOutside(p, k, r)

    private fun projectInside(
        p: FloatArray,
        k: Int,
        r: Double,
    ): CollisionHit? {
        val n = DoubleArray(3)
        var depth = 0.0
        for (a in 0 until 3) {
            val lo = min[a] + r
            val hi = max[a] - r
            require(!(lo > hi)) { "particle diameter exceeds box" }
            val v = p(k + a)
            if (v < lo) {
                n[a] = lo - v
                depth += n[a] * n[a]
                p[k + a] = lo.toFloat()
            } else if (v > hi) {
                n[a] = hi - v
                depth += n[a] * n[a]
                p[k + a] = hi.toFloat()
            }
        }
        if (depth == 0.0 || depth.isNaN()) return null
        val length = sqrt(depth)
        return CollisionHit(DoubleArray(3) { n[it] / length }, length, this)
    }

    private fun projectOutside(
        p: FloatArray,
        k: Int,
        r: Double,
    ): CollisionHit? {
        val closest = DoubleArray(3) { clamp(p(k + it), min[it], max[it]) }
        var d2 = 0.0
        for (a in 0 until 3) d2 += (p(k + a) - closest[a]) * (p(k + a) - closest[a])
        if (d2 > EPS) {
            if (d2 >= r * r) return null
            val d = sqrt(d2)
            val n = DoubleArray(3) { (p(k + it) - closest[it]) / d }
            for (a in 0 until 3) p[k + a] = (closest[a] + n[a] * r).toFloat()
            return CollisionHit(n, r - d, this)
        }
        var axis = 0
        var sign = -1.0
        var best = Double.POSITIVE_INFINITY
        for (a in 0 until 3) {
            val dl = p(k + a) - min[a]
            val dh = max[a] - p(k + a)
            if (dl < best) {
                best = dl
                axis = a
                sign = -1.0
            }
            if (dh < best) {
                best = dh
                axis = a
                sign = 1.0
            }
        }
        val n = DoubleArray(3)
        n[axis] = sign
        p[k + axis] = (if (sign < 0) min[axis] - r else max[axis] + r).toFloat()
        return CollisionHit(n, best + r, this)
    }
}

/** common.js `resolveContactVelocity`: restitution above .2 m/s and Coulomb-limited friction. */
internal fun resolveContactVelocity(
    v: FloatArray,
    k: Int,
    previous: FloatArray,
    contact: CollisionHit,
) {
    val n = contact.normal
    val c = contact.collider
    var vn = 0.0
    var old = 0.0
    for (a in 0 until 3) {
        vn += v(k + a) * n[a]
        old += previous(k + a) * n[a]
    }
    val target = if (old < -.2) -c.restitution * old else 0.0
    val impulse = max(0.0, target - vn)
    for (a in 0 until 3) v[k + a] = (v(k + a) + impulse * n[a]).toFloat()
    var tang = 0.0
    val newVn = vn + impulse
    for (a in 0 until 3) tang += (v(k + a) - newVn * n[a]) * (v(k + a) - newVn * n[a])
    tang = sqrt(tang)
    val friction = min(1.0, c.friction * max(impulse, -old) / max(tang, EPS))
    for (a in 0 until 3) v[k + a] = (v(k + a) - (v(k + a) - newVn * n[a]) * friction).toFloat()
}
