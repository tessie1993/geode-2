package dev.geode.ui.opaline.physics

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cbrt
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/** film.js `makeBubbleMesh` result (`surfaceIndices` is [triangles]). */
internal class BubbleMesh(
    val positions: FloatArray,
    val triangles: IntArray,
)

/** film.js `makeBubbleMesh`: a subdivided octahedron projected onto a sphere, seam-free. */
internal fun makeBubbleMesh(
    subdivisions: Int = 3,
    radius: Double = .65,
    center: DoubleArray = doubleArrayOf(0.0, 0.0, 0.0),
): BubbleMesh {
    require(subdivisions in 0..6 && radius > 0) { "invalid bubble resolution/radius" }
    requireFinite(center, 3, "center")
    val points =
        mutableListOf(
            doubleArrayOf(1.0, 0.0, 0.0),
            doubleArrayOf(-1.0, 0.0, 0.0),
            doubleArrayOf(0.0, 1.0, 0.0),
            doubleArrayOf(0.0, -1.0, 0.0),
            doubleArrayOf(0.0, 0.0, 1.0),
            doubleArrayOf(0.0, 0.0, -1.0),
        )
    var faces =
        listOf(2, 4, 0, 2, 1, 4, 2, 5, 1, 2, 0, 5, 3, 0, 4, 3, 4, 1, 3, 1, 5, 3, 5, 0)
            .chunked(3)
    repeat(subdivisions) {
        val cache = HashMap<List<Int>, Int>()
        val midpoint = { a: Int, b: Int ->
            cache.getOrPut(listOf(min(a, b), max(a, b))) {
                val p = DoubleArray(3) { (points[a][it] + points[b][it]) / 2 }
                val length = hypot(p[0], p[1], p[2])
                points.add(DoubleArray(3) { p[it] / length })
                points.size - 1
            }
        }
        faces =
            faces.flatMap { (a, b, c) ->
                val ab = midpoint(a, b)
                val bc = midpoint(b, c)
                val ca = midpoint(c, a)
                listOf(listOf(a, ab, ca), listOf(ab, b, bc), listOf(ca, bc, c), listOf(ab, bc, ca))
            }
    }
    val positions =
        FloatArray(points.size * 3) { (center[it % 3] + radius * points[it / 3][it % 3]).toFloat() }
    return BubbleMesh(positions, faces.flatten().toIntArray())
}

/**
 * film.js `BubbleFilm`: a closed triangular film with XPBD edge, area-tension and enclosed-gas
 * volume constraints, and conserved surface liquid drained by gravity. [thickness] is the live
 * optical thickness per vertex in metres (× 1e9 = nanometres) for the film interference shader.
 */
