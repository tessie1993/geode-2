package dev.geode.ui.opaline.physics

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** cloth.js `makeClothGrid` result. */
internal class ClothGrid(
    val positions: FloatArray,
    val triangles: IntArray,
)

/** cloth.js `Cloth`: XPBD stretch/shear edges, hinge-opposite bend distances, pressure drag. */
internal class Cloth(
    positions: FloatArray,
    triangles: IntArray,
    var stretchCompliance: Double = 1e-7,
    var bendCompliance: Double = 2e-4,
    aerodynamicWind: DoubleArray = doubleArrayOf(0.0, 0.0, 0.0),
    var airDensity: Double = 1.225,
    var dragCoefficient: Double = .9,
    inverseMass: FloatArray? = null,
    uniformInverseMass: Float = 1f,
    gravity: DoubleArray = doubleArrayOf(0.0, -9.81, 0.0),
    damping: Double = .8,
    radius: Double = .005,
    colliders: List<Collider> = emptyList(),
    fields: List<ForceField> = emptyList(),
    iterations: Int = 10,
) : ConstraintBody(
        positions,
        inverseMass,
        uniformInverseMass,
        gravity,
        damping,
        radius,
        colliders,
        fields,
        iterations,
    ) {
    init {
        require(triangles.size % 3 == 0) { "triangles length must be a multiple of 3" }
    }

    val triangles: IntArray = triangles.copyOf()
    val aerodynamicWind: DoubleArray = aerodynamicWind.copyOf()
    val stretch: DistanceConstraints
    val bend: DistanceConstraints

    init {
        val edges = LinkedHashMap<List<Int>, IntArray>()
        val bends = ArrayList<IntArray>()
        for (t in 0 until this.triangles.size step 3) {
            for (q in 0 until 3) checkIndex(this.triangles[t + q])
            for (j in 0 until 3) {
                val a = this.triangles[t + j]
                val b = this.triangles[t + (j + 1) % 3]
                val opposite = this.triangles[t + (j + 2) % 3]
                val key = listOf(minOf(a, b), maxOf(a, b))
                val edge = edges[key]
                if (edge != null) {
                    bends.add(intArrayOf(edge[2], opposite))
                } else {
                    edges[key] = intArrayOf(a, b, opposite)
                }
            }
        }
        stretch = distanceConstraints(this.positions, edges.values.toList())
        bend = distanceConstraints(this.positions, bends)
    }

    private fun aerodynamics(dt: Double) {
        val p = positions
        val v = velocities
        val wind = aerodynamicWind
        for (t in 0 until triangles.size step 3) {
            val a = 3 * triangles[t]
            val b = 3 * triangles[t + 1]
            val c = 3 * triangles[t + 2]
            val ux = p(b) - p(a)
            val uy = p(b + 1) - p(a + 1)
            val uz = p(b + 2) - p(a + 2)
            val vx = p(c) - p(a)
            val vy = p(c + 1) - p(a + 1)
            val vz = p(c + 2) - p(a + 2)
            val n = doubleArrayOf(uy * vz - uz * vy, uz * vx - ux * vz, ux * vy - uy * vx)
            val area2 = hypot(n[0], n[1], n[2])
            if (area2 < 1e-10) continue
            for (k in 0 until 3) n[k] /= area2
            var normalSpeed = 0.0
            for (k in 0 until 3) {
                normalSpeed += (wind[k] - (v(a + k) + v(b + k) + v(c + k)) / 3) * n[k]
            }
            val force =
                .5 * airDensity * dragCoefficient * area2 * .5 * normalSpeed * abs(normalSpeed)
            for (j in 0 until 9) {
                val i = triangles[t + j / 3]
                val k = j % 3
                v[3 * i + k] = (v(3 * i + k) + n[k] * force / 3 * inverseMass(i) * dt).toFloat()
            }
        }
    }

    override fun step(dt: Double) {
        aerodynamics(dt)
        begin(dt)
        stretch.lambda.fill(0f)
        bend.lambda.fill(0f)
        repeat(iterations) {
            solveDistances(positions, inverseMass, stretch, stretchCompliance, dt)
            solveDistances(positions, inverseMass, bend, bendCompliance, dt)
            solveGrabs(dt)
            collide()
            applyPins()
        }
        finish(dt)
    }
}

