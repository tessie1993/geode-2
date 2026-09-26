package dev.geode.ui.opaline

import android.content.res.AssetManager
import android.opengl.Matrix
import androidx.compose.ui.geometry.Rect
import dev.geode.ui.opaline.optics.OpalinePostChain
import dev.geode.ui.opaline.physics.OpalineGelLattice
import dev.geode.ui.opaline.transitions.TransitionGeometry
import dev.geode.ui.opaline.transitions.TransitionMaterial
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tan
import android.opengl.GLES30 as GL

/**
 * One catalogue body on screen: [element] fitted to [bounds]. A recipe part carries its
 * compositions.json [dimensions], [rotation], [scale] and selector [material]; a single element
 * has no [dimensions] (its depth follows its authored bounds) and may have no selector.
 */
internal data class OpalinePart(
    val id: Long,
    val element: String,
    val material: String?,
    val dimensions: OpalineVec3?,
    val rotation: OpalineVec3,
    val scale: OpalineVec3,
    /** The recipe part's `position.z`: its depth within the composition, in metres. */
    val z: Float,
    val bounds: Rect,
    val clip: Rect,
    val value: Float,
    val selected: Boolean,
    val enabled: Boolean,
    val depth: Int,
    val secondaryValue: Float = value,
    /** The recipe part id [events] target. */
    val name: String = element,
    val events: OpalinePartEvents? = null,
)

internal data class OpalineFrame(
    val parts: List<OpalinePart> = emptyList(),
    val width: Float = 1f,
    val height: Float = 1f,
    val reducedMotion: Boolean = false,
    val active: Boolean = true,
    val palette: OpalinePalette = OpalinePalette.TIDAL,
    val dim: Float = 0f,
    val motion: Float = 1f,
    val environment: Boolean = true,
    val transparent: Boolean = false,
    /** Window pixels per dp. */
    val density: Float = 1f,
)

/**
 * One GLES 3 scene per Android window. All GPU objects are confined to the EGL thread.
 *
 * Frame: sun shadows (a per-part atlas; the static realm map once) → the realm Reflector → the
 * transmission receiver → background, opaque realm, water and transmissive realm, mist → UI parts
 * → output. The realm draws only in environment hosts that are not transparent; every other host
 * clears to transparent and draws its parts, whose glass refracts the realm's distant plate.
 */
