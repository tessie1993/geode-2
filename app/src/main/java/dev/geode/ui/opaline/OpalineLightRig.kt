package dev.geode.ui.opaline

import kotlin.math.sqrt

/**
 * The shared light rig of the library workbench (src/workbench.js): a hemisphere light, the key
 * "sun" and cool rim directional lights, two blue point lights, ACES filmic tone mapping at
 * exposure 0.78 and the RoomEnvironment at intensity 0.42. Colours are linear and already
 * multiplied by intensity, as three.js uploads them.
 *
 * Point lights sit in element space (metres around the element's origin), so each part is lit
 * as the workbench lights an element of its size: [pointPosition] and [pointDistance] scale with
 * the part, and [pointColor] scales by the square of it to cancel the inverse-square falloff.
 */
internal object OpalineLightRig {
    const val TONE_MAPPING_EXPOSURE = 0.78f
    const val ENVIRONMENT_INTENSITY = 0.42f
    private const val POINT_DECAY = 2f

    val hemisphereSky = scaled(0xC5EADC, .55f)
    val hemisphereGround = scaled(0x142640, .55f)
    val hemisphereDirection = floatArrayOf(0f, 1f, 0f)

    /** Directions toward each light (position minus the origin target), then their colours. */
    val directionalDirections = unit(-5f, 9f, 7f) + unit(8f, 5f, -6f)
    val directionalColors = scaled(0xE4F3FF, 1.8f) + scaled(0x6BBAFF, 1.25f)

    private val pointPositions = floatArrayOf(3f, 4f, 3f, -2f, .4f, 1f)
    private val pointColors = scaled(0x8EBDFF, 8f) + scaled(0x80CEFF, 6f)
    private val pointDistances = floatArrayOf(15f, 12f)
    val pointDecays = floatArrayOf(POINT_DECAY, POINT_DECAY)

    /** View-space positions of both point lights for an element whose origin is at [center]. */
    fun pointPosition(
        center: FloatArray,
        scale: Float,
        out: FloatArray,
    ) {
        for (i in out.indices) out[i] = center[i % 3] + pointPositions[i] * scale
    }

    fun pointColor(
        scale: Float,
        out: FloatArray,
    ) {
        for (i in out.indices) out[i] = pointColors[i] * scale * scale
    }

    fun pointDistance(
        scale: Float,
        out: FloatArray,
    ) {
        for (i in out.indices) out[i] = pointDistances[i] * scale
    }

    private fun scaled(
        color: Int,
        intensity: Float,
    ): FloatArray = linearRgb(color).also { rgb -> for (i in rgb.indices) rgb[i] *= intensity }

    private fun unit(
        x: Float,
        y: Float,
        z: Float,
    ): FloatArray {
        val length = sqrt(x * x + y * y + z * z)
        return floatArrayOf(x / length, y / length, z / length)
    }
}
