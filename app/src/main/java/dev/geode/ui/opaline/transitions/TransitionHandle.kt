package dev.geode.ui.opaline.transitions

/** A handle's `status`. */
internal enum class TransitionStatus { RUNNING, COMPLETED, CANCELLED, BLOCKED }

/** The L13 ledger `state`: `source`, `in-transit`, `destination`. */
internal enum class TransferState { SOURCE, IN_TRANSIT, DESTINATION }

/**
 * The handle `play` returns. It stays [TransitionStatus.RUNNING] until completed, cancelled or
 * blocked; [TransitionOptions.onComplete] and the `transition:*` events signal the end.
 */
internal class TransitionHandle(
    val key: Int,
    val id: String,
    val name: String,
    val duration: Double,
    val options: TransitionOptions,
    private val system: TransitionSystem,
) {
    var status = TransitionStatus.RUNNING
    var progress = 0.0
    var elapsed = 0.0
    var center = TransitionVector()
    val entries = mutableListOf<TransitionEntry>()

    /** Generated meshes this transition added (aperture, connection tubes, light heads). */
    val auxiliary = mutableListOf<TransitionAuxiliary>()
    var transfer: TransitionTransfer? = null
    val stages = mutableSetOf<String>()
    val connections = mutableListOf<TransitionConnection>()
    var aperture: TransitionAuxiliary? = null
    val geometry = mutableListOf<TransitionGeometryState>()
    var branchPath: TransitionCurve? = null
    var cameraPath: TransitionCurve? = null
    var children: List<Pair<TransitionObject, TransitionPose>> = emptyList()

    fun cancel(preserveVelocity: Boolean = true): Boolean = system.cancel(this, preserveVelocity)
}

/** One moved object of a handle: its start, end and the retained first derivatives. */
internal class TransitionEntry(
    val subject: TransitionObject,
    val index: Int,
    val start: TransitionPose,
    val radius: Double,
    velocity: TransitionVector,
    angularVelocity: TransitionVector,
    scaleVelocity: TransitionVector,
) {
    var end = start.clone()
    val last = start.clone()
    val velocity = velocity.clone()
    val initialVelocity = velocity.clone()
    val angularVelocity = angularVelocity.clone()
    val initialAngularVelocity = angularVelocity.clone()
    val scaleVelocity = scaleVelocity.clone()
    val initialScaleVelocity = scaleVelocity.clone()
}

/**
 * `handle.transfer`. [accountedByHost] is false unless both `withdraw` and `deposit` ports are
 * given; a cancelled in-transit transfer stays on its handle, never refunded or duplicated.
 */
internal class TransitionTransfer(
    val mass: Double,
    val accountedByHost: Boolean,
) {
    var state = TransferState.SOURCE
    var packet: Any? = null
}

/**
 * An emitted event. [type] is the library's: `transition:start`, `…:complete`, `…:cancel`,
 * `…:blocked`; L13 `…:detach`, `…:deposit`; L03 radial reveal (with [center])
 * `…:awakening`, `…:connection-front`, `…:radial-lift`, `…:socket-approach`, `…:aftermath`.
 */
internal class TransitionEvent(
    val type: String,
    val handle: TransitionHandle,
    val time: Double,
    val center: TransitionVector? = null,
    val transfer: TransitionTransfer? = null,
    val reason: String? = null,
    val preserveVelocity: Boolean? = null,
)

/** One L03 radial connection: its curve, tube and travelling light head. */
internal class TransitionConnection(
    val curve: TransitionCurve,
    val tube: TransitionAuxiliary,
    val head: TransitionAuxiliary,
    val index: Int,
)

/** `_geometryState`: the installed clone, the exact vertex snapshot and its bounds. */
internal class TransitionGeometryState(
    val mesh: TransitionMesh,
    val geometry: TransitionGeometry,
    val base: FloatArray,
    val center: TransitionVector,
    val half: TransitionVector,
)
