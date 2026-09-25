package dev.geode.ui.opaline

import android.annotation.SuppressLint
import android.util.Half
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The image-based light of the library workbench: three.js RoomEnvironment
 * (vendor/three/examples/jsm/environments/RoomEnvironment.js, MIT) prefiltered by
 * `PMREMGenerator.fromScene(room, 0.035)` and bound as `scene.environment`.
 *
 * The room is evaluated analytically instead of rasterised: rays from the origin meet the same
 * boxes, whose MeshStandardMaterial surfaces are lit by the same unshadowed point light with the
 * physical direct-lighting equations of the surface pass, and the emissive panels return their
 * intensity. Mip 0 carries the fromScene blur of 0.035 radians; each further level is the GGX
 * prefilter for roughness = level / (levels - 1), which the surface shader samples at
 * `roughness * maxLod`. Radiance is linear and unbounded, as in the PMREM render target.
 */
internal object OpalineRoomEnvironment {
    const val BASE_SIZE = 64
    const val LEVELS = 6

    /** Level-major list of six cube faces (+X, -X, +Y, -Y, +Z, -Z), RGB floats row by row. */
    val levels: List<Array<FloatArray>> by lazy { prefilter() }

    private class Box(
        val center: DoubleArray,
        val rotationY: Double,
        val half: DoubleArray,
        val emission: Double,
        val backSide: Boolean,
    )

    private const val SCENE_OFFSET_Y = -3.5
    private val lightPosition = doubleArrayOf(0.418, 16.199 + SCENE_OFFSET_Y, 0.300)
    private const val LIGHT_INTENSITY = 900.0
    private const val LIGHT_DISTANCE = 28.0

    private val boxes: List<Box> =
        listOf(
            box(-0.757, 13.219, 0.717, 0.0, 31.713, 28.305, 28.591, backSide = true),
            box(-10.906, 2.009, 1.846, -0.195, 2.328, 7.905, 4.651),
            box(-5.607, -0.754, -0.758, 0.994, 1.970, 1.534, 3.955),
            box(6.167, 0.857, 7.803, 0.561, 3.927, 6.285, 3.687),
            box(-2.017, 0.018, 6.124, 0.333, 2.002, 4.566, 2.064),
            box(2.291, -0.756, -2.621, -0.286, 1.546, 1.552, 1.496),
            box(-2.193, -0.369, -5.547, 0.516, 3.875, 3.487, 2.986),
            box(-16.116, 14.37, 8.208, 0.0, 0.1, 2.428, 2.739, emission = 50.0),
            box(-16.109, 18.021, -8.207, 0.0, 0.1, 2.425, 2.751, emission = 50.0),
            box(14.904, 12.198, -1.832, 0.0, 0.15, 4.265, 6.331, emission = 17.0),
            box(-0.462, 8.89, 14.520, 0.0, 4.38, 5.441, 0.088, emission = 43.0),
            box(3.235, 11.486, -12.541, 0.0, 2.5, 2.0, 0.1, emission = 20.0),
            box(0.0, 20.0, 0.0, 0.0, 1.0, 0.1, 1.0, emission = 100.0),
        )

    private fun box(
        x: Double,
        y: Double,
        z: Double,
        rotationY: Double,
        sx: Double,
        sy: Double,
        sz: Double,
        emission: Double = 0.0,
        backSide: Boolean = false,
    ) = Box(doubleArrayOf(x, y + SCENE_OFFSET_Y, z), rotationY, doubleArrayOf(sx / 2, sy / 2, sz / 2), emission, backSide)

    private class Hit(
        var distance: Double = Double.POSITIVE_INFINITY,
        val normal: DoubleArray = DoubleArray(3),
        var emission: Double = 0.0,
    )