internal class OpalineRenderer(
    private val assets: AssetManager,
) {
    /**
     * One part's interaction state. [pressure] is src/motion.js MotionController's
     * `pressure = new Spring(0, 4, .68)`; [coordinate] and [coordinate2] are its `value` and
     * `value2 = new Spring(.5, 4, .8)`, started at the host's value rather than the .5 rest.
     */
    private class Instance(
        value: Float,
        secondaryValue: Float,
    ) {
        val pressure = OpalineSpring(0f, frequency = 4f, damping = .68f)
        val coordinate = OpalineSpring(value, frequency = 4f, damping = .8f)
        val coordinate2 = OpalineSpring(secondaryValue, frequency = 4f, damping = .8f)
        val selection = OpalineSpring()
        var touchX = 0.5f
        var touchY = 0.5f

        /** MotionController.drag `velocity`: the last pointer delta in host-view fractions. */
        var velocityX = 0f
        var velocityY = 0f
        val pointers = mutableSetOf<Long>()

        /** MotionController `contact`: the touch point in the element root's authored space. */
        val contact = floatArrayOf(0f, 0f, 0f, 1f)

        /** View depth of the camera-facing drag plane through the first contact. */
        var dragDepth = 0f
        var mesh: GpuMesh? = null
        var bounds = Rect.Zero
        val model = FloatArray(16)
        var metresToWorld = 1f
        var poses = FloatArray(0)
        var shadowed = false
        val shadowMatrix = FloatArray(16)
        var events = 0L
        var selected = false
        var subject: OpalineTransitionBridge.Subject? = null

        /** Recipe dimensions (or the authored size) and whether any piece takes the dent. */
        var dimensions = OpalineVec3(1f, 1f, 1f)
        var dents = false

        /** A hero medium: N07, F01, or a part that played UI074's liquid emergence. */
        var volume = false
    }

    private val catalogue = OpalineCatalogue.get(assets)
    private val meshes = mutableMapOf<String, GpuMesh>()
    private val instances = mutableMapOf<Long, Instance>()
    private val materials = mutableMapOf<Pair<OpalinePalette, String>, SurfaceMaterial>()
    private val settling = mutableMapOf<OpalinePartEvents, Boolean>()
    private lateinit var surface: OpalineProgram
    private lateinit var realm: OpalineRealm

    /**
     * The workbench post chain (bloom → OutputPass) where half-float targets render: the scene is
     * then linear HDR and the chain tone maps it once. Without it every pass tone maps itself and
     * the scene is blitted to the window.
     */
    private var post: OpalinePostChain? = null
    private lateinit var transitions: OpalineTransitionBridge
    private lateinit var physics: OpalinePhysicsBridge
    private lateinit var optics: OpalineOpticsBridge
    private var reduced = false
    private var dfg = 0
    private var environment = 0
    private var atlas = 0
    private var atlasFramebuffer = 0
    private var linearFormat = GL.GL_SRGB8_ALPHA8
    private var sceneFormat = GL.GL_RGBA8
    private var maxSize = 1
    private var palette = OpalinePalette.TIDAL
    private var themed = false
    private var realmShadow = false
    private var world = false
    private lateinit var targets: SceneTargets
    private var width = 0
    private var height = 0
    private var frameWidth = 1f
    private var frameHeight = 1f
    private var time = 0f
    private var motion = 1f
    private var frames = 0L
    private val projection = FloatArray(16)
    private val pose = FloatArray(16)
    private val motionInput = OpalineMotionInput()
    private val pieceModel = FloatArray(16)
    private val modelView = FloatArray(16)
    private val fromView = FloatArray(16)
    private val inverse = FloatArray(16)
    private val normal = FloatArray(9)
    private val lightView = FloatArray(16)
    private val lightFromView = FloatArray(16)
    private val lightProjection = FloatArray(16)
    private val tile = FloatArray(16)
    private val point = FloatArray(4)
    private val moved = FloatArray(4)
    private val ray = FloatArray(4)
    private val velocity = FloatArray(3)
    private val localContact = FloatArray(4)
    private val center = FloatArray(4)
    private val realmPoints = FloatArray(6)
    private val pointPositions = FloatArray(6)
    private val pointColors = FloatArray(6)
    private val pointDistances = FloatArray(2)
    private val directions = FloatArray(6)
    private val up = FloatArray(3)

    fun create() {
        val extensions = GL.glGetString(GL.GL_EXTENSIONS).orEmpty()
        val halfFloat = HALF_FLOAT_TARGETS.any { it in extensions }
        linearFormat = if (halfFloat) GL.GL_RGBA16F else GL.GL_SRGB8_ALPHA8
        sceneFormat = if (halfFloat) GL.GL_RGBA16F else GL.GL_RGBA8
        val limits = IntArray(2)
        GL.glGetIntegerv(GL.GL_MAX_TEXTURE_SIZE, limits, 0)
        GL.glGetIntegerv(GL.GL_MAX_RENDERBUFFER_SIZE, limits, 1)
        maxSize = min(limits[0], limits[1])
        targets = SceneTargets(linearFormat, sceneFormat)
        surface = OpalineProgram(assets, "surface")
        realm = OpalineRealm(assets, linearFormat)
        dfg = OpalineLightingTextures.dfg()
        environment = OpalineLightingTextures.environment()
        atlas = OpalineRealm.depthTexture(OpalineLightRig.SHADOW_MAP_SIZE)
        atlasFramebuffer = OpalineRealm.framebuffer(depth = atlas)
        if (halfFloat) post = OpalinePostChain(assets)
        transitions = OpalineTransitionBridge(realm)
        physics = OpalinePhysicsBridge(assets)
        optics = OpalineOpticsBridge(assets)
        GL.glEnable(GL.GL_DEPTH_TEST)
        GL.glDepthFunc(GL.GL_LEQUAL)
    }

    fun touch(
        id: Long,
        pointer: Long,
        pressed: Boolean,
        x: Float,
        y: Float,
    ) {
        val instance = instances[id] ?: return
        val began = pressed && pointer !in instance.pointers
        if (pressed) instance.pointers.add(pointer) else instance.pointers.remove(pointer)
        instance.pressure.target = if (instance.pointers.isEmpty()) 0f else 1f
        val nextX = x.coerceIn(0f, 1f)
        val nextY = y.coerceIn(0f, 1f)
        val dragging = pressed && !began
        val bounds = instance.bounds
        // MotionController.drag `velocity.set(delta.x, delta.y, 0)`; release clears it.
        instance.velocityX =
            if (dragging) (nextX - instance.touchX) * bounds.width / frameWidth else 0f
        instance.velocityY =
            if (dragging) (nextY - instance.touchY) * bounds.height / frameHeight else 0f
        instance.touchX = nextX
        instance.touchY = nextY
        if (pressed) contact(instance, began)
        val mesh = instance.mesh ?: return
        if (instance.dents && !reduced) {
            physics.touch(id, pointer, pressed, began, nextX, nextY, instance.dimensions)
        }
        physics.contact(id, instance.contact, pressed, began, mesh.minimum, mesh.maximum)
    }

    fun cancel(id: Long? = null) {
        if (id != null) physics.cancel(id) else instances.keys.forEach { physics.cancel(it) }
        (if (id == null) instances.values else listOfNotNull(instances[id])).forEach {
            it.pointers.clear()
            it.pressure.target = 0f
            it.velocityX = 0f
            it.velocityY = 0f
        }
    }

    fun render(
        frame: OpalineFrame,
        targetWidth: Int,
        targetHeight: Int,
        dt: Float,
    ) {
        if (!frame.reducedMotion) time += dt * frame.motion
        motion = frame.motion
        reduced = frame.reducedMotion
        frames++
        frameWidth = frame.width
        frameHeight = frame.height
        world = frame.environment && !frame.transparent
        resize(frame)
        if (!themed || palette != frame.palette) {
            realm.theme(frame.palette)
            palette = frame.palette
            themed = true
        }
        Matrix.perspectiveM(projection, 0, FOV, width.toFloat() / height, NEAR, FAR)
        realm.update(time, !frame.reducedMotion, frames)
        // Parents first, children last.
        val order = compareBy<OpalinePart> { it.depth }.thenByDescending { it.bounds.area }
        val parts =
            (if (world) decorations(catalogue, frame, time) else emptyList()) +
                frame.parts.filter { it.drawable }.sortedWith(order)
        val ids = parts.mapTo(hashSetOf()) { it.id }
        instances.keys.retainAll(ids)
        transitions.retain(ids)
        physics.retain(ids)
        optics.retain(ids)
        if (!reduced) transitions.update(dt * frame.motion)
        physics.update(dt * frame.motion, reduced)
        settling.clear()
        for (part in parts) update(part, frame, dt)
        for ((events, settled) in settling) if (settled) events.settled = events.serial
        if (world) lens(parts)
        GL.glDisable(GL.GL_SCISSOR_TEST)
        GL.glEnable(GL.GL_DEPTH_TEST)
        GL.glDepthMask(true)
        shadows(parts)
        if (world) reflection(parts, frame)
        // The transmission receiver: the opaque scene behind every surface in linear light,
        // mip-mapped so rough refraction reads a wider footprint, as three.js prepares it.
        target(targets.receiverFramebuffer)
        opaqueRealm(realm.view, projection, true, world, frame)
        GL.glBindTexture(GL.GL_TEXTURE_2D, targets.receiver)
        GL.glGenerateMipmap(GL.GL_TEXTURE_2D)
        val chain = post
        val linear = chain != null
        target(targets.sceneFramebuffer)
        if (world) {
            opaqueRealm(realm.view, projection, linear, true, frame)
            transmissiveRealm(realm.view, projection, targets.receiver, width, height, linear, frame)
        }
        drawParts(parts, frame, realm.view, projection, null, targets.receiver, width, height, linear)
        effects(parts, linear)
        GL.glBindFramebuffer(GL.GL_FRAMEBUFFER, 0)
        GL.glViewport(0, 0, targetWidth, targetHeight)
        GL.glDisable(GL.GL_CULL_FACE)
        if (chain != null) {
            chain.render(targets.scene, width, height)
        } else {
            GL.glBindFramebuffer(GL.GL_READ_FRAMEBUFFER, targets.sceneFramebuffer)
            GL.glBlitFramebuffer(
                0,
                0,
                width,
                height,
                0,
                0,
                targetWidth,
                targetHeight,
                GL.GL_COLOR_BUFFER_BIT,
                GL.GL_LINEAR,
            )
            GL.glBindFramebuffer(GL.GL_FRAMEBUFFER, 0)
        }
        check(GL.glGetError() == GL.GL_NO_ERROR) { "Opaline native draw failed" }
    }

    private val Rect.area: Float get() = width * height

    /** A part reaches the GPU only with a measurable body and a visible clip. */
    private val OpalinePart.drawable: Boolean
        get() = bounds.width > 1f && bounds.height > 1f && !clip.isEmpty

    /** Springs, behaviour events and layout for one part this frame. */
    private fun update(
        part: OpalinePart,
        frame: OpalineFrame,
        dt: Float,
    ) {
        val decorative = part.id < 0
        val instance = instances.getOrPut(part.id) { Instance(part.value, part.secondaryValue) }
        instance.coordinate.target = part.value
        instance.coordinate2.target = part.secondaryValue
        instance.selection.target = if (part.selected) 1f else 0f
        if (frame.reducedMotion) {
            instance.pressure.reset(0f)
            instance.coordinate.reset()
            instance.coordinate2.reset()
            instance.selection.reset()
        } else {
            instance.pressure.step(dt)
            instance.coordinate.step(dt)
            instance.coordinate2.step(dt)
            instance.selection.step(dt)
        }
        layout(part, instance, frame, decorative)
        val subject = instance.subject ?: return
        val events = part.events
        if (events != null && instance.events != events.serial) {
            instance.events = events.serial
            act(events.actions[part.name], part, instance, events)
        }
        // The catalogue binds no deselect: a part that loses the selection returns to its mount.
        if (instance.selected && !part.selected && subject.moved) {
            transitions.play("return-to-mount", subject, null, reduced)
        }
        instance.selected = part.selected
        if (events != null) settling[events] = transitions.settled(events)
    }

    /**
     * A raised behaviour's action on this part: rings on the realm water, G07 filaments, the
     * UI074 medium, or the action's transition recipe.
     */
    private fun act(
        action: String?,
        part: OpalinePart,
        instance: Instance,
        events: OpalinePartEvents,
    ) {
        val mesh = instance.mesh ?: return
        when (action) {
            null -> Unit
            "water-ring", "origin-rings" -> ripple(instance.model, 12)
            "curved-emission-filaments" -> physics.emit(part.id, boxCenter(mesh))
            "liquid-emerge" -> instance.volume = true
            else -> transitions.play(action, requireNotNull(instance.subject), events, reduced)
        }
    }

    private fun boxCenter(mesh: GpuMesh) = FloatArray(3) { (mesh.minimum[it] + mesh.maximum[it]) / 2 }

    /**
     * Fits one element as geometry.js createElement fits `dimensions`
     * (`group.scale.multiply(dimensions / size)`): its x and y extents become the layout width
     * and height, and its z extent is `dimensions.z` at metresToWorld, the mean of the x and y
     * fits in view units per catalogue metre. A single element keeps its authored depth at the
     * smaller of the x and y fits. The recipe's rotation (Euler XYZ) and scale follow the fit, as
     * instantiate-composition.js applies them; then every piece takes its motion.js pose.
     */
    private fun layout(
        part: OpalinePart,
        instance: Instance,
        frame: OpalineFrame,
        decorative: Boolean,
    ) {
        val mesh = meshes.getOrPut(part.element) { upload(part.element, read(part.element)) }
        instance.mesh = mesh
        instance.bounds = part.bounds
        val minimum = mesh.minimum
        val maximum = mesh.maximum
        // createElement: `v / Math.max(size.getComponent(i), .001)`.
        val sizeX = (maximum[0] - minimum[0]).coerceAtLeast(.001f)
        val sizeY = (maximum[1] - minimum[1]).coerceAtLeast(.001f)
        val sizeZ = (maximum[2] - minimum[2]).coerceAtLeast(.001f)
        val distance = UI_DISTANCE + if (decorative) DECORATION_DEPTH else 0f
        val perPixel = 2f * distance * tan(FOV_RADIANS / 2f) / frame.height
        val bounds = part.bounds
        val extentX = bounds.width * perPixel
        val extentY = bounds.height * perPixel
        val fitX = extentX / sizeX
        val fitY = extentY / sizeY
        val dimensions = part.dimensions
        val metresToWorld =
            if (dimensions == null) {
                min(fitX, fitY)
            } else {
                (extentX / dimensions.x + extentY / dimensions.y) / 2f
            }
        val fitZ = if (dimensions == null) metresToWorld else dimensions.z * metresToWorld / sizeZ
        val press = instance.pressure.value * PRESS_DEPTH
        val model = instance.model
        Matrix.setIdentityM(model, 0)
        Matrix.translateM(
            model,
            0,
            (bounds.center.x - frame.width / 2) * perPixel,
            (frame.height / 2 - bounds.center.y) * perPixel,
            -distance + instance.selection.value * .10f + (part.z - press) * metresToWorld,
        )
        // Front plane remains aligned; controlled tilt reveals curved rims and undersides.
        val tilt =
            if (decorative) {
                -16f
            } else if (part.element.startsWith("C")) {
                -3f
            } else {
                -8f
            }
        Matrix.rotateM(model, 0, tilt, 1f, 0f, 0f)
        val yaw = if (decorative) 18f else (instance.touchX - .5f) * instance.pressure.value * 3f
        Matrix.rotateM(model, 0, yaw, 0f, 1f, 0f)
        val rotation = part.rotation
        Matrix.rotateM(model, 0, Math.toDegrees(rotation.x.toDouble()).toFloat(), 1f, 0f, 0f)
        Matrix.rotateM(model, 0, Math.toDegrees(rotation.y.toDouble()).toFloat(), 0f, 1f, 0f)
        Matrix.rotateM(model, 0, Math.toDegrees(rotation.z.toDouble()).toFloat(), 0f, 0f, 1f)
        Matrix.scaleM(model, 0, fitX * part.scale.x, fitY * part.scale.y, fitZ * part.scale.z)
        Matrix.translateM(
            model,
            0,
            -(minimum[0] + maximum[0]) / 2,
            -(minimum[1] + maximum[1]) / 2,
            -(minimum[2] + maximum[2]) / 2,
        )
        instance.metresToWorld = metresToWorld
        instance.dimensions = dimensions ?: OpalineVec3(sizeX, sizeY, sizeZ)
        instance.dents = mesh.pieces.any { dent(part, it.source) }
        val subject = transitions.subject(part.id, mesh.pieces.map { it.base })
        instance.subject = subject
        realmCenterOf(model, 12)
        subject.rest.position.set(center[0].toDouble(), center[1].toDouble(), center[2].toDouble())
        subject.apply(model, 0, realm.view, realm.inverseView)
        if (instance.poses.size != mesh.pieces.size * 16) {
            instance.poses = FloatArray(mesh.pieces.size * 16)
        }
        motionInput.secondaryValue = instance.coordinate2.value
        motionInput.pressure = instance.pressure.value * frame.motion
        motionInput.time = time
        motionInput.reducedMotion = frame.reducedMotion
        for ((index, piece) in mesh.pieces.withIndex()) {
            val coordinate = coordinate(instance, piece.source)
            // motion.js reads the value spring unclamped: thumbs and dials overshoot and settle.
            motionInput.value = coordinate.value
            motionInput.velocity = coordinate.velocity
            OpalinePieceMotion.pose(piece.source, motionInput, pose)
            pose.copyInto(instance.poses, index * 16)
            pieceTransition(instance, subject.pieces[index], index)
        }
    }

    /** A piece's own transition (L03, L09, L10, L16, L17 split a part into its pieces). */
    private fun pieceTransition(
        instance: Instance,
        piece: OpalineTransitionBridge.Subject,
        index: Int,
    ) {
        pieceModel(instance, index)
        realmCenterOf(pieceModel, 12)
        piece.rest.position.set(center[0].toDouble(), center[1].toDouble(), center[2].toDouble())
        if (!piece.moved) return
        piece.apply(pieceModel, 0, realm.view, realm.inverseView)
        Matrix.invertM(inverse, 0, instance.model, 0)
        Matrix.multiplyMM(instance.poses, index * 16, inverse, 0, pieceModel, 0)
    }

    /** [pieceModel] = the part's model × piece [index]'s pose. */
    private fun pieceModel(
        instance: Instance,
        index: Int,
    ) = Matrix.multiplyMM(pieceModel, 0, instance.model, 0, instance.poses, index * 16)

    /**
     * The sun's shadow per part (plan D3): each part is lit in its own element space, so its
     * map is the workbench `sun.shadow` (2048², ±11, near .5, far 35) in metres around the
     * element, scaled by metresToWorld like the rig. Only the tile the part's footprint covers is
     * rendered, at the map's own texel density, into one shared atlas.
     */
    private fun shadows(parts: List<OpalinePart>) {
        val size = OpalineLightRig.SHADOW_MAP_SIZE
        surface.use()
        surface.flag("uDepthOnly", true)
        neutral()
        if (world && !realmShadow) {
            realm.bindShadow()
            surface.matrix("uProjection", realm.shadowProjection)
            for (body in realm.stones + realm.nacre) {
                Matrix.multiplyMM(modelView, 0, realm.shadowView, 0, body.model, 0)
                setModel(modelView)
                cull(body.model, doubleSided = false, shadow = true)
                body.geometry.draw()
            }
            realmShadow = true
        }
        GL.glBindFramebuffer(GL.GL_FRAMEBUFFER, atlasFramebuffer)
        GL.glViewport(0, 0, size, size)
        GL.glClear(GL.GL_DEPTH_BUFFER_BIT)
        var x = 0
        var y = 0
        var row = 0
        for (part in parts.filter { instances.getValue(it.id).mesh != null }) {
            val instance = instances.getValue(part.id)
            val mesh = requireNotNull(instance.mesh)
            instance.shadowed = false
            val scale = instance.metresToWorld
            realmCenter(instance)
            val sun = OpalineLightRig.sunPosition
            Matrix.setLookAtM(
                lightView,
                0,
                center[0] + sun[0] * scale,
                center[1] + sun[1] * scale,
                center[2] + sun[2] * scale,
                center[0],
                center[1],
                center[2],
                0f,
                1f,
                0f,
            )
            Matrix.multiplyMM(lightFromView, 0, lightView, 0, realm.inverseView, 0)
            val footprint = footprint(mesh, instance)
            val texel = 2f * OpalineLightRig.SHADOW_EXTENT * scale / size
            val w = ceil((footprint[2] - footprint[0]) / texel).toInt() + 2 * SHADOW_MARGIN
            val h = ceil((footprint[3] - footprint[1]) / texel).toInt() + 2 * SHADOW_MARGIN
            if (x + w > size) {
                x = 0
                y += row
                row = 0
            }
            if (w > size || y + h > size) continue
            val left = footprint[0] - SHADOW_MARGIN * texel
            val bottom = footprint[1] - SHADOW_MARGIN * texel
            Matrix.orthoM(
                lightProjection,
                0,
                left,
                left + w * texel,
                bottom,
                bottom + h * texel,
                OpalineLightRig.SHADOW_NEAR * scale,
                OpalineLightRig.SHADOW_FAR * scale,
            )
            GL.glViewport(x, y, w, h)
            surface.matrix("uProjection", lightProjection)
            partUniforms(instance)
            for ((index, piece) in mesh.pieces.withIndex()) {
                if (piece.hidden) continue
                pieceModel(instance, index)
                Matrix.multiplyMM(modelView, 0, lightFromView, 0, pieceModel, 0)
                setModel(modelView)
                val family = family(part, piece.source)
                cull(pieceModel, material(family).source.doubleSided, shadow = true)
                drawPiece(part, instance, index, piece)
            }
            // View space to this tile of the atlas.
            Matrix.setIdentityM(tile, 0)
            Matrix.translateM(tile, 0, x.toFloat() / size, y.toFloat() / size, 0f)
            Matrix.scaleM(tile, 0, w.toFloat() / size, h.toFloat() / size, 1f)
            val matrix = instance.shadowMatrix
            Matrix.multiplyMM(matrix, 0, tile, 0, OpalineRealm.BIAS, 0)
            Matrix.multiplyMM(matrix, 0, matrix.copyOf(), 0, lightProjection, 0)
            Matrix.multiplyMM(matrix, 0, matrix.copyOf(), 0, lightFromView, 0)
            instance.shadowed = true
            x += w
            row = max(row, h)
        }
        surface.flag("uDepthOnly", false)
    }

    /** min x, min y, max x, max y of the part's posed pieces in the light's view. */
    private fun footprint(
        mesh: GpuMesh,
        instance: Instance,
    ): FloatArray {
        val bounds =
            floatArrayOf(
                Float.POSITIVE_INFINITY,
                Float.POSITIVE_INFINITY,
                Float.NEGATIVE_INFINITY,
                Float.NEGATIVE_INFINITY,
            )
        for ((index, piece) in mesh.pieces.withIndex()) {
            pieceModel(instance, index)
            Matrix.multiplyMM(modelView, 0, lightFromView, 0, pieceModel, 0)
            for (corner in 0 until 8) {
                for (axis in 0..2) {
                    val sign = ((corner shr axis) and 1) * 2 - 1
                    point[axis] = piece.center[axis] + sign * piece.half[axis]
                }
                point[3] = 1f
                Matrix.multiplyMV(moved, 0, modelView, 0, point, 0)
                bounds[0] = min(bounds[0], moved[0])
                bounds[1] = min(bounds[1], moved[1])
                bounds[2] = max(bounds[2], moved[0])
                bounds[3] = max(bounds[3], moved[1])
            }
        }
        return bounds
    }

    /** Reflector.onBeforeRender: all above the water plane, seen from below, in linear light. */
    private fun reflection(
        parts: List<OpalinePart>,
        frame: OpalineFrame,
    ) {
        val size = OpalineRealm.REFLECTION_SIZE
        val view = realm.reflectionView
        val projection = realm.reflectionProjection
        realm.project(this.projection)
        realm.bindReflection()
        opaqueRealm(view, projection, true, true, frame)
        realm.resolveReflection(mipmaps = true)
        transmissiveRealm(view, projection, realm.reflectionMap, size, size, true, frame)
        Matrix.multiplyMM(fromView, 0, view, 0, realm.inverseView, 0)
        drawParts(parts, frame, view, projection, fromView, realm.reflectionMap, size, size, true)
        realm.resolveReflection(mipmaps = false)
    }

    private fun target(framebuffer: Int) {
        GL.glBindFramebuffer(GL.GL_FRAMEBUFFER, framebuffer)
        GL.glViewport(0, 0, width, height)
        GL.glClearColor(0f, 0f, 0f, 0f)
        GL.glClear(GL.GL_COLOR_BUFFER_BIT or GL.GL_DEPTH_BUFFER_BIT)
    }

    /** The app's background dim darkens the realm, never the parts. */
    private fun light(frame: OpalineFrame) = 1f - frame.dim * DIM_RANGE

    /**
     * Background, then (with [near]) the stone shelves and islands and, below the Reflector's
     * camera, the floor and the mirror; the distant plate last, as three.js sorts it.
     */
    private fun opaqueRealm(
        view: FloatArray,
        projection: FloatArray,
        linear: Boolean,
        near: Boolean,
        frame: OpalineFrame,
    ) {
        val light = light(frame)
        realm.drawBackground(linear, light)
        if (near) {
            realmScene(view, projection, 0, width, height, linear, frame)
            for (body in realm.stones) drawBody(body, view)
            if (view !== realm.reflectionView) {
                drawBody(realm.floor, view)
                realm.drawMirror(view, projection, linear, light)
            }
        }
        realm.drawPlate(view, projection, linear, light)
    }

    /** Water (above the mirror, never in its own reflection), nacre islands, motes, mist. */
    private fun transmissiveRealm(
        view: FloatArray,
        projection: FloatArray,
        transmission: Int,
        transmissionWidth: Int,
        transmissionHeight: Int,
        linear: Boolean,
        frame: OpalineFrame,
    ) {
        realmScene(
            view,
            projection,
            transmission,
            transmissionWidth,
            transmissionHeight,
            linear,
            frame,
        )
        if (view !== realm.reflectionView) {
            optics.bindReceiver(surface, CAUSTIC_UNIT)
            drawBody(realm.water, view)
            surface.scalar("uOpCausticStrength", 0f)
        }
        for (body in realm.nacre + realm.motes) drawBody(body, view)
        realm.drawMist(view, projection, linear, light(frame))
    }

    /** Surface-pass state shared by every realm body: rig at scale 1, fog, realm shadow map. */
    private fun realmScene(
        view: FloatArray,
        projection: FloatArray,
        transmission: Int,
        transmissionWidth: Int,
        transmissionHeight: Int,
        linear: Boolean,
        frame: OpalineFrame,
    ) {
        bindScene(view, projection, transmission, transmissionWidth, transmissionHeight, linear)
        neutral()
        center.fill(0f)
        points(view, 1f)
        surface.scalar("uFogDensity", OpalineRealm.FOG_DENSITY)
        surface.color("uFogColor", realm.fogColor)
        surface.scalar("uLight", light(frame))
        surface.flag("uReceiveShadow", true)
        surface.integer("uShadowMap", REALM_SHADOW_UNIT)
        surface.matrix("uShadowMatrix", realm.shadowMatrix)
        surface.scalar("uShadowNormalBias", OpalineLightRig.SHADOW_NORMAL_BIAS)
        realm.bindWater(surface)
    }

    private fun drawBody(
        body: OpalineRealm.Body,
        view: FloatArray,
    ) {
        Matrix.multiplyMM(modelView, 0, view, 0, body.model, 0)
        setModel(modelView)
        surface.matrix("uShadowModel", body.model)
        surface.flag("uWater", body === realm.water && realm.waves)
        val material = material(body.family)
        cull(body.model, material.source.doubleSided, shadow = false)
        surface.bindMaterial(material, 1f, time)
        body.geometry.draw()
    }

    /** A realm body is still: no press, morph or excitation. */
    private fun neutral() {
        surface.flag("uWater", false)
        surface.scalar("uPressure", 0f)
        surface.scalar("uOpExcitation", 0f)
        surface.scalar("uEnabled", 1f)
        neutralPiece()
    }

    /** Uniforms shared by every surface of a pass: camera, light rig, lighting textures. */
    private fun bindScene(
        view: FloatArray,
        projection: FloatArray,
        transmission: Int,
        transmissionWidth: Int,
        transmissionHeight: Int,
        linear: Boolean,
    ) {
        val theme = OpalineMaterialTheme.of(palette)
        val size = OpalineLightRig.SHADOW_MAP_SIZE.toFloat()
        surface.use()
        surface.flag("uDepthOnly", false)
        surface.flag("uLinearOutput", linear)
        surface.matrix("uProjection", projection)
        surface.scalar("toneMappingExposure", OpalineLightRig.TONE_MAPPING_EXPOSURE)
        surface.scalar("envMapIntensity", OpalineLightRig.ENVIRONMENT_INTENSITY)
        surface.scalar("envMapMaxLod", (OpalineRoomEnvironment.LEVELS - 1).toFloat())
        surface.vec2(
            "transmissionSamplerSize",
            transmissionWidth.toFloat(),
            transmissionHeight.toFloat(),
        )
        surface.vec3("uHemisphereSky", OpalineLightRig.hemisphereSky)
        surface.vec3("uHemisphereGround", OpalineLightRig.hemisphereGround)
        rotate(view, OpalineLightRig.hemisphereDirection, 0, up, 0)
        surface.vec3("uHemisphereDirection", up)
        for (i in 0..1) {
            rotate(view, OpalineLightRig.directionalDirections, i * 3, directions, i * 3)
        }
        surface.vec3s("uDirectionalColor", OpalineLightRig.directionalColors)
        surface.vec3s("uDirectionalDirection", directions)
        surface.scalars("uPointDecay", OpalineLightRig.pointDecays)
        surface.scalar("uOpTime", time)
        surface.scalar("uOpRadius", CONTACT_RADIUS)
        surface.color("uOpAccent", theme.accent)
        surface.color("uOpDeep", theme.deep)
        surface.vec2("uShadowMapSize", size, size)
        surface.scalar("uShadowBias", OpalineLightRig.SHADOW_BIAS)
        surface.scalar("uShadowRadius", OpalineLightRig.SHADOW_RADIUS)
        bindTexture(0, GL.GL_TEXTURE_2D, transmission, "transmissionSamplerMap")
        bindTexture(1, GL.GL_TEXTURE_CUBE_MAP, environment, "envMap")
        bindTexture(2, GL.GL_TEXTURE_2D, dfg, "dfgLUT")
        bindTexture(ATLAS_UNIT, GL.GL_TEXTURE_2D, atlas, "uShadowMap")
        surface.integer("uOpCaustics", CAUSTIC_UNIT)
        surface.scalar("uOpCausticStrength", 0f)
        surface.flag("uFilmThickness", false)
        if (world) {
            GL.glActiveTexture(GL.GL_TEXTURE0 + REALM_SHADOW_UNIT)
            GL.glBindTexture(GL.GL_TEXTURE_2D, realm.shadowMap)
        }
        GL.glActiveTexture(GL.GL_TEXTURE0)
    }

    private fun bindTexture(
        unit: Int,
        target: Int,
        texture: Int,
        uniform: String,
    ) {
        GL.glActiveTexture(GL.GL_TEXTURE0 + unit)
        GL.glBindTexture(target, texture)
        surface.integer(uniform, unit)
    }

    /** Both point lights for an element at realm [center] of [scale], carried into [view]. */
    private fun points(
        view: FloatArray,
        scale: Float,
    ) {
        OpalineLightRig.pointPosition(center, scale, realmPoints)
        for (i in 0..1) {
            point[0] = realmPoints[i * 3]
            point[1] = realmPoints[i * 3 + 1]
            point[2] = realmPoints[i * 3 + 2]
            point[3] = 1f
            Matrix.multiplyMV(moved, 0, view, 0, point, 0)
            moved.copyInto(pointPositions, i * 3, 0, 3)
        }
        OpalineLightRig.pointColor(scale, pointColors)
        OpalineLightRig.pointDistance(scale, pointDistances)
        surface.vec3s("uPointPosition", pointPositions)
        surface.vec3s("uPointColor", pointColors)
        surface.scalars("uPointDistance", pointDistances)
    }

    /** The element origin of [instance] in realm metres, into [center]. */
    private fun realmCenter(instance: Instance) {
        point[0] = instance.model[12]
        point[1] = instance.model[13]
        point[2] = instance.model[14]
        point[3] = 1f
        Matrix.multiplyMV(center, 0, realm.inverseView, 0, point, 0)
    }

    /**
     * Every part in draw order: parents first, children last. Depth is cleared between semantic
     * surfaces (within each clip on screen) so nested controls cannot disappear inside a
     * parent's thick shell. [fromView] carries the parts into the Reflector's camera.
     */
    private fun drawParts(
        parts: List<OpalinePart>,
        frame: OpalineFrame,
        view: FloatArray,
        projection: FloatArray,
        fromView: FloatArray?,
        transmission: Int,
        transmissionWidth: Int,
        transmissionHeight: Int,
        linear: Boolean,
    ) {
        bindScene(view, projection, transmission, transmissionWidth, transmissionHeight, linear)
        surface.flag("uWater", false)
        surface.scalar("uFogDensity", 0f)
        surface.scalar("uLight", 1f)
        surface.integer("uShadowMap", ATLAS_UNIT)
        val sx = width / frame.width
        val sy = height / frame.height
        for (part in parts) {
            val instance = instances.getValue(part.id)
            val mesh = instance.mesh ?: continue
            if (fromView == null) {
                // A recipe-rotated part may reach past its node box: its footprint is the frame.
                val node = if (part.rotation == UNROTATED) part.clip else Rect(0f, 0f, frame.width, frame.height)
                val clip = node.intersect(Rect(0f, 0f, frame.width, frame.height))
                GL.glEnable(GL.GL_SCISSOR_TEST)
                GL.glScissor(
                    (clip.left * sx).toInt().coerceAtLeast(0),
                    ((frame.height - clip.bottom) * sy).toInt().coerceAtLeast(0),
                    (clip.width * sx).toInt().coerceAtLeast(0),
                    (clip.height * sy).toInt().coerceAtLeast(0),
                )
            }
            GL.glClear(GL.GL_DEPTH_BUFFER_BIT)
            surface.scalar("uEnabled", if (part.enabled) 1f else 0f)
            partUniforms(instance)
            // setInteraction(point, strength, velocity): motion.js passes `this.velocity` as is.
            velocity[0] = instance.velocityX
            velocity[1] = instance.velocityY
            surface.vec3("uOpVelocity", velocity)
            surface.flag("uReceiveShadow", instance.shadowed)
            surface.matrix("uShadowMatrix", instance.shadowMatrix)
            val bias = OpalineLightRig.SHADOW_NORMAL_BIAS * instance.metresToWorld
            surface.scalar("uShadowNormalBias", bias)
            // The workbench rig sits in metres around the element's origin.
            realmCenter(instance)
            points(view, instance.metresToWorld)
            for ((index, piece) in mesh.pieces.withIndex()) {
                if (piece.hidden || part.element == BUBBLE) continue
                pieceModel(instance, index)
                place(fromView)
                val family = family(part, piece.source)
                val material = material(family)
                cull(pieceModel, material.source.doubleSided, shadow = false)
                surface.bindMaterial(material, instance.metresToWorld, time)
                drawPiece(part, instance, index, piece)
            }
            if (part.element == BUBBLE) drawBubble(part, instance, mesh, fromView)
            if (part.element == LIQUID) physics.fluid(part.id)
            physics.particles(part.id, mesh.minimum, mesh.maximum) { matrix, family ->
                Matrix.multiplyMM(pieceModel, 0, instance.model, 0, matrix, 0)
                val scale = 1f / OpalineRealm.MOTE_RADIUS
                Matrix.scaleM(pieceModel, 0, scale, scale, scale)
                place(fromView)
                neutralPiece()
                val material = material(family)
                cull(pieceModel, material.source.doubleSided, shadow = false)
                surface.bindMaterial(material, instance.metresToWorld, time)
                realm.motes[0].geometry.draw()
            }
        }
        GL.glDisable(GL.GL_SCISSOR_TEST)
    }

    /** `uModel` for [pieceModel] in this pass, and the shadow's model. */
    private fun place(fromView: FloatArray?) {
        setModel(if (fromView == null) pieceModel else multiply(fromView, pieceModel))
        surface.matrix("uShadowModel", pieceModel)
    }

    /** A piece with no dent or morph of its own. */
    private fun neutralPiece() {
        surface.scalar("uDeform", 0f)
        noMorph()
    }

    private fun noMorph() {
        surface.vec2("uMorph", 0f, 0f)
        GL.glVertexAttrib3f(2, 0f, 0f, 0f)
        GL.glVertexAttrib3f(3, 0f, 0f, 0f)
    }

    /** F01 draws the Bubble hero's simulated film, its live thickness driving the interference. */
    private fun drawBubble(
        part: OpalinePart,
        instance: Instance,
        mesh: GpuMesh,
        fromView: FloatArray?,
    ) {
        val bubble = physics.bubble(part.id)
        instance.model.copyInto(pieceModel)
        place(fromView)
        neutralPiece()
        surface.vec3("uContact", instance.contact)
        surface.vec3("uOpTouch", instance.contact)
        val material = material(family(part, mesh.pieces[0].source))
        cull(pieceModel, material.source.doubleSided, shadow = false)
        surface.bindMaterial(material, instance.metresToWorld, time)
        surface.flag("uFilmThickness", true)
        bubble.mesh.draw(bubble.film)
        surface.flag("uFilmThickness", false)
    }

    /**
     * After the parts, in the scene target: the heroes' media, G07 trails and the transitions'
     * generated meshes (L03 tubes and heads, L15 aperture) in realm metres.
     */
    private fun effects(
        parts: List<OpalinePart>,
        linear: Boolean,
    ) {
        for (part in parts.filter { instances.getValue(it.id).mesh != null }) {
            val instance = instances.getValue(part.id)
            val mesh = requireNotNull(instance.mesh)
            physics.trails(part.id)?.let { (vertices, count) ->
                realm.drawLines(vertices, count, instance.model, projection, linear)
            }
            if (part.element !in MEDIA && !instance.volume) continue
            val half = FloatArray(3) { (mesh.maximum[it] - mesh.minimum[it]) / 2 }
            val pressure = instance.pressure.value * motion
            optics.volume(part.id, instance.model, boxCenter(mesh), half, projection, time, instance.contact, pressure)
        }
        val generated = transitions.generated()
        if (generated.isEmpty()) return
        bindScene(realm.view, projection, targets.receiver, width, height, linear)
        neutral()
        surface.scalar("uFogDensity", 0f)
        surface.scalar("uLight", 1f)
        surface.flag("uReceiveShadow", false)
        center.fill(0f)
        points(realm.view, 1f)
        for ((auxiliary, buffers) in generated) {
            auxiliary.worldPose.writeMatrix(pieceModel)
            setModel(multiply(realm.view, pieceModel))
            val material = SurfaceMaterial(auxiliary.material.physical())
            cull(pieceModel, doubleSided = false, shadow = false)
            surface.bindMaterial(material, 1f, time)
            buffers.draw()
        }
    }

    /** I01 caustics through the first N03 lens on screen onto the realm water. */
    private fun lens(parts: List<OpalinePart>) {
        val part = parts.firstOrNull { it.element == LENS } ?: return
        val instance = instances.getValue(part.id)
        val mesh = instance.mesh ?: return
        val pieces =
            mesh.pieces.mapIndexed { index, piece ->
                pieceModel(instance, index)
                val world = FloatArray(16)
                Matrix.multiplyMM(world, 0, realm.inverseView, 0, pieceModel, 0)
                Triple(piece.source, material(family(part, piece.source)).source, world)
            }
        realmCenterOf(instance.model, 12)
        optics.lens(part.id, center.copyOf(), pieces, frames)
    }

    /** The press of one part, for its shadow and its surface. */
    private fun partUniforms(instance: Instance) {
        surface.scalar("uPressure", instance.pressure.value * motion)
    }

    private fun multiply(
        a: FloatArray,
        b: FloatArray,
    ): FloatArray {
        Matrix.multiplyMM(modelView, 0, a, 0, b, 0)
        return modelView
    }

    /** `uModel` (model-view) and its normal matrix. */
    private fun setModel(matrix: FloatArray) {
        Matrix.invertM(inverse, 0, matrix, 0)
        for (column in 0..2) for (row in 0..2) normal[column * 3 + row] = inverse[row * 4 + column]
        surface.matrix("uModel", matrix)
        GL.glUniformMatrix3fv(surface.location("uNormal"), 1, false, normal, 0)
    }

    /**
     * three.js WebGLState.setMaterial: DoubleSide draws both faces; otherwise back faces are
     * culled, and a shadow pass (the BackSide shadowSide) or a negative determinant flips which
     * winding faces front.
     */
    private fun cull(
        model: FloatArray,
        doubleSided: Boolean,
        shadow: Boolean,
    ) {
        if (doubleSided) {
            GL.glDisable(GL.GL_CULL_FACE)
            return
        }
        GL.glEnable(GL.GL_CULL_FACE)
        GL.glCullFace(GL.GL_BACK)
        val determinant =
            model[0] * (model[5] * model[10] - model[9] * model[6]) -
                model[4] * (model[1] * model[10] - model[9] * model[2]) +
                model[8] * (model[1] * model[6] - model[5] * model[2])
        GL.glFrontFace(if ((determinant < 0f) != shadow) GL.GL_CW else GL.GL_CCW)
    }

    /** instantiate-composition.js `local.gel = local[spec.material]`: gel takes the selector. */
    private fun family(
        part: OpalinePart,
        source: OpalineMesh.Piece,
    ): String = part.material?.takeIf { source.family == GEL } ?: source.family

    /**
     * One piece's contact, excitation, dent and morph, then its draw (from its own buffers while
     * a transition or a lattice deforms it). `setInteraction` receives the contact in the piece's
     * own space.
     */
    private fun drawPiece(
        part: OpalinePart,
        instance: Instance,
        index: Int,
        piece: GpuPiece,
    ) {
        val source = piece.source
        Matrix.invertM(inverse, 0, instance.poses, index * 16)
        Matrix.multiplyMV(localContact, 0, inverse, 0, instance.contact, 0)
        val excitation = (instance.pressure.value * motion).coerceIn(0f, MAX_EXCITATION)
        val transition = instance.subject?.meshes?.getOrNull(index)
        // A transition's own excitation (the L06 light sweep) wins where it is brighter.
        val swept = transition != null && transition.strength > excitation
        surface.vec3("uContact", localContact)
        surface.vec3("uOpTouch", if (swept) requireNotNull(transition).touch else localContact)
        surface.scalar("uOpExcitation", if (swept) requireNotNull(transition).strength else excitation)
        val lattice = physics.lattice(part.id)?.takeIf { dent(part, source) }
        surface.scalar("uDeform", if (lattice == null && dent(part, source)) 1f else 0f)
        if (transition != null && (lattice != null || transition.geometry !== transition.base)) {
            drawDynamic(transition, lattice, source, requireNotNull(instance.mesh))
            return
        }
        GL.glBindVertexArray(piece.vao)
        if (source.morphs.isNotEmpty()) {
            val at = coordinate(instance, source).value.coerceIn(0f, 1f) * (source.morphs.size - 1)
            val low = at.toInt()
            attribute(2, piece.buffers[3 + low])
            attribute(3, piece.buffers[3 + min(low + 1, source.morphs.lastIndex)])
            surface.vec2("uMorph", at - low, 1f)
        } else {
            GL.glDisableVertexAttribArray(2)
            GL.glDisableVertexAttribArray(3)
            GL.glVertexAttrib3f(2, 0f, 0f, 0f)
            GL.glVertexAttrib3f(3, 0f, 0f, 0f)
            surface.vec2("uMorph", 0f, 0f)
        }
        GL.glDrawElements(GL.GL_TRIANGLES, source.indices.size, GL.GL_UNSIGNED_INT, 0)
        GL.glBindVertexArray(0)
    }

    /**
     * A piece drawn from its own buffers: the transition's installed clone, displaced by the gel
     * lattice while one runs (re-uploaded each frame), else re-uploaded when the clone changes.
     */
    private fun drawDynamic(
        transition: OpalineTransitionBridge.Piece,
        lattice: OpalineGelLattice?,
        source: OpalineMesh.Piece,
        mesh: GpuMesh,
    ) {
        val geometry = transition.geometry
        val indices = geometry.indices ?: source.indices
        val buffers = transition.mesh ?: OpalineDynamicMesh(indices).also { transition.mesh = it }
        if (lattice != null) {
            val positions = FloatArray(geometry.positions.size)
            val normals = FloatArray(positions.size)
            physics.deform(lattice, geometry.positions, indices, source.transform, mesh.minimum, mesh.maximum, positions, normals)
            buffers.upload(positions, normals)
            buffers.version = -1
        } else if (buffers.version != geometry.version) {
            buffers.upload(geometry.positions, geometry.normals)
            buffers.version = geometry.version
        }
        noMorph()
        buffers.draw()
    }

    /**
     * MotionController dents closed bodies: a family (after the selector) of gel, blue, nacre or
     * pigment, a role of body, thumb or panel, and at most 50 000 positions.
     */
    private fun dent(
        part: OpalinePart,
        source: OpalineMesh.Piece,
    ): Boolean =
        family(part, source) in DENT_FAMILIES &&
            source.role in DENT_ROLES &&
            source.positions.size / 3 <= DENT_POSITIONS

    /** B04 thumb 1 and B15's outer dial follow `value2`; every other piece follows `value`. */
    private fun coordinate(
        instance: Instance,
        source: OpalineMesh.Piece,
    ) = if (source.motionIndex == 1) instance.coordinate2 else instance.coordinate

    private fun material(family: String): SurfaceMaterial =
        materials.getOrPut(palette to family) {
            SurfaceMaterial(
                if (family == OpalineRealm.FLOOR) {
                    OpalineRealm.floor(palette)
                } else {
                    OpalineMaterialTheme.of(palette).material(family)
                },
            )
        }

    /**
     * motion.js `begin` takes the raycast hit through the perspective camera and `drag` the
     * pointer on the camera-facing plane through it; either becomes the element's contact.
     * A press also rings the realm water at the contact (the workbench bus `touch`, strength 1).
     */
    private fun contact(
        instance: Instance,
        began: Boolean,
    ) {
        val mesh = instance.mesh ?: return
        val bounds = instance.bounds
        val half = tan(FOV_RADIANS / 2f)
        val x = (bounds.left + instance.touchX * bounds.width) / frameWidth * 2f - 1f
        val y = 1f - (bounds.top + instance.touchY * bounds.height) / frameHeight * 2f
        ray[0] = x * half * frameWidth / frameHeight
        ray[1] = y * half
        ray[2] = -1f
        ray[3] = 0f
        if (began) instance.dragDepth = hit(mesh, instance.model, instance.poses, ray) ?: instance.model[14]
        val t = -instance.dragDepth
        point[0] = ray[0] * t
        point[1] = ray[1] * t
        point[2] = instance.dragDepth
        point[3] = 1f
        Matrix.invertM(inverse, 0, instance.model, 0)
        Matrix.multiplyMV(instance.contact, 0, inverse, 0, point, 0)
        if (began) ripple(point, 0)
    }

    /** A water ring at the view-space point [values][offset] in environment hosts. */
    private fun ripple(
        values: FloatArray,
        offset: Int,
    ) {
        if (!world) return
        realmCenterOf(values, offset)
        realm.touch(center[0], center[2], 1f)
    }

    private fun realmCenterOf(
        values: FloatArray,
        offset: Int,
    ) {
        moved[0] = values[offset]
        moved[1] = values[offset + 1]
        moved[2] = values[offset + 2]
        moved[3] = 1f
        Matrix.multiplyMV(center, 0, realm.inverseView, 0, moved, 0)
    }

    private fun read(element: String): OpalineMesh =
        OpalineMesh.read(assets.open("opaline-native/$element.glb").use { it.readBytes() })

    /** The scene at the workbench `ultra` pixel ratio of 2 (per dp), within the device's limits. */
    private fun resize(frame: OpalineFrame) {
        width = (frame.width / frame.density * PIXEL_RATIO).roundToInt().coerceIn(1, maxSize)
        height = (frame.height / frame.density * PIXEL_RATIO).roundToInt().coerceIn(1, maxSize)
        targets.resize(width, height)
    }

    fun dispose() {
        meshes.values.forEach { mesh ->
            mesh.pieces.forEach { piece ->
                GL.glDeleteBuffers(piece.buffers.size, piece.buffers, 0)
                GL.glDeleteVertexArrays(1, intArrayOf(piece.vao), 0)
            }
        }
        meshes.clear()
        instances.clear()
        GL.glDeleteTextures(3, intArrayOf(atlas, dfg, environment), 0)
        GL.glDeleteFramebuffers(1, intArrayOf(atlasFramebuffer), 0)
        if (::targets.isInitialized) targets.dispose()
        if (::surface.isInitialized) surface.dispose()
        if (::realm.isInitialized) realm.dispose()
        post?.release()
        if (::transitions.isInitialized) transitions.dispose()
        if (::physics.isInitialized) physics.dispose()
        if (::optics.isInitialized) optics.dispose()
    }

    private companion object {
        /** The family a part's selector replaces: instantiate-composition.js `local.gel`. */
        const val GEL = "gel"

        /** `uOpRadius` in src/materials.js: the contact glow radius in element units. */
        const val CONTACT_RADIUS = .42f

        /** `setInteraction` clamps excitation to [0, 4]. */
        const val MAX_EXCITATION = 4f

        /** design-tokens.json `perspectiveFovDegrees`; workbench.js camera near .05, far 180. */
        const val FOV = 38f
        const val NEAR = .05f
        const val FAR = 180f
        val FOV_RADIANS = Math.toRadians(FOV.toDouble()).toFloat()

        /**
         * Adapter mapping, not a library value: the screen layout is the camera-facing plane this
         * many view units (realm metres) down the realm camera's axis; decorations sit deeper.
         */
        const val UI_DISTANCE = 16f
        const val DECORATION_DEPTH = 3f

        /** workbench.js `ultra` quality: `renderer.setPixelRatio(2)`. */
        const val PIXEL_RATIO = 2f

        /** motion.js `root.position.z = initialPosition.z - p * .035`. */
        const val PRESS_DEPTH = .035f

        /** MotionController: the families, roles and size limit of bodies that take the dent. */
        val DENT_FAMILIES = setOf("gel", "blue", "nacre", "pigment")
        val DENT_ROLES = setOf("body", "thumb", "panel")
        const val DENT_POSITIONS = 50_000

        /** PCF reach around a tile: the Vogel disk's `shadow.radius` plus one filtered texel. */
        val SHADOW_MARGIN = ceil(OpalineLightRig.SHADOW_RADIUS).toInt() + 1

        /** The app's background-dim setting scales the realm down by up to 72%. */
        const val DIM_RANGE = .72f

        const val ATLAS_UNIT = 3
        const val REALM_SHADOW_UNIT = 4
        const val CAUSTIC_UNIT = 6

        /** The Bubble hero's film (F01), the Drop hero's liquid (E12) and the Glass hero's lens (N03). */
        const val BUBBLE = "F01"
        const val LIQUID = "E12"
        const val LENS = "N03"

        /** Elements that hold a medium (§12a): the Glass hero's water panel and the Bubble. */
        val MEDIA = setOf("N07", "F01")
        val UNROTATED = OpalineVec3(0f, 0f, 0f)
        val HALF_FLOAT_TARGETS =
            listOf("GL_EXT_color_buffer_half_float", "GL_EXT_color_buffer_float")
    }
}

