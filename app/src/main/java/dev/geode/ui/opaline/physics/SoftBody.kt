package dev.geode.ui.opaline.physics

import kotlin.math.abs
import kotlin.math.sqrt

/** soft-body.js `makeTetGrid` result. */
internal class TetGrid(
    val positions: FloatArray,
    val tetrahedra: IntArray,
    val surfaceIndices: IntArray,
)

/** soft-body.js `signedTetVolume(p, a, b, c, d)`. */
internal fun signedTetVolume(
    p: FloatArray,
    a: Int,
    b: Int,
    c: Int,
    d: Int,
): Double = tetVolume({ p(it) }, a, b, c, d)

private inline fun tetVolume(
    p: (Int) -> Double,
    a: Int,
    b: Int,
    c: Int,
    d: Int,
): Double {
    val bx = p(3 * b) - p(3 * a)
    val by = p(3 * b + 1) - p(3 * a + 1)
    val bz = p(3 * b + 2) - p(3 * a + 2)
    val cx = p(3 * c) - p(3 * a)
    val cy = p(3 * c + 1) - p(3 * a + 1)
    val cz = p(3 * c + 2) - p(3 * a + 2)
    val dx = p(3 * d) - p(3 * a)
    val dy = p(3 * d + 1) - p(3 * a + 1)
    val dz = p(3 * d + 2) - p(3 * a + 2)
    return (bx * (cy * dz - cz * dy) + by * (cz * dx - cx * dz) + bz * (cx * dy - cy * dx)) / 6
}

/** soft-body.js `tetrahedralSurface`: faces not shared by two tetrahedra, wound outward. */
internal fun tetrahedralSurface(
    tetrahedra: IntArray,
    positions: FloatArray,
): IntArray {
    val faces = LinkedHashMap<List<Int>, IntArray>()
    for (t in 0 until tetrahedra.size step 4) {
        for (omit in 0 until 4) {
            val f = (0 until 4).filter { it != omit }.map { tetrahedra[t + it] }.toIntArray()
            val key = f.sorted()
            if (faces.remove(key) != null) continue
            if (signedTetVolume(positions, f[0], f[1], f[2], tetrahedra[t + omit]) > 0) {
                f[1] = f[2].also { f[2] = f[1] }
            }
            faces[key] = f
        }
    }
    return faces.values.flatMap { it.asList() }.toIntArray()
}

/**
 * soft-body.js `makeTetGrid`: (nx+1)(ny+1)(nz+1) lattice points, vertex index
 * `x + (nx+1)(y + (ny+1)z)`, six conforming tetrahedra per cell; "ellipsoid" spherifies the cube.
 */
internal fun makeTetGrid(
    nx: Int = 5,
    ny: Int = 3,
    nz: Int = 5,
    size: DoubleArray = doubleArrayOf(1.0, .45, 1.0),
    origin: DoubleArray = doubleArrayOf(0.0, 0.0, 0.0),
    shape: String = "box",
): TetGrid {
    require(nx >= 1 && ny >= 1 && nz >= 1) { "nx, ny and nz are positive cell counts" }
    val p = DoubleArray((nx + 1) * (ny + 1) * (nz + 1) * 3)
    for (v in 0 until p.size / 3) {
        var a = 2.0 * (v % (nx + 1)) / nx - 1
        var b = 2.0 * (v / (nx + 1) % (ny + 1)) / ny - 1
        var c = 2.0 * (v / ((nx + 1) * (ny + 1))) / nz - 1
        if (shape == "ellipsoid") {
            val x = a * sqrt(1 - b * b / 2 - c * c / 2 + b * b * c * c / 3)
            val y = b * sqrt(1 - c * c / 2 - a * a / 2 + c * c * a * a / 3)
            c *= sqrt(1 - a * a / 2 - b * b / 2 + a * a * b * b / 3)
            a = x
            b = y
        }
        p[3 * v] = origin[0] + a * size[0] / 2
        p[3 * v + 1] = origin[1] + b * size[1] / 2
        p[3 * v + 2] = origin[2] + c * size[2] / 2
    }
    val ring = intArrayOf(1, 3, 2, 6, 4, 5)
    val tetrahedra = IntArray(nx * ny * nz * 24)
    for (cell in 0 until nx * ny * nz) {
        val x = cell % nx
        val y = cell / nx % ny
        val z = cell / (nx * ny)
        val v = IntArray(8) { x + it % 2 + (nx + 1) * (y + it / 2 % 2 + (ny + 1) * (z + it / 4)) }
        for (a in 0 until 6) {
            val q = intArrayOf(v[0], v[ring[a]], v[ring[(a + 1) % 6]], v[7])
            if (tetVolume({ p[it] }, q[0], q[1], q[2], q[3]) < 0) q[1] = q[2].also { q[2] = q[1] }
            q.copyInto(tetrahedra, (cell * 6 + a) * 4)
        }
    }
    val positions = FloatArray(p.size) { p[it].toFloat() }
    return TetGrid(positions, tetrahedra, tetrahedralSurface(tetrahedra, positions))
}