    /** Radiance arriving at the origin from [direction], written into [out] as linear RGB. */
    private fun radiance(
        direction: DoubleArray,
        out: DoubleArray,
    ) {
        val hit = Hit()
        for (box in boxes) intersect(box, direction, hit)
        if (hit.distance.isInfinite()) {
            out.fill(0.0)
            return
        }
        if (hit.emission > 0.0) {
            out.fill(hit.emission)
            return
        }
        out.fill(shadeWhiteStandard(direction, hit))
    }

    /** Ray/box slab test in the box's yaw-rotated frame; keeps the nearest visible face. */
    private fun intersect(
        box: Box,
        direction: DoubleArray,
        hit: Hit,
    ) {
        val c = cos(box.rotationY)
        val s = sin(box.rotationY)
        // World to local: rotate by -rotationY about +Y.
        val ox = -(c * box.center[0] - s * box.center[2])
        val oy = -box.center[1]
        val oz = -(s * box.center[0] + c * box.center[2])
        val origin = doubleArrayOf(ox, oy, oz)
        val dir = doubleArrayOf(c * direction[0] - s * direction[2], direction[1], s * direction[0] + c * direction[2])
        var near = Double.NEGATIVE_INFINITY
        var far = Double.POSITIVE_INFINITY
        var nearAxis = 0
        var farAxis = 0
        for (axis in 0..2) {
            val inverse = 1.0 / (if (abs(dir[axis]) < 1e-12) 1e-12 else dir[axis])
            val t0 = (-box.half[axis] - origin[axis]) * inverse
            val t1 = (box.half[axis] - origin[axis]) * inverse
            val low = min(t0, t1)
            val high = max(t0, t1)
            if (low > near) {
                near = low
                nearAxis = axis
            }
            if (high < far) {
                far = high
                farAxis = axis
            }
        }
        if (near > far) return
        // A BackSide room is seen from inside at its exit face; front-side boxes at their entry.
        val distance = if (box.backSide) far else near
        val axis = if (box.backSide) farAxis else nearAxis
        if (distance <= 0.0 || distance >= hit.distance) return
        // Entry faces face against the ray; a BackSide wall is shaded with its inward normal,
        // which also faces against the ray. Either way the normal is minus the ray's sign.
        val local = DoubleArray(3)
        local[axis] = if (dir[axis] > 0.0) -1.0 else 1.0
        // Local normal back to world: rotate by +rotationY about +Y.
        hit.normal[0] = c * local[0] + s * local[2]
        hit.normal[1] = local[1]
        hit.normal[2] = -s * local[0] + c * local[2]
        hit.distance = distance
        hit.emission = box.emission
    }

    /**
     * RE_Direct_Physical for a white MeshStandardMaterial (roughness 1, metalness 0, specular
     * colour 0.04) lit by the room's PointLight(0xffffff, 900, 28, 2), seen from the origin.
     */
    private fun shadeWhiteStandard(
        direction: DoubleArray,
        hit: Hit,
    ): Double {
        val position = DoubleArray(3) { direction[it] * hit.distance }
        val toLight = DoubleArray(3) { lightPosition[it] - position[it] }
        val lightDistance = sqrt(dot(toLight, toLight))
        val l = DoubleArray(3) { toLight[it] / lightDistance }
        val v = DoubleArray(3) { -direction[it] }
        val n = hit.normal
        val dotNL = dot(n, l).coerceIn(0.0, 1.0)
        if (dotNL <= 0.0) return 0.0
        val window = (1.0 - (lightDistance / LIGHT_DISTANCE).let { it * it * it * it }).coerceIn(0.0, 1.0)
        val attenuation = 1.0 / max(lightDistance * lightDistance, 0.01) * window * window
        val irradiance = dotNL * LIGHT_INTENSITY * attenuation
        val h = normalize(DoubleArray(3) { l[it] + v[it] })
        val dotNV = dot(n, v).coerceIn(0.0, 1.0)
        val dotNH = dot(n, h).coerceIn(0.0, 1.0)
        val dotVH = dot(v, h).coerceIn(0.0, 1.0)
        val fresnel = fSchlick(SPECULAR, dotVH)
        val alpha = 1.0
        val a2 = alpha * alpha
        val gv = dotNL * sqrt(a2 + (1.0 - a2) * dotNV * dotNV)
        val gl = dotNV * sqrt(a2 + (1.0 - a2) * dotNL * dotNL)
        val visibility = 0.5 / max(gv + gl, 1e-6)
        val distribution = a2 / (PI * (dotNH * dotNH * (a2 - 1.0) + 1.0).let { it * it })
        val (scale, bias) = dfg(1.0, dotNV)
        val compensation = 1.0 + SPECULAR * (1.0 / (scale + bias) - 1.0)
        val specular = irradiance * fresnel * visibility * distribution * compensation
        val diffuse = irradiance * (1.0 / PI) * (1.0 - fresnel)
        return diffuse + specular
    }

