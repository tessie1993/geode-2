package dev.geode.ui.opaline.optics

import android.opengl.Matrix
import dev.geode.ui.opaline.OpalineMaterial
import dev.geode.ui.opaline.linearRgb

/** A linear RGB triple, as three.js `Color` stores its components. */
internal data class OpticalColor(
    val r: Float,
    val g: Float,
    val b: Float,
) {
    operator fun get(channel: Int): Float =
        when (channel) {
            0 -> r
            1 -> g
            else -> b
        }

    companion object {
        /** An sRGB hex colour as `new THREE.Color(hex)` stores it. */
        fun of(hex: Int): OpticalColor = linearRgb(hex).let { OpticalColor(it[0], it[1], it[2]) }
    }
}

/**
 * The source-material fields src/optics.js reads on a hit: `ior`, `dispersion`,
 * `attenuationColor`, `attenuationDistance`, `metalness`, `color`, and the authored reflector
 * override `userData.optics.reflectance` (a scalar override is the same value on all channels).
 */
internal data class OpticalMaterial(
    val ior: Float,
    val dispersion: Float,
    val attenuationColor: OpticalColor,
    val attenuationDistance: Float,
    val metalness: Float,
    val color: OpticalColor,
    val reflectance: OpticalColor? = null,
) {
    companion object {
        /** A src/materials.js family; every family is built with `metalness: 0` (`shared`). */
        fun of(material: OpalineMaterial): OpticalMaterial =
            OpticalMaterial(
                ior = material.ior,
                dispersion = material.dispersion,
                attenuationColor = OpticalColor.of(material.attenuationColor),
                attenuationDistance = material.attenuationDistance,
                metalness = 0f,
                color = OpticalColor.of(material.color),
            )
    }
}

/**
 * One mesh the photon transport intersects: local xyz [positions] of the current (deformed)
 * vertices, triangle [indices], [uvs] (required on the receiver, nonoverlapping), [material]
 * (null reads as the library's fallbacks) and the world [matrix], column-major as
 * android.opengl.Matrix writes it. Refractors must be closed and outward-wound. Call
 * [markChanged] after editing [positions], as three.js bumps `position.version`.
 */
internal class OpticalMesh(
    val positions: FloatArray,
    val indices: IntArray,
    val uvs: FloatArray? = null,
    var material: OpticalMaterial? = null,
) {
    val matrix = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
    var version = 0
        private set

    fun markChanged() {
        version++
    }
}