internal class BubbleFilm(
    positions: FloatArray,
    triangles: IntArray,
    initialThickness: Double = 400e-9,
    val minThickness: Double = 80e-9,
    val maxThickness: Double = 1500e-9,
    val liquidDensity: Double = 1000.0,
    val dynamicViscosity: Double = .001,
    var drainageGravity: Double = 9.81,
    var drainageTimeScale: Double = 1.0,
    var gasCompliance: Double = 1e-8,
    var edgeCompliance: Double = 8e-4,
    var surfaceTension: Double = .025,
    inverseMass: FloatArray? = null,
    uniformInverseMass: Float = 1f,
    gravity: DoubleArray = doubleArrayOf(0.0, 0.0, 0.0),
    damping: Double = 1.2,
    radius: Double = .001,
    colliders: List<Collider> = emptyList(),
    fields: List<ForceField> = emptyList(),
    iterations: Int = 12,
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
        require(initialThickness in minThickness..maxThickness && minThickness > 0) {
            "invalid film material parameters"
        }
        require(liquidDensity > 0 && dynamicViscosity > 0) { "invalid film material parameters" }
    }

    val triangles: IntArray = triangles.copyOf()
    val area = DoubleArray(count)
    val thickness = FloatArray(count)
    val filmMass = DoubleArray(count)
    private val volumeGradient = DoubleArray(count * 3)
    private val areaGradient = DoubleArray(9)
    private val areaLambda = DoubleArray(this.triangles.size / 3)
    private var volumeLambda = 0.0
    var boundsRelaxed = false
        private set
    var drainageFlux = 0.0
        private set
    val edges: DistanceConstraints
    val restVolume: Double
    val initialFilmMass: Double

    init {
        // [a, b, faces, orientation] per undirected edge, in first-seen order.
        val edgeMap = LinkedHashMap<List<Int>, IntArray>()
        for (t in 0 until this.triangles.size step 3) {
            for (q in 0 until 3) checkIndex(this.triangles[t + q])
            for (q in 0 until 3) {
                val a = this.triangles[t + q]
                val b = this.triangles[t + (q + 1) % 3]
                val key = listOf(min(a, b), max(a, b))
                val entry = edgeMap.getOrPut(key) { intArrayOf(a, b, 0, 0) }
                entry[2]++
                entry[3] += if (a < b) 1 else -1
            }
        }
        require(edgeMap.values.all { it[2] == 2 && it[3] == 0 }) {
            "bubble requires a closed consistently wound two-manifold triangle mesh"
        }
        edges = distanceConstraints(this.positions, edgeMap.values.toList())
        measure()
        restVolume = volume
        require(restVolume > 1e-10) {
            "bubble triangles must be outward wound and enclose positive volume"
        }
        for (i in 0 until count) filmMass[i] = area[i] * liquidDensity * initialThickness
        initialFilmMass = totalFilmMass
        updateThickness()
    }

    val totalFilmMass: Double
        get() = filmMass.sum()

    val totalArea: Double
        get() = area.sum()

    val equivalentRadius: Double
        get() = cbrt(3 * volume / (4 * PI))

    val center: DoubleArray
        get() {
            val c = DoubleArray(3)
            for (k in 0 until count * 3) c[k % 3] += positions(k) / count
            return c
        }

    /** Signed enclosed volume, measured about the centroid. */
    val volume: Double
        get() {
            val p = positions
            val c = center
            var result = 0.0
            for (t in 0 until triangles.size step 3) {
                val ia = 3 * triangles[t]
                val ib = 3 * triangles[t + 1]
                val ic = 3 * triangles[t + 2]
                val ax = p(ia) - c[0]
                val ay = p(ia + 1) - c[1]
                val az = p(ia + 2) - c[2]
                val bx = p(ib) - c[0]
                val by = p(ib + 1) - c[1]
                val bz = p(ib + 2) - c[2]
                val cx = p(ic) - c[0]
                val cy = p(ic + 1) - c[1]
                val cz = p(ic + 2) - c[2]
                val xy = ax * (by * cz - bz * cy) + ay * (bz * cx - bx * cz)
                result += (xy + az * (bx * cy - by * cx)) / 6
            }
            return result
        }

    private fun measure() {
        val p = positions
        area.fill(0.0)
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
            val share = hypot(uy * vz - uz * vy, uz * vx - ux * vz, ux * vy - uy * vx) / 6
            for (q in 0 until 3) area[triangles[t + q]] += share
        }
    }

    /** The enclosed-gas constraint (also run by [BubblePairCoupling]). */
    fun solveVolume(dt: Double) {
        val p = positions
        val g = volumeGradient
        val c = center
        g.fill(0.0)
        for (t in 0 until triangles.size step 3) {
            val a = 3 * triangles[t]
            val b = 3 * triangles[t + 1]
            val d = 3 * triangles[t + 2]
            val ax = p(a) - c[0]
            val ay = p(a + 1) - c[1]
            val az = p(a + 2) - c[2]
            val bx = p(b) - c[0]
            val by = p(b + 1) - c[1]
            val bz = p(b + 2) - c[2]
            val cx = p(d) - c[0]
            val cy = p(d + 1) - c[1]
            val cz = p(d + 2) - c[2]
            g[a] += (by * cz - bz * cy) / 6
            g[a + 1] += (bz * cx - bx * cz) / 6
            g[a + 2] += (bx * cy - by * cx) / 6
            g[b] += (cy * az - cz * ay) / 6
            g[b + 1] += (cz * ax - cx * az) / 6
            g[b + 2] += (cx * ay - cy * ax) / 6
            g[d] += (ay * bz - az * by) / 6
            g[d + 1] += (az * bx - ax * bz) / 6
            g[d + 2] += (ax * by - ay * bx) / 6
        }
        val alpha = gasCompliance / (dt * dt)
        var denominator = alpha
        for (k in 0 until count * 3) denominator += inverseMass(k / 3) * (g[k] * g[k])
        if (denominator < EPS) return
        val dl = (-(volume - restVolume) - alpha * volumeLambda) / denominator
        volumeLambda += dl
        for (k in 0 until count * 3) p[k] = (p(k) + inverseMass(k / 3) * g[k] * dl).toFloat()
    }

    private fun solveTension(dt: Double) {
        if (!(surfaceTension > 0)) return
        val p = positions
        val g = areaGradient
        val alpha = 1 / (surfaceTension * dt * dt)
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
            var nx = uy * vz - uz * vy
            var ny = uz * vx - ux * vz
            var nz = ux * vy - uy * vx
            val area2 = hypot(nx, ny, nz)
            if (area2 < EPS) continue
            nx /= area2
            ny /= area2
            nz /= area2
            val bc = doubleArrayOf(p(b) - p(c), p(b + 1) - p(c + 1), p(b + 2) - p(c + 2))
            val sides = doubleArrayOf(bc[0], bc[1], bc[2], vx, vy, vz, -ux, -uy, -uz)
            val constraint = sqrt(area2)
            var den = alpha
            for (j in 0 until 3) {
                val x = sides[3 * j]
                val y = sides[3 * j + 1]
                val z = sides[3 * j + 2]
                g[3 * j] = (y * nz - z * ny) * .5 / constraint
                g[3 * j + 1] = (z * nx - x * nz) * .5 / constraint
                g[3 * j + 2] = (x * ny - y * nx) * .5 / constraint
            }
            for (k in 0 until 9) den += inverseMass(triangles[t + k / 3]) * (g[k] * g[k])
            val index = t / 3
            val dl = (-constraint - alpha * areaLambda[index]) / den
            areaLambda[index] += dl
            for (k in 0 until 9) {
                val i = 3 * triangles[t + k / 3] + k % 3
                p[i] = (p(i) + inverseMass(triangles[t + k / 3]) * g[k] * dl).toFloat()
            }
        }
    }

    /** Relaxes the thickness interval to the mean when geometry makes it infeasible. */
    private fun massBounds(): DoubleArray {
        val average = totalFilmMass / (liquidDensity * max(totalArea, EPS))
        val low = min(minThickness, average)
        val high = max(maxThickness, average)
        boundsRelaxed = low != minThickness || high != maxThickness
        return doubleArrayOf(low, high)
    }

    /** Re-measures vertex areas, clamps film mass conservatively and derives thickness. */
    fun updateThickness() {
        measure()
        val original = totalFilmMass
        val (low, high) = massBounds()
        var clamped = 0.0
        for (i in 0 until count) {
            val capacity = area[i] * liquidDensity
            filmMass[i] = max(capacity * low, min(capacity * high, filmMass[i]))
            clamped += filmMass[i]
        }
        val remaining = original - clamped
        if (abs(remaining) > 1e-24) {
            var capacity = 0.0
            for (i in 0 until count) capacity += available(i, remaining, low, high)
            if (capacity > 0) {
                for (i in 0 until count) {
                    filmMass[i] += remaining * available(i, remaining, low, high) / capacity
                }
            }
        }
        for (i in 0 until count) {
            thickness[i] = (filmMass[i] / (liquidDensity * max(area[i], 1e-16))).toFloat()
        }
    }

    private fun available(
        i: Int,
        remaining: Double,
        low: Double,
        high: Double,
    ): Double =
        if (remaining > 0) {
            area[i] * liquidDensity * high - filmMass[i]
        } else {
            filmMass[i] - area[i] * liquidDensity * low
        }

    private fun drain(dt: Double) {
        updateThickness()
        if (!(drainageTimeScale > 0 && drainageGravity > 0)) return
        val out = DoubleArray(count)
        val incoming = DoubleArray(count)
        val (low, high) = massBounds()
        val proposed = ArrayList<DoubleArray>()
        for (e in edges.rest.indices) {
            val flow = edgeFlux(e, dt) ?: continue
            proposed.add(flow)
            out[flow[0].toInt()] += flow[2]
            incoming[flow[1].toInt()] += flow[2]
        }
        val delta = DoubleArray(count)
        drainageFlux = 0.0
        for ((donor, receiver, flux) in proposed) {
            val a = donor.toInt()
            val b = receiver.toInt()
            val available = max(0.0, filmMass[a] - area[a] * liquidDensity * low)
            val space = max(0.0, area[b] * liquidDensity * high - filmMass[b])
            val share = min(1.0, available / max(out[a], 1e-30))
            val transfer = flux * min(share, space / max(incoming[b], 1e-30))
            delta[a] -= transfer
            delta[b] += transfer
            drainageFlux += transfer
        }
        for (i in 0 until count) filmMass[i] += delta[i]
        updateThickness()
    }

    /** One edge's downhill thin-film flux `[donor, receiver, flux]`, mobility h³ / (3μ). */
    private fun edgeFlux(
        e: Int,
        dt: Double,
    ): DoubleArray? {
        val a = edges.indices[2 * e]
        val b = edges.indices[2 * e + 1]
        val dy = positions(3 * a + 1) - positions(3 * b + 1)
        if (abs(dy) < EPS) return null
        val dz = positions(3 * a + 2) - positions(3 * b + 2)
        val length = hypot(positions(3 * a) - positions(3 * b), dy, dz)
        if (length < EPS) return null
        val h = (thickness(a) + thickness(b)) * .5
        val width = (area[a] + area[b]) / (3 * length)
        val slope = abs(dy) / length
        val flux =
            liquidDensity * liquidDensity * drainageGravity * slope * h.pow(3) * width * dt *
                drainageTimeScale / (3 * dynamicViscosity)
        val donor = if (dy > 0) a else b
        val receiver = if (dy > 0) b else a
        return doubleArrayOf(donor.toDouble(), receiver.toDouble(), flux)
    }

    override fun step(dt: Double) {
        begin(dt)
        edges.lambda.fill(0f)
        areaLambda.fill(0.0)
        volumeLambda = 0.0
        repeat(iterations) {
            solveDistances(positions, inverseMass, edges, edgeCompliance, dt)
            solveTension(dt)
            solveVolume(dt)
            solveGrabs(dt)
            collide()
            applyPins()
        }
        finish(dt)
        drain(dt)
    }
}

