package dev.geode.ui.opaline.transitions

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.floor
import kotlin.math.pow

/**
 * three.js r186 `CatmullRomCurve3` (vendor/three src/extras/curves/CatmullRomCurve3.js) with the
 * `Curve` methods it inherits (src/extras/core/Curve.js): 200 arc-length divisions, tangents and
 * Frenet frames. src/transitions.js builds it open and centripetal for the L12 centreline, the
 * L18 camera path and the L03 connection tubes.
 */
internal class TransitionCurve(
    private val points: List<TransitionVector>,
) {
    /** `computeFrenetFrames` result, one entry per segment boundary. */
    class Frames(
        val tangents: List<TransitionVector>,
        val normals: List<TransitionVector>,
        val binormals: List<TransitionVector>,
    )

    private var lengths: DoubleArray? = null

    fun getPoint(
        t: Double,
        target: TransitionVector = TransitionVector(),
    ): TransitionVector {
        val l = points.size
        val p = (l - 1) * t
        var index = floor(p).toInt()
        var weight = p - index
        if (weight == 0.0 && index == l - 1) {
            index = l - 2
            weight = 1.0
        }
        val p0 = if (index > 0) points[index - 1] else extrapolate(points[0], points[1])
        val p1 = points[index]
        val p2 = points[index + 1]
        val p3 = if (index + 2 < l) points[index + 2] else extrapolate(points[l - 1], points[l - 2])
        var dt0 = p0.distanceToSquared(p1).pow(0.25)
        var dt1 = p1.distanceToSquared(p2).pow(0.25)
        var dt2 = p2.distanceToSquared(p3).pow(0.25)
        // Safety check for repeated points.
        if (dt1 < 1e-4) dt1 = 1.0
        if (dt0 < 1e-4) dt0 = dt1
        if (dt2 < 1e-4) dt2 = dt1
        return target.set(
            cubic(p0.x, p1.x, p2.x, p3.x, dt0, dt1, dt2, weight),
            cubic(p0.y, p1.y, p2.y, p3.y, dt0, dt1, dt2, weight),
            cubic(p0.z, p1.z, p2.z, p3.z, dt0, dt1, dt2, weight),
        )
    }

    fun getPointAt(
        u: Double,
        target: TransitionVector,
    ): TransitionVector = getPoint(mapping(u), target)

    fun getTangent(
        t: Double,
        target: TransitionVector = TransitionVector(),
    ): TransitionVector {
        val start = getPoint(maxOf(t - 0.0001, 0.0))
        return target.copy(getPoint(minOf(t + 0.0001, 1.0))).sub(start).normalize()
    }

    /** `computeFrenetFrames(segments, false)`. */
    fun computeFrenetFrames(segments: Int): Frames {
        val tangents = List(segments + 1) { getTangent(mapping(it.toDouble() / segments)) }
        val first = tangents[0]
        var smallest = Double.MAX_VALUE
        val normal = TransitionVector()
        if (abs(first.x) <= smallest) {
            smallest = abs(first.x)
            normal.set(1.0, 0.0, 0.0)
        }
        if (abs(first.y) <= smallest) {
            smallest = abs(first.y)
            normal.set(0.0, 1.0, 0.0)
        }
        if (abs(first.z) <= smallest) normal.set(0.0, 0.0, 1.0)
        val axis = TransitionVector().cross(first, normal).normalize()
        val normals = mutableListOf(TransitionVector().cross(first, axis))
        val binormals = mutableListOf(TransitionVector().cross(first, normals[0]))
        for (i in 1..segments) {
            val next = normals[i - 1].clone()
            axis.cross(tangents[i - 1], tangents[i])
            if (axis.length() > Math.ulp(1.0)) {
                axis.normalize()
                val cosine = TransitionMath.clamp(tangents[i - 1].dot(tangents[i]), -1.0, 1.0)
                next.applyRotationAxis(axis, acos(cosine))
            }
            normals.add(next)
            binormals.add(TransitionVector().cross(tangents[i], next))
        }
        return Frames(tangents, normals, binormals)
    }

    /** `getUtoTmapping(u)`: arc-length fraction [u] to curve parameter. */
    private fun mapping(u: Double): Double {
        val arc = arcLengths()
        val last = arc.size - 1
        val target = u * arc[last]
        var low = 0
        var high = last
        while (low <= high) {
            val i = low + (high - low) / 2
            val comparison = arc[i] - target
            when {
                comparison < 0 -> low = i + 1
                comparison > 0 -> high = i - 1
                else -> {
                    high = i
                    break
                }
            }
        }
        if (arc[high] == target) return high.toDouble() / last
        return (high + (target - arc[high]) / (arc[high + 1] - arc[high])) / last
    }

    /** `getLengths(200)`, cached. */
    private fun arcLengths(): DoubleArray =
        lengths ?: DoubleArray(DIVISIONS + 1).also { cache ->
            var previous = getPoint(0.0)
            for (p in 1..DIVISIONS) {
                val current = getPoint(p.toDouble() / DIVISIONS)
                cache[p] = cache[p - 1] + current.distanceTo(previous)
                previous = current
            }
            lengths = cache
        }

    private companion object {
        const val DIVISIONS = 200

        /** `tmp.subVectors(a, b).add(a)`: the extrapolated end point. */
        fun extrapolate(
            a: TransitionVector,
            b: TransitionVector,
        ): TransitionVector = a.clone().sub(b).add(a)

        /** CubicPoly `initNonuniformCatmullRom(x0…x3, dt0, dt1, dt2)` then `calc(t)`. */
        fun cubic(
            x0: Double,
            x1: Double,
            x2: Double,
            x3: Double,
            dt0: Double,
            dt1: Double,
            dt2: Double,
            t: Double,
        ): Double {
            val t1 = ((x1 - x0) / dt0 - (x2 - x0) / (dt0 + dt1) + (x2 - x1) / dt1) * dt1
            val t2 = ((x2 - x1) / dt1 - (x3 - x1) / (dt1 + dt2) + (x3 - x2) / dt2) * dt1
            val c2 = -3 * x1 + 3 * x2 - 2 * t1 - t2
            val c3 = 2 * x1 - 2 * x2 + t1 + t2
            val square = t * t
            return x1 + t1 * t + c2 * square + c3 * (square * t)
        }
    }
}