/** soft-body.js `SoftBody`: XPBD edge-length and tetrahedral-volume constraints. */
internal class SoftBody(
    positions: FloatArray,
    tetrahedra: IntArray,
    var edgeCompliance: Double = 2e-5,
    var volumeCompliance: Double = 1e-9,
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
        require(tetrahedra.size % 4 == 0) { "tetrahedra length must be a multiple of 4" }
    }

    val tetrahedra: IntArray = tetrahedra.copyOf()
    val restPositions: FloatArray = this.positions.copyOf()
    val restVolumes = FloatArray(tetrahedra.size / 4)
    val volumeLambda = FloatArray(restVolumes.size)
    private val gradient = DoubleArray(12)
    val edges: DistanceConstraints
    val surfaceIndices: IntArray

    init {
        val edgeMap = LinkedHashMap<List<Int>, IntArray>()
        for (t in restVolumes.indices) {
            val v = IntArray(4) { this.tetrahedra[4 * t + it] }
            for (i in v) checkIndex(i)
            val volume = signedTetVolume(this.positions, v[0], v[1], v[2], v[3])
            require(abs(volume) >= 1e-12) { "degenerate tetrahedron $t" }
            restVolumes[t] = volume.toFloat()
            for (a in 0 until 4) {
                for (b in a + 1 until 4) {
                    val lo = minOf(v[a], v[b])
                    val hi = maxOf(v[a], v[b])
                    edgeMap[listOf(lo, hi)] = intArrayOf(lo, hi)
                }
            }
        }
        edges = distanceConstraints(this.positions, edgeMap.values.toList())
        surfaceIndices = tetrahedralSurface(this.tetrahedra, this.positions)
    }

    val volume: Double
        get() = restVolumes.indices.sumOf { abs(volumeOf(it)) }

    val restVolume: Double
        get() = restVolumes.sumOf { abs(it.toDouble()) }

    val invertedTetrahedra: Int
        get() = restVolumes.indices.count { volumeOf(it) * restVolumes[it] <= 0 }

    private fun volumeOf(t: Int): Double {
        val b = 4 * t
        return signedTetVolume(
            positions,
            tetrahedra[b],
            tetrahedra[b + 1],
            tetrahedra[b + 2],
            tetrahedra[b + 3],
        )
    }

    private fun solveVolumes(dt: Double) {
        val p = positions
        val w = inverseMass
        val g = gradient
        val alpha = volumeCompliance / (dt * dt)
        for (t in restVolumes.indices) {
            val a = 3 * tetrahedra[4 * t]
            val b = 3 * tetrahedra[4 * t + 1]
            val c = 3 * tetrahedra[4 * t + 2]
            val d = 3 * tetrahedra[4 * t + 3]
            val bx = p(b) - p(a)
            val by = p(b + 1) - p(a + 1)
            val bz = p(b + 2) - p(a + 2)
            val cx = p(c) - p(a)
            val cy = p(c + 1) - p(a + 1)
            val cz = p(c + 2) - p(a + 2)
            val dx = p(d) - p(a)
            val dy = p(d + 1) - p(a + 1)
            val dz = p(d + 2) - p(a + 2)
            g[3] = (cy * dz - cz * dy) / 6
            g[4] = (cz * dx - cx * dz) / 6
            g[5] = (cx * dy - cy * dx) / 6
            g[6] = (dy * bz - dz * by) / 6
            g[7] = (dz * bx - dx * bz) / 6
            g[8] = (dx * by - dy * bx) / 6
            g[9] = (by * cz - bz * cy) / 6
            g[10] = (bz * cx - bx * cz) / 6
            g[11] = (bx * cy - by * cx) / 6
            for (k in 0 until 3) g[k] = -g[3 + k] - g[6 + k] - g[9 + k]
            var denom = alpha
            for (j in 0 until 12) denom += w(tetrahedra[4 * t + j / 3]) * (g[j] * g[j])
            if (denom < EPS) continue
            val volume = bx * g[3] + by * g[4] + bz * g[5]
            val dl = (-(volume - restVolumes(t)) - alpha * volumeLambda(t)) / denom
            volumeLambda[t] = (volumeLambda(t) + dl).toFloat()
            for (j in 0 until 12) {
                val i = tetrahedra[4 * t + j / 3]
                p[i * 3 + j % 3] = (p(i * 3 + j % 3) + w(i) * g[j] * dl).toFloat()
            }
        }
    }

    override fun step(dt: Double) {
        begin(dt)
        edges.lambda.fill(0f)
        volumeLambda.fill(0f)
        repeat(iterations) {
            solveDistances(positions, inverseMass, edges, edgeCompliance, dt)
            solveVolumes(dt)
            solveGrabs(dt)
            collide()
            applyPins()
        }
        finish(dt)
    }

    /** Adds [impulse] as total momentum shared by linear radial falloff around [point]. */
    fun addImpulse(
        point: DoubleArray,
        radius: Double,
        impulse: DoubleArray,
    ) = addRadialImpulse(positions, velocities, inverseMass, count, point, radius, impulse)
}