    private const val SPECULAR = 0.04

    private fun fSchlick(
        f0: Double,
        dotVH: Double,
    ): Double {
        val fresnel = 2.0.pow((-5.55473 * dotVH - 6.98316) * dotVH)
        return f0 * (1.0 - fresnel) + fresnel
    }

    private val dfgTable: FloatArray by lazy { decodeDfgTable() }

    // Lint's HalfFloat check misreads the FloatArray initializer lambda as widening a half;
    // the conversion goes through android.util.Half.toFloat as the check asks.
    @SuppressLint("HalfFloat")
    private fun decodeDfgTable(): FloatArray {
        val halves = OpalineDfgLut.halfFloats()
        return FloatArray(halves.size) { Half.toFloat(halves[it]) }
    }

    /** Bilinear read of the three.js DFG table at (roughness, dotNV), clamped to its edges. */
    private fun dfg(
        roughness: Double,
        dotNV: Double,
    ): Pair<Double, Double> {
        val size = OpalineDfgLut.SIZE
        val x = (roughness * size - 0.5).coerceIn(0.0, size - 1.0)
        val y = (dotNV * size - 0.5).coerceIn(0.0, size - 1.0)
        val x0 = x.toInt()
        val y0 = y.toInt()
        val x1 = min(x0 + 1, size - 1)
        val y1 = min(y0 + 1, size - 1)
        val fx = x - x0
        val fy = y - y0

        fun at(
            column: Int,
            row: Int,
            channel: Int,
        ) = dfgTable[(row * size + column) * 2 + channel].toDouble()

        fun sample(channel: Int): Double {
            val top = at(x0, y0, channel) * (1 - fx) + at(x1, y0, channel) * fx
            val bottom = at(x0, y1, channel) * (1 - fx) + at(x1, y1, channel) * fx
            return top * (1 - fy) + bottom * fy
        }
        return sample(0) to sample(1)
    }

    private const val BLUR_SIGMA = 0.035
    private const val BLUR_SAMPLES = 8
    private const val GGX_SAMPLES = 128

    private fun prefilter(): List<Array<FloatArray>> =
        List(LEVELS) { level ->
            val size = BASE_SIZE shr level
            Array(6) { face -> renderFace(face, size, level) }
        }

    private fun renderFace(
        face: Int,
        size: Int,
        level: Int,
    ): FloatArray {
        val pixels = FloatArray(size * size * 3)
        val direction = DoubleArray(3)
        val color = DoubleArray(3)
        for (row in 0 until size) {
            for (column in 0 until size) {
                faceDirection(face, (column + 0.5) / size * 2 - 1, (row + 0.5) / size * 2 - 1, direction)
                if (level == 0) blurred(direction, color) else prefiltered(direction, level.toDouble() / (LEVELS - 1), color)
                val index = (row * size + column) * 3
                for (channel in 0..2) pixels[index + channel] = color[channel].toFloat()
            }
        }
        return pixels
    }