/** cloth.js `Rod`: consecutive stretch and second-neighbour bend distances, optionally closed. */
internal class Rod(
    positions: FloatArray,
    var stretchCompliance: Double = 1e-8,
    var bendCompliance: Double = 1e-5,
    val closed: Boolean = false,
    inverseMass: FloatArray? = null,
    uniformInverseMass: Float = 1f,
    gravity: DoubleArray = doubleArrayOf(0.0, -9.81, 0.0),
    damping: Double = .8,
    radius: Double = .005,
    colliders: List<Collider> = emptyList(),
    fields: List<ForceField> = emptyList(),
    iterations: Int = 10,
) : ConstraintBody(
        positions,
        inverseMass,
        uniformInverseMass,
        gravity,
        damping,
        radius,
        colliders,
        fields,
        iterations,
    ) {
    val stretch: DistanceConstraints
    val bend: DistanceConstraints

    init {
        val edges = (0 until count - 1).map { intArrayOf(it, it + 1) }.toMutableList()
        val bends = (0 until count - 2).map { intArrayOf(it, it + 2) }.toMutableList()
        if (closed && count > 2) {
            edges.add(intArrayOf(count - 1, 0))
            bends.add(intArrayOf(count - 2, 0))
            bends.add(intArrayOf(count - 1, 1))
        }
        stretch = distanceConstraints(this.positions, edges)
        bend = distanceConstraints(this.positions, bends)
    }

    override fun step(dt: Double) {
        begin(dt)
        stretch.lambda.fill(0f)
        bend.lambda.fill(0f)
        repeat(iterations) {
            solveDistances(positions, inverseMass, stretch, stretchCompliance, dt)
            solveDistances(positions, inverseMass, bend, bendCompliance, dt)
            solveGrabs(dt)
            collide()
            applyPins()
        }
        finish(dt)
    }
}

/** cloth.js `makeClothGrid`: a triangulated sheet in the "xy" or "xz" plane. */
internal fun makeClothGrid(
    nx: Int = 16,
    ny: Int = 16,
    size: DoubleArray = doubleArrayOf(1.0, 1.0),
    origin: DoubleArray = doubleArrayOf(0.0, 0.0, 0.0),
    plane: String = "xy",
): ClothGrid {
    val p = FloatArray((nx + 1) * (ny + 1) * 3)
    for (v in 0 until (nx + 1) * (ny + 1)) {
        val a = ((v % (nx + 1)).toDouble() / nx - .5) * size[0]
        val b = ((v / (nx + 1)).toDouble() / ny - .5) * size[1]
        p[3 * v] = (origin[0] + a).toFloat()
        p[3 * v + 1] = (origin[1] + (if (plane == "xy") b else 0.0)).toFloat()
        p[3 * v + 2] = (origin[2] + (if (plane == "xz") b else 0.0)).toFloat()
    }
    val triangles = IntArray(nx * ny * 6)
    for (cell in 0 until nx * ny) {
        val a = cell % nx + cell / nx * (nx + 1)
        val c = a + nx + 1
        intArrayOf(a, a + 1, c + 1, a, c + 1, c).copyInto(triangles, cell * 6)
    }
    return ClothGrid(p, triangles)
}

/** cloth.js `makeRodPoints`: [count] points along [direction], optionally curled. */
internal fun makeRodPoints(
    count: Int = 24,
    length: Double = 1.0,
    origin: DoubleArray = doubleArrayOf(0.0, 0.0, 0.0),
    direction: DoubleArray = doubleArrayOf(0.0, 1.0, 0.0),
    curl: Double = 0.0,
): FloatArray {
    val norm = hypot(direction[0], direction[1], direction[2])
    val p = FloatArray(count * 3)
    for (i in 0 until count) {
        val t = i.toDouble() / (count - 1)
        val x = origin[0] + direction[0] / norm * t * length + sin(t * PI * 2) * curl
        val z = origin[2] + direction[2] / norm * t * length + (1 - cos(t * PI * 2)) * curl
        p[3 * i] = x.toFloat()
        p[3 * i + 1] = (origin[1] + direction[1] / norm * t * length).toFloat()
        p[3 * i + 2] = z.toFloat()
    }
    return p
}