/** One src/materials.js family in one theme, converted once to the linear uniforms it needs. */
private class SurfaceMaterial(
    val source: OpalineMaterial,
) {
    val diffuse = linearRgb(source.color)
    val emissive = linearRgb(source.emissive).scaled(source.emissiveIntensity)
    val attenuationColor = linearRgb(source.attenuationColor)
    val sheenColor = linearRgb(source.sheenColor).scaled(source.sheen)

    private fun FloatArray.scaled(factor: Float) = also { for (i in indices) this[i] *= factor }
}

/** [hidden] marks the baked slot guides of D supports, re-authored as native tab mounts. */
private class GpuPiece(
    val source: OpalineMesh.Piece,
    val buffers: IntArray,
    val vao: Int,
    val center: FloatArray,
    val half: FloatArray,
    val hidden: Boolean,
) {
    /** The rest geometry transitions read and clone before deforming. */
    val base = TransitionGeometry(source.positions, source.indices, source.normals)
}

/** [minimum] and [maximum] bound the authored element as createElement measures it. */
private class GpuMesh(
    val pieces: List<GpuPiece>,
    val minimum: FloatArray,
    val maximum: FloatArray,
)

/** Möller–Trumbore: the smallest positive ray parameter over the piece's triangles. */
private fun intersect(
    piece: OpalineMesh.Piece,
    o: FloatArray,
    d: FloatArray,
): Float {
    val p = piece.positions
    val indices = piece.indices
    var best = Float.POSITIVE_INFINITY
    for (i in indices.indices step 3) {
        val a = indices[i] * 3
        val b = indices[i + 1] * 3
        val c = indices[i + 2] * 3
        val e1x = p[b] - p[a]
        val e1y = p[b + 1] - p[a + 1]
        val e1z = p[b + 2] - p[a + 2]
        val e2x = p[c] - p[a]
        val e2y = p[c + 1] - p[a + 1]
        val e2z = p[c + 2] - p[a + 2]
        val px = d[1] * e2z - d[2] * e2y
        val py = d[2] * e2x - d[0] * e2z
        val pz = d[0] * e2y - d[1] * e2x
        val det = e1x * px + e1y * py + e1z * pz
        if (det == 0f) continue
        val sx = o[0] - p[a]
        val sy = o[1] - p[a + 1]
        val sz = o[2] - p[a + 2]
        val u = (sx * px + sy * py + sz * pz) / det
        val qx = sy * e1z - sz * e1y
        val qy = sz * e1x - sx * e1z
        val qz = sx * e1y - sy * e1x
        val v = (d[0] * qx + d[1] * qy + d[2] * qz) / det
        val t = (e2x * qx + e2y * qy + e2z * qz) / det
        val inside = u >= 0f && v >= 0f && u + v <= 1f
        if (inside && t > 0f) best = min(best, t)
    }
    return best
}

