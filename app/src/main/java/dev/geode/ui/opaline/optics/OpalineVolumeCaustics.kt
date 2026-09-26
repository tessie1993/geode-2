package dev.geode.ui.opaline.optics

import android.content.res.AssetManager
import android.opengl.Matrix
import dev.geode.ui.opaline.OpalineProgram
import android.opengl.GLES30 as GL

/**
 * src/optics.js `createVolumeCausticsMaterial(transport, {anisotropy, intensity, steps})` (I04)
 * with its defaults and clamps: the photon-lit medium on a box of the PhotonVolume's bounds,
 * ray-marching the six directional grids of [textures] with a Henyey-Greenstein weight each.
 */
internal class OpalineVolumeCaustics(
    assets: AssetManager,
    private val textures: OpalineCausticTextures,
    anisotropy: Float = .35f,
    private val intensity: Float = 1f,
    steps: Int = 128,
) {
    private val anisotropy = anisotropy.coerceIn(-.9f, .9f)
    private val steps = steps.coerceIn(32, 512)
    private val photons =
        requireNotNull(textures.caustics.volume) {
            "Volume-caustic material needs a PhotonVolume or transport with volume enabled."
        }
    private val program = OpalineProgram(assets, "optics/volume-caustics")
    private val cube = OpalineOpticsCube()
    private val cameraWorld = FloatArray(16)
    private val projectionInverse = FloatArray(16)
    private val vector = FloatArray(3)

    /**
     * `bindVolume(medium)` and draw under the world-to-view [view] and [projection] into a
     * drawing buffer of [width]×[height]; a nonzero [depthTexture] ends camera rays at the scene
     * depth. Draw after the opaque parts.
     */
    fun draw(
        view: FloatArray,
        projection: FloatArray,
        width: Int,
        height: Int,
        depthTexture: Int = 0,
    ) {
        Matrix.invertM(cameraWorld, 0, view, 0)
        Matrix.invertM(projectionInverse, 0, projection, 0)
        program.use()
        program.matrix("uView", view)
        program.matrix("uProjection", projection)
        program.matrix("uCameraWorld", cameraWorld)
        program.matrix("uProjectionInverse", projectionInverse)
        cameraWorld.copyInto(vector, 0, 12, 15)
        program.vec3("uCameraPosition", vector)
        for (axis in 0..2) vector[axis] = photons.boundsMin[axis].toFloat()
        program.vec3("uBoundsMin", vector)
        for (axis in 0..2) vector[axis] = photons.boundsMax[axis].toFloat()
        program.vec3("uBoundsMax", vector)
        for (bin in textures.volume.indices) {
            bindTexture(bin, GL.GL_TEXTURE_3D, textures.volume[bin])
            program.integer("uVolume$bin", bin)
        }
        bindTexture(textures.volume.size, GL.GL_TEXTURE_2D, depthTexture)
        program.integer("uSceneDepth", textures.volume.size)
        program.scalar("uUseDepth", if (depthTexture != 0) 1f else 0f)
        program.vec2("uResolution", width.toFloat(), height.toFloat())
        program.scalar("uExtinction", photons.extinction.toFloat())
        program.scalar("uAnisotropy", anisotropy)
        program.scalar("uIntensity", intensity)
        program.integer("uSteps", steps)
        cube.drawMedium(mirrored(view))
        GL.glActiveTexture(GL.GL_TEXTURE0)
    }

    fun release() {
        program.dispose()
        cube.release()
    }
}
