package dev.geode.ui.opaline

import android.content.res.AssetManager
import android.opengl.Matrix
import dev.geode.ui.opaline.optics.CausticTransport
import dev.geode.ui.opaline.optics.OpalineCausticTextures
import dev.geode.ui.opaline.optics.OpalineRefractiveCaustics
import dev.geode.ui.opaline.optics.OpalineVolume
import dev.geode.ui.opaline.optics.OpalineVolumeProgram
import dev.geode.ui.opaline.optics.OpticalMaterial
import dev.geode.ui.opaline.optics.OpticalMesh
import java.util.concurrent.Executors
import java.util.concurrent.Future
import android.opengl.GLES30 as GL

/**
 * The renderer's side of src/optics.js and createVolumeMaterial (plan E2): participating media
 * inside the heroes (the workbench hero volume) and I01 refractive caustics from one lens onto the
 * realm water, traced on a worker thread every third frame and sampled by the water. EGL thread
 * only, except the photon batches.
 */
internal class OpalineOpticsBridge(
    assets: AssetManager,
) {
    /** One lens's caustics: the solver, its GPU copy and the refracting pieces. */
    private class Lens(
        val owner: Long,
        val solver: OpalineRefractiveCaustics,
        val textures: OpalineCausticTextures,
        val pieces: List<OpticalMesh>,
        val center: FloatArray,
    ) {
        var batch: Future<*>? = null
        var version = -1
    }

    private val program = OpalineVolumeProgram(assets)
    private val volumes = HashMap<Long, OpalineVolume>()
    private val worker = Executors.newSingleThreadExecutor()
    private var lens: Lens? = null
    private val modelView = FloatArray(16)

    /**
     * Draws part [id]'s medium: workbench.js buildWorld `heroVolume` (ellipsoid, density .72,
     * colour #8bbbec, light [−2, 4, 2]) filling the element box of [half] extents about [center]
     * under the element [model] (view space); it glows where the part is pressed.
     */
    fun volume(
        id: Long,
        model: FloatArray,
        center: FloatArray,
        half: FloatArray,
        projection: FloatArray,
        time: Float,
        touch: FloatArray,
        pressure: Float,
    ) {
        val volume =
            volumes.getOrPut(id) {
                OpalineVolume(
                    color = 0x8BBBEC,
                    density = .72f,
                    bounds = half.copyOf(),
                    lightPosition = floatArrayOf(-2f, 4f, 2f),
                )
            }
        volume.time = time
        volume.setInteraction(FloatArray(3) { touch[it] - center[it] }, pressure)
        Matrix.translateM(modelView, 0, model, 0, center[0], center[1], center[2])
        program.draw(volume, modelView, projection)
    }

    /**
     * Keeps the I01 caustics of the lens part [id] (workbench.js buildOpticalSpecimen I01: 128²,
     * 700 photons per update, light (−3, 6, 4) and target (1.9, 1, 0) around a lens at (1.9, 2, 0),
     * aperture 3 × 3, transmission) on an 8 × 8 receiver patch of the water under [center] (realm
     * metres); [pieces] are the lens's refracting pieces with their realm matrices.
     */
    fun lens(
        id: Long,
        center: FloatArray,
        pieces: List<Triple<OpalineMesh.Piece, OpalineMaterial, FloatArray>>,
        frame: Long,
    ) {
        val current = lens?.takeIf { it.owner == id } ?: create(id, center, pieces)
        if (current.batch?.isDone == false) return
        if (current.solver.version != current.version) {
            current.textures.upload()
            current.version = current.solver.version
        }
        for ((index, piece) in pieces.withIndex()) {
            val matrix = current.pieces[index].matrix
            if (!piece.third.contentEquals(matrix)) piece.third.copyInto(matrix)
        }
        if (frame % 3 == 0L) current.batch = worker.submit(Runnable { current.solver.update() })
    }

    private fun create(
        id: Long,
        center: FloatArray,
        pieces: List<Triple<OpalineMesh.Piece, OpalineMaterial, FloatArray>>,
    ): Lens {
        release()
        val receiver =
            OpticalMesh(
                floatArrayOf(-4f, 0f, -4f, 4f, 0f, -4f, -4f, 0f, 4f, 4f, 0f, 4f),
                intArrayOf(0, 2, 1, 2, 3, 1),
                floatArrayOf(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f),
            )
        Matrix.translateM(receiver.matrix, 0, center[0], OpalineRealm.WATER_Y, center[2])
        val refractors =
            pieces.map { (piece, material, _) ->
                OpticalMesh(piece.positions, piece.indices, material = OpticalMaterial.of(material))
            }
        val solver =
            OpalineRefractiveCaustics(
                receiver,
                refractors = refractors,
                resolution = 128,
                photonsPerUpdate = 700,
                lightPosition = doubleArrayOf(center[0] - 4.9, center[1] + 4.0, center[2] + 4.0),
                lightTarget = doubleArrayOf(center[0] + 0.0, center[1] - 1.0, center[2] + 0.0),
                aperture = doubleArrayOf(3.0, 3.0),
                transport = CausticTransport.TRANSMISSION,
            )
        return Lens(id, solver, OpalineCausticTextures(solver), refractors, center.copyOf()).also {
            lens = it
        }
    }

    /**
     * The water's receiver uniforms (`attachCaustics(material, texture, 1.8)`): the caustic
     * texture on [unit], its strength and the patch it covers; strength 0 without a lens.
     */
    fun bindReceiver(
        surface: OpalineProgram,
        unit: Int,
    ) {
        val current = lens
        GL.glActiveTexture(GL.GL_TEXTURE0 + unit)
        GL.glBindTexture(GL.GL_TEXTURE_2D, current?.textures?.receiver ?: 0)
        GL.glActiveTexture(GL.GL_TEXTURE0)
        surface.integer("uOpCaustics", unit)
        surface.scalar("uOpCausticStrength", if (current == null) 0f else RECEIVER_STRENGTH)
        val center = current?.center ?: FloatArray(3)
        surface.vec3("uCausticPatch", floatArrayOf(center[0], center[2], PATCH))
    }

    /** Drops the media and caustics of parts no longer drawn. */
    fun retain(ids: Set<Long>) {
        volumes.keys.retainAll(ids)
        if (lens?.owner !in ids) release()
    }

    private fun release() {
        val current = lens ?: return
        // A running batch finishes on the worker; it only touches this solver's arrays.
        current.batch?.cancel(false)
        current.textures.release()
        lens = null
    }

    fun dispose() {
        release()
        worker.shutdown()
        program.release()
    }

    private companion object {
        /** workbench.js I01 `attachCaustics(receiver.material, caustics.texture, 1.8)`. */
        const val RECEIVER_STRENGTH = 1.8f

        /** The I01 receiver: `new THREE.PlaneGeometry(8, 8)`. */
        const val PATCH = 8f
    }
}