    /** OpenGL cube-map face orientation: the direction of texel (s, t) in [-1, 1]. */
    private fun faceDirection(
        face: Int,
        s: Double,
        t: Double,
        out: DoubleArray,
    ) {
        when (face) {
            0 -> set(out, 1.0, -t, -s)
            1 -> set(out, -1.0, -t, s)
            2 -> set(out, s, 1.0, t)
            3 -> set(out, s, -1.0, -t)
            4 -> set(out, s, -t, 1.0)
            else -> set(out, -s, -t, -1.0)
        }
        val unit = normalize(out)
        unit.copyInto(out)
    }

    /** The fromScene Gaussian blur of sigma 0.035 radians, over a small fixed ring of rays. */
    private fun blurred(
        direction: DoubleArray,
        out: DoubleArray,
    ) {
        val (tangent, bitangent) = basis(direction)
        val sample = DoubleArray(3)
        val value = DoubleArray(3)
        var weightSum = 0.0
        out.fill(0.0)
        for (i in 0..BLUR_SAMPLES) {
            val radius = if (i == 0) 0.0 else BLUR_SIGMA * 1.5
            val angle = 2 * PI * i / BLUR_SAMPLES
            val weight = exp(-radius * radius / (2 * BLUR_SIGMA * BLUR_SIGMA))
            for (a in 0..2) sample[a] = direction[a] + (tangent[a] * cos(angle) + bitangent[a] * sin(angle)) * radius
            radiance(normalize(sample), value)
            for (a in 0..2) out[a] += value[a] * weight
            weightSum += weight
        }
        for (a in 0..2) out[a] /= weightSum
    }

    /** Split-sum GGX prefilter with N = V = R, importance-sampled with a Hammersley set. */
    private fun prefiltered(
        normal: DoubleArray,
        roughness: Double,
        out: DoubleArray,
    ) {
        val alpha = roughness * roughness
        val (tangent, bitangent) = basis(normal)
        val half = DoubleArray(3)
        val light = DoubleArray(3)
        val value = DoubleArray(3)
        var weightSum = 0.0
        out.fill(0.0)
        for (i in 0 until GGX_SAMPLES) {
            val u = (i + 0.5) / GGX_SAMPLES
            val v = radicalInverse(i)
            val phi = 2 * PI * u
            val cosTheta = sqrt((1 - v) / (1 + (alpha * alpha - 1) * v))
            val sinTheta = sqrt(1 - cosTheta * cosTheta)
            for (a in 0..2) {
                half[a] = tangent[a] * sinTheta * cos(phi) + bitangent[a] * sinTheta * sin(phi) + normal[a] * cosTheta
            }
            val dotNH = dot(normal, half)
            for (a in 0..2) light[a] = 2 * dotNH * half[a] - normal[a]
            val dotNL = dot(normal, light)
            if (dotNL > 0.0) {
                radiance(light, value)
                for (a in 0..2) out[a] += value[a] * dotNL
                weightSum += dotNL
            }
        }
        if (weightSum > 0.0) for (a in 0..2) out[a] /= weightSum
    }

    private fun radicalInverse(index: Int): Double = Integer.reverse(index).toUInt().toDouble() / 4294967296.0

    private fun basis(n: DoubleArray): Pair<DoubleArray, DoubleArray> {
        val up = if (abs(n[1]) < 0.999) doubleArrayOf(0.0, 1.0, 0.0) else doubleArrayOf(1.0, 0.0, 0.0)
        val tangent = normalize(cross(up, n))
        return tangent to cross(n, tangent)
    }

    private fun set(
        out: DoubleArray,
        x: Double,
        y: Double,
        z: Double,
    ) {
        out[0] = x
        out[1] = y
        out[2] = z
    }

    private fun dot(
        a: DoubleArray,
        b: DoubleArray,
    ) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

    private fun cross(
        a: DoubleArray,
        b: DoubleArray,
    ) = doubleArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])

    private fun normalize(v: DoubleArray): DoubleArray {
        val length = sqrt(dot(v, v)).coerceAtLeast(1e-12)
        return doubleArrayOf(v[0] / length, v[1] / length, v[2] / length)
    }
}
