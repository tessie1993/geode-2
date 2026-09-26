package dev.geode.ui.opaline

import android.content.res.AssetManager
import android.opengl.Matrix
import dev.geode.ui.opaline.optics.OpalineFilmThickness
import dev.geode.ui.opaline.physics.OpalineBubbleSpecimen
import dev.geode.ui.opaline.physics.OpalineFluidSpecimen
import dev.geode.ui.opaline.physics.OpalineGelLattice
import dev.geode.ui.opaline.physics.OpalinePhysicsCatalogue
import dev.geode.ui.opaline.physics.ParticleSystem
import dev.geode.ui.opaline.physics.PhysicsWorld
import dev.geode.ui.opaline.physics.computeVertexNormals
import dev.geode.ui.opaline.physics.particleFamily
import dev.geode.ui.opaline.physics.writeParticleMatrix
import dev.geode.ui.opaline.physics.writeTrailSegments
import kotlin.math.min

/**
 * The renderer's side of src/physics (plan E2), in each part's element space (library metres):
 * K01/K02/K07/K23/K24 gel lattices on dented parts under touch, the Bubble hero's F01 film, the
 * E12 PBF liquid, and G07 emission filaments. Reduced motion keeps no lattices and steps nothing.
 * EGL thread only.
 */
internal class OpalinePhysicsBridge(
    assets: AssetManager,
) {
    class Bubble(
        val specimen: OpalineBubbleSpecimen,
        val mesh: OpalineDynamicMesh,
        val film: OpalineFilmThickness,
    )

    private class Emitter(
        val world: PhysicsWorld,
        val system: ParticleSystem,
        val trails: FloatArray,
    )

    private val catalogue = OpalinePhysicsCatalogue.get(assets)
    private val lattices = HashMap<Long, OpalineGelLattice>()
    private val bubbles = HashMap<Long, Bubble>()
    private val fluids = HashMap<Long, OpalineFluidSpecimen>()
    private val emitters = HashMap<Long, Emitter>()
    private val point = DoubleArray(3)
    private val matrix = FloatArray(16)
    private val inverse = FloatArray(16)
    private val vector = FloatArray(4)
    private val moved = FloatArray(4)

    fun lattice(id: Long): OpalineGelLattice? = lattices[id]

    /**
     * A pointer on a dented part at part-normalised ([x], [y]) of its layout box: the lattice is
     * sized from the recipe's [dimensions] and pressed at the front (z = 1).
     */
    fun touch(
        id: Long,
        pointer: Long,
        pressed: Boolean,
        began: Boolean,
        x: Float,
        y: Float,
        dimensions: OpalineVec3,
    ) {
        if (!pressed) {
            lattices[id]?.release(pointer)
            return
        }
        val lattice =
            lattices.getOrPut(id) {
                OpalineGelLattice(catalogue, dimensions.x, dimensions.y, dimensions.z)
            }
        val px = x * 2 - 1
        val py = 1 - y * 2
        if (began) lattice.press(pointer, px, py, 1f) else lattice.drag(pointer, px, py, 1f)
    }

    /** Specimen contact at [contact] (element space): a film grab, a liquid impulse. */
    fun contact(
        id: Long,
        contact: FloatArray,
        pressed: Boolean,
        began: Boolean,
        minimum: FloatArray,
        maximum: FloatArray,
    ) {
        for (a in 0..2) point[a] = contact[a].toDouble()
        val bubble = bubbles[id]?.specimen
        if (bubble != null) {
            when {
                !pressed -> bubble.releaseGrab()
                began -> bubble.grab(point)
                else -> bubble.moveGrab(point)
            }
        }
        val fluid = fluids[id] ?: return
        if (!began) return
        // workbench.js `impulse(hit.point, 1.2)`, carried from the element's box into the liquid's.
        for (a in 0..2) {
            val f = (contact[a] - minimum[a]) / (maximum[a] - minimum[a])
            point[a] = FLUID_MIN[a] + f * (FLUID_MAX[a] - FLUID_MIN[a])
        }
        fluid.impulse(point, CONTACT_IMPULSE)
    }

    fun cancel(id: Long) {
        lattices[id]?.cancel()
        bubbles[id]?.specimen?.releaseGrab()
    }

    /** The Bubble hero's live film for an F01 part. */
    fun bubble(id: Long): Bubble =
        bubbles.getOrPut(id) {
            val specimen = OpalineBubbleSpecimen()
            val mesh = OpalineDynamicMesh(specimen.film.triangles)
            Bubble(specimen, mesh, OpalineFilmThickness()).also { upload(it) }
        }

    fun fluid(id: Long): OpalineFluidSpecimen = fluids.getOrPut(id) { OpalineFluidSpecimen() }

    /** UI074 `settled → curved-emission-filaments` (`particlePreset: 'G07'`) at [origin]. */
    fun emit(
        id: Long,
        origin: FloatArray,
    ) {
        val system =
            catalogue.createParticlePreset(
                FILAMENTS,
                origin = DoubleArray(3) { origin[it].toDouble() },
            )
        val world = PhysicsWorld(fixedDt = 1.0 / 120, maxSubsteps = 64, maxFrameDt = .5)
        world.add(system)
        val segments = (system.trailLength - 1).coerceAtLeast(0)
        emitters[id] = Emitter(world, system, FloatArray(system.capacity * segments * 6))
    }

    fun update(
        dt: Float,
        reduced: Boolean,
    ) {
        if (reduced) {
            lattices.values.forEach { it.cancel() }
            lattices.clear()
            return
        }
        val elapsed = dt.toDouble()
        lattices.values.forEach { it.update(elapsed) }
        lattices.values.removeAll { it.settled }
        for (bubble in bubbles.values) {
            bubble.specimen.update(elapsed)
            upload(bubble)
        }
        fluids.values.forEach { it.update(elapsed) }
        emitters.values.forEach { it.world.step(elapsed) }
    }

    private fun upload(bubble: Bubble) {
        val film = bubble.specimen.film
        bubble.mesh.upload(film.positions, bubble.specimen.normals)
        bubble.film.update(film.thickness)
    }

    /** Drops the specimens of parts no longer drawn. */
    fun retain(ids: Set<Long>) {
        lattices.keys.retainAll(ids)
        for (id in bubbles.keys - ids) {
            bubbles.remove(id)?.let {
                it.mesh.dispose()
                it.film.release()
            }
        }
        fluids.keys.retainAll(ids)
        emitters.keys.retainAll(ids)
    }

    /**
     * The gel lattice's displacement of one piece: each vertex, carried through the piece's
     * [transform] into element space, samples the lattice at its position normalised to the
     * element box, and the fraction of the half-extents moves it back in piece space.
     */
    fun deform(
        lattice: OpalineGelLattice,
        source: FloatArray,
        indices: IntArray,
        transform: FloatArray,
        minimum: FloatArray,
        maximum: FloatArray,
        out: FloatArray,
        normals: FloatArray,
    ) {
        Matrix.invertM(inverse, 0, transform, 0)
        for (v in source.indices step 3) {
            vector[0] = source[v]
            vector[1] = source[v + 1]
            vector[2] = source[v + 2]
            vector[3] = 1f
            Matrix.multiplyMV(moved, 0, transform, 0, vector, 0)
            for (a in 0..2) moved[a] = (moved[a] - minimum[a]) / (maximum[a] - minimum[a]) * 2 - 1
            lattice.sample(moved[0], moved[1], moved[2], vector, 0)
            for (a in 0..2) vector[a] *= (maximum[a] - minimum[a]) / 2
            vector[3] = 0f
            Matrix.multiplyMV(moved, 0, inverse, 0, vector, 0)
            for (a in 0..2) out[v + a] = source[v + a] + moved[a]
        }
        computeVertexNormals(out, indices, normals)
    }

    /**
     * Every simulated particle of part [id] as an element-space matrix for a unit sphere, with its
     * materials.js family: the E12 liquid ([minimum]..[maximum] holds its box) and G07 filaments.
     */
    fun particles(
        id: Long,
        minimum: FloatArray,
        maximum: FloatArray,
        draw: (FloatArray, String) -> Unit,
    ) {
        fluids[id]?.fluid?.let { fluid ->
            var scale = Double.POSITIVE_INFINITY
            for (a in 0..2) {
                scale = min(scale, (maximum[a] - minimum[a]) / (FLUID_MAX[a] - FLUID_MIN[a]))
            }
            for (i in 0 until fluid.count) {
                Matrix.setIdentityM(matrix, 0)
                for (a in 0..2) {
                    val position = fluid.positions[i * 3 + a]
                    val f = (position - FLUID_MIN[a]) / (FLUID_MAX[a] - FLUID_MIN[a])
                    matrix[12 + a] = (minimum[a] + f * (maximum[a] - minimum[a])).toFloat()
                }
                val radius = (fluid.particleRadius * scale).toFloat()
                Matrix.scaleM(matrix, 0, radius, radius, radius)
                draw(matrix, "water")
            }
        }
        val emitter = emitters[id] ?: return
        val family = particleFamily(requireNotNull(emitter.system.preset))
        for (i in 0 until emitter.system.count) {
            writeParticleMatrix(emitter.system, i, matrix, 0)
            draw(matrix, family)
        }
    }

    /** G07 trail line vertices of part [id] in element space, and their count. */
    fun trails(id: Long): Pair<FloatArray, Int>? {
        val emitter = emitters[id] ?: return null
        if (emitter.trails.isEmpty()) return null
        return emitter.trails to writeTrailSegments(emitter.system, emitter.trails)
    }

    fun dispose() = retain(emptySet())

    private companion object {
        const val FILAMENTS = "G07"

        /** workbench.js pointerdown `root.userData.impulse?.(hit.point, 1.2)`. */
        const val CONTACT_IMPULSE = 1.2

        /** OpalineFluidSpecimen bounds (workbench.js buildFluidSpecimen BoxCollider). */
        val FLUID_MIN = doubleArrayOf(-1.0, -.1, -.85)
        val FLUID_MAX = doubleArrayOf(1.3, 1.5, .85)
    }
}
