package dev.geode.ui.opaline

import android.opengl.Matrix
import dev.geode.ui.opaline.transitions.TransitionAuxiliary
import dev.geode.ui.opaline.transitions.TransitionGeometry
import dev.geode.ui.opaline.transitions.TransitionHandle
import dev.geode.ui.opaline.transitions.TransitionMesh
import dev.geode.ui.opaline.transitions.TransitionObject
import dev.geode.ui.opaline.transitions.TransitionOptions
import dev.geode.ui.opaline.transitions.TransitionPose
import dev.geode.ui.opaline.transitions.TransitionStatus
import dev.geode.ui.opaline.transitions.TransitionSystem
import dev.geode.ui.opaline.transitions.TransitionVector

/**
 * The renderer's side of src/transitions.js (plan E2). Every part and each of its pieces is a
 * [TransitionObject] in realm metres, every piece a [TransitionMesh], and the realm camera is the
 * L18 subject. Behaviour actions play their recipes; the pose a recipe writes becomes a delta on
 * the part's view-space model; deformed clones and generated meshes draw from their own buffers.
 * EGL thread only.
 */
internal class OpalineTransitionBridge(
    private val realm: OpalineRealm,
) {
    /** A part or piece: its rest pose this frame and the pose a transition last wrote. */
    class Subject(
        override val meshes: List<Piece> = emptyList(),
        override val pieces: List<Subject> = emptyList(),
    ) : TransitionObject {
        val rest = TransitionPose()
        private val written = TransitionPose()
        private val restAtWrite = TransitionPose()
        private val shifted = TransitionPose()
        private val local = FloatArray(16)
        private val step = FloatArray(16)
        var moved = false
            private set

        override fun readWorldPose(out: TransitionPose) {
            out.copy(if (moved) shift() else rest)
        }

        override fun writeWorldPose(pose: TransitionPose) {
            written.copy(pose)
            restAtWrite.copy(rest)
            moved = true
        }

        /** The written pose, carried along as the layout moves the rest pose. */
        private fun shift(): TransitionPose {
            shifted.copy(written)
            shifted.position.add(rest.position).sub(restAtWrite.position)
            return shifted
        }

        /**
         * Premultiplies [model] (view space) by the change from rest to the written pose:
         * `view × T(pivot) × writeDelta × T(−pivot) × view⁻¹`, one view unit per realm metre.
         */
        fun apply(
            model: FloatArray,
            offset: Int,
            view: FloatArray,
            inverseView: FloatArray,
        ) {
            if (!moved) return
            shift().writeDelta(rest, rest.position, 1f, local)
            val p = rest.position
            Matrix.setIdentityM(step, 0)
            Matrix.translateM(step, 0, p.x.toFloat(), p.y.toFloat(), p.z.toFloat())
            Matrix.multiplyMM(step, 0, step.copyOf(), 0, local, 0)
            Matrix.translateM(step, 0, -p.x.toFloat(), -p.y.toFloat(), -p.z.toFloat())
            Matrix.multiplyMM(local, 0, view, 0, step, 0)
            Matrix.multiplyMM(step, 0, local, 0, inverseView, 0)
            Matrix.multiplyMM(local, 0, step, 0, model, offset)
            local.copyInto(model, offset)
        }
    }

    /** One piece: the shared rest geometry until a deforming recipe installs its own clone. */
    class Piece(
        val base: TransitionGeometry,
    ) : TransitionMesh {
        override var geometry = base
        override val color: Int? = null
        val touch = FloatArray(4)
        var strength = 0f
            private set
        var mesh: OpalineDynamicMesh? = null

        override fun setInteraction(
            point: TransitionVector,
            strength: Double,
        ) {
            touch[0] = point.x.toFloat()
            touch[1] = point.y.toFloat()
            touch[2] = point.z.toFloat()
            this.strength = strength.coerceIn(0.0, MAX_EXCITATION).toFloat()
        }
    }

    private val camera =
        object : TransitionObject {
            private val world = FloatArray(16)
            private val view = FloatArray(16)

            override fun readWorldPose(out: TransitionPose) {
                // The camera's world matrix: columns +X, +Y, +Z (looking down −Z), then position.
                val eye = column(3)
                val target = eye.clone().sub(column(2))
                out.position.copy(eye)
                out.quaternion.setFromLookAt(eye, target, column(1))
                out.scale.set(1.0, 1.0, 1.0)
            }

            override fun writeWorldPose(pose: TransitionPose) {
                pose.writeMatrix(world)
                Matrix.invertM(view, 0, world, 0)
                realm.look(view)
            }

            private fun column(index: Int): TransitionVector {
                val m = realm.inverseView
                val i = index * 4
                return TransitionVector(m[i].toDouble(), m[i + 1].toDouble(), m[i + 2].toDouble())
            }
        }

    /** The workbench shared water consumes the L03 radial stages (docs/TRANSITIONS.md). */
    private val system =
        TransitionSystem(camera) { event ->
            val center = event.center
            if (center != null && event.type.removePrefix("transition:") in STAGES) {
                realm.touch(center.x.toFloat(), center.z.toFloat(), 1f)
            }
        }
    private val subjects = HashMap<Long, Subject>()
    private val handles = HashMap<OpalinePartEvents, Pair<Long, MutableList<TransitionHandle>>>()
    private val generated = HashMap<TransitionAuxiliary, OpalineDynamicMesh>()

    /** The part [id]'s subject, one mesh and one piece subject per piece. */
    fun subject(
        id: Long,
        bases: List<TransitionGeometry>,
    ): Subject = subjects.getOrPut(id) { Subject(bases.map { Piece(it) }, bases.map { Subject() }) }

    /** Plays [action]'s recipe on [subject] for [events]; false when the action has none. */
    fun play(
        action: String,
        subject: Subject,
        events: OpalinePartEvents?,
        reduced: Boolean,
    ): Boolean {
        val handle = system.playAction(action, TransitionOptions(subject = subject)) ?: return false
        if (events != null) {
            val current = handles[events]?.takeIf { it.first == events.serial }
            val list = current?.second ?: mutableListOf<TransitionHandle>().also {
                handles[events] = events.serial to it
            }
            list += handle
        }
        finish(handle, reduced)
        return true
    }

    /** Reduced motion: a transition completes at once in its settled pose (AI-HANDOFF). */
    private fun finish(
        handle: TransitionHandle,
        reduced: Boolean,
    ) {
        if (reduced) system.update(handle.duration)
    }

    /** Whether every transition [events] started for its latest raise has left RUNNING. */
    fun settled(events: OpalinePartEvents): Boolean {
        val current = handles[events]?.takeIf { it.first == events.serial } ?: return true
        return current.second.none { it.status == TransitionStatus.RUNNING }
    }

    fun update(dt: Float) = system.update(dt.toDouble())

    /** Drops the subjects (and buffers) of parts no longer drawn. */
    fun retain(ids: Set<Long>) {
        val gone = subjects.keys - ids
        for (id in gone) subjects.remove(id)?.let { dispose(it) }
        handles.keys.retainAll { events -> events.parts > 0 }
    }

    /** Every generated mesh (L03 tubes and heads, L15 aperture) with its current buffers. */
    fun generated(): Map<TransitionAuxiliary, OpalineDynamicMesh> {
        val live = system.generated.toSet()
        for (gone in generated.keys - live) generated.remove(gone)?.dispose()
        for (auxiliary in live) {
            val geometry = auxiliary.geometry
            val mesh =
                generated.getOrPut(auxiliary) {
                    OpalineDynamicMesh(geometry.indices ?: IntArray(geometry.count) { it })
                }
            if (mesh.version != geometry.version) {
                mesh.upload(geometry.positions, geometry.normals)
                mesh.version = geometry.version
            }
        }
        return generated
    }

    fun dispose() {
        system.dispose()
        subjects.values.forEach { dispose(it) }
        generated.values.forEach { it.dispose() }
    }

    private fun dispose(subject: Subject) = subject.meshes.forEach { it.mesh?.dispose() }

    private companion object {
        /** materials.js `setInteraction` clamps excitation to [0, 4]. */
        const val MAX_EXCITATION = 4.0
        val STAGES =
            setOf("awakening", "connection-front", "radial-lift", "socket-approach", "aftermath")
    }
}