private fun rotate(
    view: FloatArray,
    source: FloatArray,
    from: Int,
    out: FloatArray,
    to: Int,
) {
    for (row in 0..2) {
        out[to + row] =
            view[row] * source[from] + view[4 + row] * source[from + 1] +
                view[8 + row] * source[from + 2]
    }
}

/**
 * Grows [minimum]/[maximum] by one piece as geometry.js createElement measures an element
 * before fitting `dimensions`: `new THREE.Box3().setFromObject(group)` unites each mesh's
 * geometry bounding box, its morph shapes included as BufferGeometry.computeBoundingBox
 * includes them, carried corner by corner through the mesh's matrix. For every bundled GLB
 * this reproduces elements.json `modelAsset.bounds`.
 */
private fun measure(
    piece: OpalineMesh.Piece,
    minimum: FloatArray,
    maximum: FloatArray,
) {
    val low = FloatArray(3) { Float.POSITIVE_INFINITY }
    val high = FloatArray(3) { Float.NEGATIVE_INFINITY }
    for (i in piece.positions.indices) {
        val axis = i % 3
        val position = piece.positions[i]
        low[axis] = min(low[axis], position)
        high[axis] = max(high[axis], position)
        // glTF morph targets are displacements; three.js measures the displaced shapes.
        for (morph in piece.morphs) {
            low[axis] = min(low[axis], position + morph[i])
            high[axis] = max(high[axis], position + morph[i])
        }
    }
    val corner = FloatArray(4)
    val moved = FloatArray(4)
    for (index in 0 until 8) {
        corner[0] = if ((index and 1) == 0) low[0] else high[0]
        corner[1] = if ((index and 2) == 0) low[1] else high[1]
        corner[2] = if ((index and 4) == 0) low[2] else high[2]
        corner[3] = 1f
        Matrix.multiplyMV(moved, 0, piece.transform, 0, corner, 0)
        for (axis in 0..2) {
            minimum[axis] = min(minimum[axis], moved[axis])
            maximum[axis] = max(maximum[axis], moved[axis])
        }
    }
}

