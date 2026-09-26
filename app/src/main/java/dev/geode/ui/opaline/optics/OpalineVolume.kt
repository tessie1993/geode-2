package dev.geode.ui.opaline.optics

import android.content.res.AssetManager
import android.opengl.Matrix
import dev.geode.ui.opaline.OpalineProgram
import dev.geode.ui.opaline.linearRgb

/** createVolumeMaterial `shape`: the implicit density boundary inside the box. */
internal enum class OpalineVolumeShape {
    ELLIPSOID,
    BOX,
}

/**
 * One part's participating medium: the options and uniforms of src/materials.js
 * `createVolumeMaterial` (I05, I13, I14, I15), with the factory's defaults. The medium fills a box
 * of half-extents [bounds] in its local coordinates, where [lightPosition] and the interaction
 * point also live. Colours are sRGB hex, kept linear as three.js `Color` keeps them.
 */
internal class OpalineVolume(
    color: Int = 0xC3EFF2,
    absorption: Int = 0x2D5360,
    var density: Float = 1.6f,
    val bounds: FloatArray = floatArrayOf(1f, 1f, 1f),
    val lightPosition: FloatArray = floatArrayOf(4f, 6f, 3f),
    lightColor: Int = 0xFFF5E4,
    val shape: OpalineVolumeShape = OpalineVolumeShape.ELLIPSOID,
    var anisotropy: Float = .35f,
) {
    val scatterColor = linearRgb(color)
    val absorptionColor = linearRgb(absorption)

    /** `new THREE.Color(lightColor).multiplyScalar(12)`. */
    val lightRadiance = linearRgb(lightColor).also { rgb -> for (i in rgb.indices) rgb[i] *= 12f }

    /** `uTime`, advanced by updateMaterials with the scene clock. */
    var time = 0f
    val touch = FloatArray(3)
    var excitation = 0f
        private set

    /** `material.userData.setInteraction(point, strength)`; a null point keeps the last one. */
    fun setInteraction(
        point: FloatArray?,
        strength: Float = 0f,
    ) {
        point?.copyInto(touch, 0, 0, 3)
        excitation = strength
    }
}

/** The participating-medium program (VOLUME_VERTEX, VOLUME_FRAGMENT), shared by every part. */
internal class OpalineVolumeProgram(
    assets: AssetManager,
) {
    private val program = OpalineProgram(assets, "optics/volume")
    private val cube = OpalineOpticsCube()
    private val inverse = FloatArray(16)
    private val camera = FloatArray(3)

    /**
     * `bindVolume(mesh)` and draw: [volume] under [modelView] (medium-local to view space, the
     * camera at the origin, as the renderer's `uModel`) and [projection]; the inverse brings the
     * camera into local coordinates as `uWorldToLocal` does. Draw after the opaque parts.
     */
    fun draw(
        volume: OpalineVolume,
        modelView: FloatArray,
        projection: FloatArray,
    ) {
        Matrix.invertM(inverse, 0, modelView, 0)
        inverse.copyInto(camera, 0, 12, 15)
        program.use()
        program.matrix("uModelView", modelView)
        program.matrix("uProjection", projection)
        program.vec3("uCameraLocal", camera)
        program.vec3("uBounds", volume.bounds)
        program.vec3("uScatterColor", volume.scatterColor)
        program.vec3("uAbsorption", volume.absorptionColor)
        program.vec3("uLightPosition", volume.lightPosition)
        program.vec3("uLightColor", volume.lightRadiance)
        program.vec3("uAmbient", AMBIENT)
        program.scalar("uDensity", volume.density)
        program.scalar("uTime", volume.time)
        program.scalar("uAnisotropy", volume.anisotropy)
        program.scalar("uShape", if (volume.shape == OpalineVolumeShape.BOX) 1f else 0f)
        program.scalar("uExcitation", volume.excitation)
        program.vec3("uTouch", volume.touch)
        cube.drawMedium(mirrored(modelView))
    }

    fun release() {
        program.dispose()
        cube.release()
    }

    private companion object {
        /** createVolumeMaterial `uAmbient: new THREE.Vector3(.12, .16, .19)`. */
        val AMBIENT = floatArrayOf(.12f, .16f, .19f)
    }
}
