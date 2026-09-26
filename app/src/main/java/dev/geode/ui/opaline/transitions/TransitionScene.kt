package dev.geode.ui.opaline.transitions

/**
 * One persistent object a transition moves (the `Object3D` src/transitions.js works on): a part,
 * a piece of a part, or the camera for L18. The renderer implements it; poses are world space in
 * workbench metres. Implementations keep identity equality: per-object motion, the L01 mount and
 * the L09 fan origin are remembered in weak maps keyed by the object.
 */
internal interface TransitionObject {
    /** `worldPose(object)` into [out]. */
    fun readWorldPose(out: TransitionPose)

    /** `setWorldPose(object, pose)`; the system has already refused a non-finite pose. */
    fun writeWorldPose(pose: TransitionPose)

    /** `objectPieces`: direct mesh/group children; L03, L09, L10, L16, L17 split into them. */
    val pieces: List<TransitionObject>
        get() = emptyList()

    /** `meshList(object)`: every mesh in the subtree, in traversal order. */
    val meshes: List<TransitionMesh>
        get() = emptyList()

    /** `userData.transitionRadius`: the L16/L17 contact sphere radius. */
    val transitionRadius: Double?
        get() = null
}

/**
 * One mesh of a [TransitionObject]. Deforming recipes (L04–L08, L11, L12, L14) install their own
 * clone into [geometry] before editing it, so geometry shared with other parts is never touched.
 */
internal interface TransitionMesh {
    /** `mesh.geometry`, local space. */
    var geometry: TransitionGeometry

    /** `mesh.material.color` as 0xRRGGBB (sRGB, `Color.getHex`), null without a single colour. */
    val color: Int?

    /** materials.js `userData.setInteraction(point, strength)`: local point, strength 0..4. */
    fun setInteraction(
        point: TransitionVector,
        strength: Double,
    )
}

/**
 * A mesh the system generates and draws through the renderer until [TransitionSystem.dispose]:
 * the L15 aperture, the L03 connection tubes and their light heads. [name] is the library's.
 */
internal class TransitionAuxiliary(
    val name: String,
    val geometry: TransitionGeometry,
    val material: TransitionMaterial,
    val light: TransitionPointLight? = null,
) {
    /** World pose; the identity (the scene root's frame) until the system places it. */
    val worldPose = TransitionPose()
}

/** The `MeshPhysicalMaterial` parameters the library sets; the rest keep r186 defaults. */
internal class TransitionMaterial(
    val color: Int,
    val roughness: Double,
    val transmission: Double,
    val thickness: Double,
    val ior: Double = 1.5,
    val clearcoat: Double = 0.0,
    val emissive: Int = 0x000000,
    var emissiveIntensity: Double = 1.0,
)

/** `new PointLight(color, intensity, distance, decay)` riding on an L03 light head. */
internal class TransitionPointLight(
    val color: Int,
    var intensity: Double,
    val distance: Double,
    val decay: Double,
)