private fun attribute(
    index: Int,
    buffer: Int,
) {
    GL.glBindBuffer(GL.GL_ARRAY_BUFFER, buffer)
    GL.glEnableVertexAttribArray(index)
    GL.glVertexAttribPointer(index, 3, GL.GL_FLOAT, false, 0, 0)
}

private fun upload(
    element: String,
    mesh: OpalineMesh,
): GpuMesh {
    // geometry.js: an element with no meshes (J24) measures as the unit box [-1, 1].
    val empty = mesh.pieces.isEmpty()
    val minimum =
        if (empty) mesh.minimum.copyOf() else FloatArray(3) { Float.POSITIVE_INFINITY }
    val maximum =
        if (empty) mesh.maximum.copyOf() else FloatArray(3) { Float.NEGATIVE_INFINITY }
    for (piece in mesh.pieces) measure(piece, minimum, maximum)
    return GpuMesh(mesh.pieces.map { upload(element, it) }, minimum, maximum)
}

private fun upload(
    element: String,
    source: OpalineMesh.Piece,
): GpuPiece {
    val buffers = IntArray(3 + source.morphs.size)
    GL.glGenBuffers(buffers.size, buffers, 0)
    val vao = IntArray(1)
    GL.glGenVertexArrays(1, vao, 0)
    GL.glBindVertexArray(vao[0])
    val arrays = listOf(source.positions, source.normals) + source.morphs
    for ((index, values) in arrays.withIndex()) {
        GL.glBindBuffer(GL.GL_ARRAY_BUFFER, buffers[if (index < 2) index else index + 1])
        val data =
            ByteBuffer
                .allocateDirect(values.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .put(values)
        data.position(0)
        GL.glBufferData(GL.GL_ARRAY_BUFFER, values.size * 4, data, GL.GL_STATIC_DRAW)
    }
    attribute(0, buffers[0])
    attribute(1, buffers[1])
    GL.glBindBuffer(GL.GL_ELEMENT_ARRAY_BUFFER, buffers[2])
    val indices =
        ByteBuffer
            .allocateDirect(source.indices.size * 4)
            .order(ByteOrder.nativeOrder())
            .asIntBuffer()
            .put(source.indices)
    indices.position(0)
    GL.glBufferData(
        GL.GL_ELEMENT_ARRAY_BUFFER,
        source.indices.size * 4,
        indices,
        GL.GL_STATIC_DRAW,
    )
    GL.glBindVertexArray(0)
    val low = FloatArray(3) { Float.POSITIVE_INFINITY }
    val high = FloatArray(3) { Float.NEGATIVE_INFINITY }
    source.positions.forEachIndexed { i, value ->
        low[i % 3] = minOf(low[i % 3], value)
        high[i % 3] = maxOf(high[i % 3], value)
    }
    return GpuPiece(
        source,
        buffers,
        vao[0],
        FloatArray(3) { (low[it] + high[it]) / 2 },
        FloatArray(3) { ((high[it] - low[it]) / 2).coerceAtLeast(.001f) },
        // UI034/UI038 sockets are re-authored as the native tab mounts, so the baked
        // five/three-slot guides of D supports must not duplicate the tabs.
        element.startsWith("D") && source.role == "guide",
    )
}

/** The scene target and the transmission receiver (full mips) beside it, sharing one depth buffer. */
private class SceneTargets(
    private val linearFormat: Int,
    private val sceneFormat: Int,
) {
    var receiver = 0
        private set
    var receiverFramebuffer = 0
        private set
    var scene = 0
        private set
    var sceneFramebuffer = 0
        private set
    private var depthBuffer = 0
    private var width = 0
    private var height = 0

    fun resize(
        w: Int,
        h: Int,
    ) {
        if (w == width && h == height) return
        width = w
        height = h
        dispose()
        val ids = IntArray(1)
        GL.glGenRenderbuffers(1, ids, 0)
        depthBuffer = ids[0]
        GL.glBindRenderbuffer(GL.GL_RENDERBUFFER, depthBuffer)
        GL.glRenderbufferStorage(GL.GL_RENDERBUFFER, GL.GL_DEPTH_COMPONENT24, w, h)
        receiver = OpalineRealm.texture()
        val levels = log2(max(w, h).toFloat()).toInt() + 1
        GL.glTexStorage2D(GL.GL_TEXTURE_2D, levels, linearFormat, w, h)
        GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MIN_FILTER, GL.GL_LINEAR_MIPMAP_LINEAR)
        receiverFramebuffer = framebuffer(receiver)
        scene = OpalineRealm.texture()
        GL.glTexStorage2D(GL.GL_TEXTURE_2D, 1, sceneFormat, w, h)
        sceneFramebuffer = framebuffer(scene)
    }

    private fun framebuffer(color: Int): Int {
        val ids = IntArray(1)
        GL.glGenFramebuffers(1, ids, 0)
        GL.glBindFramebuffer(GL.GL_FRAMEBUFFER, ids[0])
        GL.glFramebufferTexture2D(GL.GL_FRAMEBUFFER, GL.GL_COLOR_ATTACHMENT0, GL.GL_TEXTURE_2D, color, 0)
        GL.glFramebufferRenderbuffer(GL.GL_FRAMEBUFFER, GL.GL_DEPTH_ATTACHMENT, GL.GL_RENDERBUFFER, depthBuffer)
        check(GL.glCheckFramebufferStatus(GL.GL_FRAMEBUFFER) == GL.GL_FRAMEBUFFER_COMPLETE)
        GL.glBindFramebuffer(GL.GL_FRAMEBUFFER, 0)
        return ids[0]
    }

    fun dispose() {
        GL.glDeleteTextures(2, intArrayOf(receiver, scene), 0)
        GL.glDeleteFramebuffers(2, intArrayOf(receiverFramebuffer, sceneFramebuffer), 0)
        GL.glDeleteRenderbuffers(1, intArrayOf(depthBuffer), 0)
    }
}