/**
 * film.js `BubblePairCoupling`: two closed films pressed into flattened, separated contact caps
 * about the plane of their gas-equivalent radii; no shared wall, rupture or coalescence.
 */
internal class BubblePairCoupling(
    override val a: BubbleFilm,
    override val b: BubbleFilm,
    var gap: Double = .002,
    var iterations: Int = 8,
    var restitution: Double = .05,
) : PhysicsCoupling {
    var contactCount = 0
        private set
    var contactRadius = 0.0
        private set

    override fun step(dt: Double) {
        val ca = a.center
        val cb = b.center
        val dv = DoubleArray(3) { cb[it] - ca[it] }
        val distance = hypot(dv[0], dv[1], dv[2])
        val ra = cbrt(a.restVolume * 3 / (4 * PI))
        val rb = cbrt(b.restVolume * 3 / (4 * PI))
        contactCount = 0
        if (distance >= ra + rb + gap || distance < EPS) {
            contactRadius = 0.0
            return
        }
        val n = DoubleArray(3) { dv[it] / distance }
        val offset = (distance * distance + ra * ra - rb * rb) / (2 * distance)
        val plane = DoubleArray(3) { ca[it] + n[it] * offset }
        val savedA = a.positions.copyOf()
        val savedB = b.positions.copyOf()
        contactRadius = sqrt(max(0.0, ra * ra - offset * offset))
        repeat(iterations) {
            project(a, 1.0, plane, n)
            project(b, -1.0, plane, n)
            a.solveVolume(dt)
            b.solveVolume(dt)
        }
        contactCount = project(a, 1.0, plane, n) + project(b, -1.0, plane, n)
        settle(a, savedA, 1.0, plane, n, dt)
        settle(b, savedB, -1.0, plane, n, dt)
    }

    private fun project(
        body: BubbleFilm,
        sign: Double,
        plane: DoubleArray,
        n: DoubleArray,
    ): Int {
        var count = 0
        val p = body.positions
        for (i in 0 until body.count) {
            if (body.inverseMass[i] == 0f) continue
            val k = 3 * i
            val violation = sign * side(p, k, plane, n) + gap * .5
            if (violation > 0) {
                for (q in 0 until 3) p[k + q] = (p(k + q) - sign * n[q] * violation).toFloat()
                count++
            }
        }
        return count
    }

    /** Position corrections become inertial velocity with inward normal motion removed. */
    private fun settle(
        body: BubbleFilm,
        before: FloatArray,
        sign: Double,
        plane: DoubleArray,
        n: DoubleArray,
        dt: Double,
    ) {
        val p = body.positions
        val v = body.velocities
        for (i in 0 until body.count) {
            if (body.inverseMass[i] == 0f) continue
            val k = 3 * i
            for (q in 0 until 3) v[k + q] = (v(k + q) + (p(k + q) - before(k + q)) / dt).toFloat()
            val vn = (v(k) * n[0] + v(k + 1) * n[1] + v(k + 2) * n[2]) * sign
            if (abs(side(p, k, plane, n)) < gap + .003 && vn > 0) {
                for (q in 0 until 3) {
                    v[k + q] = (v(k + q) - sign * n[q] * vn * (1 + restitution)).toFloat()
                }
            }
        }
        body.updateThickness()
    }

    private fun side(
        p: FloatArray,
        k: Int,
        plane: DoubleArray,
        n: DoubleArray,
    ): Double =
        (p(k) - plane[0]) * n[0] + (p(k + 1) - plane[1]) * n[1] + (p(k + 2) - plane[2]) * n[2]
}
