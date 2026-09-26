package dev.geode.ui.opaline.transitions

/**
 * `play(id, options)` options of src/transitions.js, same names (docs/TRANSITIONS.md); null means
 * "not given", so each recipe applies its library default. `object`/`objects` are [subject] and
 * [subjects]. A pose source (`from`, `to`, `targets[i]`, `ports.mount`) receives a copy of the
 * pose it replaces and returns the resolved pose: set only the position for a vector, read an
 * object's world pose for an Object3D. Poses are world space; [targetSize] and an L12 [path] are
 * local mesh space, an L18 [path] is world space.
 */
internal data class TransitionOptions(
    val subject: TransitionObject? = null,
    val subjects: List<TransitionObject>? = null,
    val duration: Double = 2.4,
    val from: ((TransitionPose) -> TransitionPose)? = null,
    val to: ((TransitionPose) -> TransitionPose)? = null,
    val targets: List<((TransitionPose) -> TransitionPose)?>? = null,
    val center: TransitionVector? = null,
    val offset: TransitionVector? = null,
    val arcHeight: Double? = null,
    val radius: Double? = null,
    val radialReveal: Boolean = false,
    val startAngle: Double? = null,
    val lift: Double? = null,
    val angle: Double? = null,
    val laneSpacing: Double? = null,
    val stagger: Double? = null,
    val centerObject: TransitionObject? = null,
    val connectionColor: Int? = null,
    val connectionRadius: Double? = null,
    val connectionLights: Boolean = false,
    val spacing: Double? = null,
    val targetGeometry: TransitionGeometry? = null,
    val targetSize: TransitionVector? = null,
    val path: List<TransitionVector>? = null,
    val support: TransitionObject? = null,
    val children: List<TransitionObject> = emptyList(),
    val growth: Double? = null,
    val mass: Double? = null,
    val pigment: Any? = null,
    val material: TransitionMaterial? = null,
    val apertureOffset: TransitionVector? = null,
    val innerRadius: Double? = null,
    val outerRadius: Double? = null,
    val stiffness: Double? = null,
    val drag: Double? = null,
    /** `wind(position, time)`; a constant wind is a lambda returning one vector. */
    val wind: ((TransitionVector, Double) -> TransitionVector)? = null,
    val spin: TransitionVector? = null,
    val floor: Double? = null,
    val restitution: Double? = null,
    val settleTolerance: Double? = null,
    val distance: Double? = null,
    val maxSettleTime: Double? = null,
    /** L18 focal point, read every step (a point, or an object's world position). */
    val lookAt: (() -> TransitionVector)? = null,
    val ports: TransitionPorts? = null,
    val onEvent: ((TransitionEvent) -> Unit)? = null,
    val onUpdate: ((TransitionHandle) -> Unit)? = null,
    val onComplete: ((TransitionHandle) -> Unit)? = null,
)

/** The physics ports (docs/TRANSITIONS.md "Physics ports"). */
internal class TransitionPorts(
    /** Host grants kinematic ownership or switches a constraint target. */
    val acquire: ((TransitionPortEvent) -> Unit)? = null,
    /** Synchronises collider/contact/constraint state every substep. */
    val update: ((TransitionPortEvent) -> Unit)? = null,
    /** Host collision or guide may return a corrected pose (null keeps the authored one). */
    val resolvePose: ((TransitionPortEvent) -> TransitionPose?)? = null,
    /** Returns the object to dynamics with its current momentum. */
    val release: ((TransitionPortEvent) -> Unit)? = null,
    /** The current L02 destination socket, resampled every step. */
    val mount: ((TransitionPose) -> TransitionPose)? = null,
    /** L13: the source removes one fluid packet (mass = `handle.transfer.mass`). */
    val withdraw: ((TransitionHandle) -> Any?)? = null,
    /** L13: the destination accepts that same packet once. */
    val deposit: ((Any?, TransitionHandle) -> Unit)? = null,
)

/** A port callback's argument; [progress], [dt] and [angularVelocity] only where JS sends them. */
internal class TransitionPortEvent(
    val subject: TransitionObject,
    val pose: TransitionPose,
    val velocity: TransitionVector,
    val handle: TransitionHandle,
    val progress: Double? = null,
    val dt: Double? = null,
    val angularVelocity: TransitionVector? = null,
)

/** The L13 packet `{mass, pigment}` used when the host supplies no `withdraw` port. */
internal data class TransitionPacket(
    val mass: Double,
    val pigment: Any?,
)