/**
 * Decorative accents drawn as parts of UI075, the seed-and-leaf control dock, so each carries its
 * recipe material and depth.
 */
private fun decorations(
    catalogue: OpalineCatalogue,
    frame: OpalineFrame,
    time: Float,
): List<OpalinePart> {
    val w = frame.width
    val h = frame.height
    return listOf(
        Triple("lens-right", Rect(-w * .18f, h * .15f, w * .23f, h * .44f), .3f),
        Triple("spore", Rect(w * .80f, h * .04f, w * 1.05f, h * .34f), .7f),
        Triple("seed-left", Rect(w * .72f, h * .63f, w * 1.1f, h * .98f), .5f),
    ).mapIndexed { index, (id, bounds, value) ->
        val spec = catalogue.composition("UI075").part(id)
        val drift = if (frame.reducedMotion) 0f else sin(time * .28f + index) * w * .012f
        OpalinePart(
            id = -index - 1L,
            element = spec.element,
            material = spec.material,
            dimensions = spec.dimensions,
            rotation = spec.rotation,
            scale = spec.scale,
            z = spec.position.z,
            bounds = bounds.translate(0f, drift),
            clip = Rect(0f, 0f, w, h),
            value = value,
            selected = false,
            enabled = true,
            depth = 0,
        )
    }
}

/** `updateMaterials`: the film's thickness range drifts by 25 nm at 0.09 rad/s. */
private const val FILM_DRIFT = 25f
private const val FILM_DRIFT_RATE = .09f

/**
 * MeshPhysicalMaterial uniforms for one family. Attenuation distance is in three.js world
 * units, which are catalogue metres in the library, while the refracted path is scaled by the
 * model matrix: [metresToWorld] carries it into this view as the part's dimensions are. The
 * film family's thickness range drifts as `updateMaterials` drives it.
 */
private fun OpalineProgram.bindMaterial(
    material: SurfaceMaterial,
    metresToWorld: Float,
    time: Float,
) {
    val source = material.source
    val drift = if (source.film >= 1f) FILM_DRIFT * sin(time * FILM_DRIFT_RATE) else 0f
    vec3("uDiffuse", material.diffuse)
    vec3("uEmissive", material.emissive)
    scalar("uRoughness", source.roughness)
    scalar("uMetalness", source.metalness)
    scalar("uIor", source.ior)
    scalar("uClearcoat", source.clearcoat)
    scalar("uClearcoatRoughness", source.clearcoatRoughness)
    scalar("uIridescence", source.iridescence)
    scalar("uIridescenceIOR", source.iridescenceIor)
    vec2(
        "uIridescenceThickness",
        source.iridescenceThickness.start + drift,
        source.iridescenceThickness.endInclusive + drift,
    )
    vec3("uSheenColor", material.sheenColor)
    scalar("uSheenRoughness", source.sheenRoughness)
    scalar("uTransmission", source.transmission)
    scalar("uThickness", source.thickness)
    vec3("uAttenuationColor", material.attenuationColor)
    val attenuation = source.attenuationDistance
    scalar(
        "uAttenuationDistance",
        if (attenuation.isFinite()) attenuation * metresToWorld else 0f,
    )
    scalar("uDispersion", source.dispersion)
    flag("uDoubleSide", source.doubleSided)
    scalar("uOpFlow", source.flow)
    scalar("uOpCloud", source.cloud)
    scalar("uOpGrain", source.grain)
    scalar("uOpFilm", source.film)
}

/** The view depth of the nearest drawn triangle under [ray] (from the camera), or null. */
private fun hit(
    mesh: GpuMesh,
    model: FloatArray,
    poses: FloatArray,
    ray: FloatArray,
): Float? {
    val world = FloatArray(16)
    val inverse = FloatArray(16)
    val origin = FloatArray(4)
    val direction = FloatArray(4)
    var best = Float.POSITIVE_INFINITY
    for ((index, piece) in mesh.pieces.withIndex()) {
        if (piece.hidden) continue
        Matrix.multiplyMM(world, 0, model, 0, poses, index * 16)
        Matrix.invertM(inverse, 0, world, 0)
        Matrix.multiplyMV(origin, 0, inverse, 0, floatArrayOf(0f, 0f, 0f, 1f), 0)
        Matrix.multiplyMV(direction, 0, inverse, 0, ray, 0)
        best = min(best, intersect(piece.source, origin, direction))
    }
    return if (best.isFinite()) -best else null
}

/** A generated mesh's `MeshPhysicalMaterial` parameters; the rest keep r186 defaults. */
private fun TransitionMaterial.physical() =
    OpalineMaterial(
        color = color,
        roughness = roughness.toFloat(),
        ior = ior.toFloat(),
        transmission = transmission.toFloat(),
        thickness = thickness.toFloat(),
        clearcoat = clearcoat.toFloat(),
        emissive = emissive,
        emissiveIntensity = emissiveIntensity.toFloat(),
    )
