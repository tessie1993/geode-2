package dev.geode.ui.opaline

import android.content.res.AssetManager
import android.graphics.BitmapFactory
import android.opengl.GLUtils
import android.opengl.Matrix
import androidx.compose.ui.geometry.Rect
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
import android.opengl.GLES30 as GL

internal data class OpalinePart(
    val id: Long,
    val element: String,
    val bounds: Rect,
    val clip: Rect,
    val value: Float,
    val selected: Boolean,
    val enabled: Boolean,
    val depth: Int,
    val secondaryValue: Float = value,
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
)

/** One GLES 3 scene per Android window. All GPU objects are confined to the EGL thread. */
internal class OpalineRenderer(
    private val assets: AssetManager,
) {
    private class Instance(
        value: Float,
        secondaryValue: Float = value,
    ) {
        val pressure = OpalineSpring()
        val coordinate = OpalineSpring(value, damping = 0.8f)
        val coordinate2 = OpalineSpring(secondaryValue, damping = 0.8f)
        val selection = OpalineSpring()
        val reveal = OpalineSpring(0f, frequency = 2.5f, damping = 1f).apply { target = 1f }
        var touchX = 0.5f
        var touchY = 0.5f

        /** MotionController.drag: the last pointer delta, cleared on release (normalised units). */
        var velocityX = 0f
        var velocityY = 0f
        val pointers = mutableSetOf<Long>()
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

    private data class GpuPiece(
        val source: OpalineMesh.Piece,
        val buffers: IntArray,
        val vao: Int,
        val center: FloatArray,
        val half: FloatArray,
    )

    private data class GpuMesh(
        val source: OpalineMesh,
        val pieces: List<GpuPiece>,
    )

    private val meshes = mutableMapOf<String, GpuMesh>()
    private val instances = mutableMapOf<Long, Instance>()
    private val materials = mutableMapOf<Pair<OpalinePalette, String>, SurfaceMaterial>()
    private lateinit var surface: OpalineProgram
    private lateinit var backdrop: OpalineProgram
    private var dfg = 0
    private var environment = 0
    private var artwork = 0
    private var artworkAspect = 1f
    private var theme: OpalinePalette? = null
    private var receiver = 0
    private var framebuffer = 0
    private var width = 0
    private var height = 0
    private var time = 0f
    private val projection = FloatArray(16)
    private val model = FloatArray(16)
    private val pieceModel = FloatArray(16)
    private val inverse = FloatArray(16)
    private val normal = FloatArray(9)
    private val contact = FloatArray(4)
    private val localContact = FloatArray(4)
    private val velocity = FloatArray(4)
    private val localVelocity = FloatArray(4)
    private val lightCenter = FloatArray(3)
    private val pointPositions = FloatArray(6)
    private val pointColors = FloatArray(6)
    private val pointDistances = FloatArray(2)

    fun create() {
        surface = OpalineProgram(assets, "surface")
        backdrop = OpalineProgram(assets, "backdrop")
        dfg = OpalineLightingTextures.dfg()
        environment = OpalineLightingTextures.environment()
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
        val dragging = pressed && pointer in instance.pointers
        if (pressed) instance.pointers.add(pointer) else instance.pointers.remove(pointer)
        instance.pressure.target = if (instance.pointers.isEmpty()) 0f else 1f
        val nextX = x.coerceIn(0f, 1f)
        val nextY = y.coerceIn(0f, 1f)
        instance.velocityX = if (dragging) nextX - instance.touchX else 0f
        instance.velocityY = if (dragging) nextY - instance.touchY else 0f
        instance.touchX = nextX
        instance.touchY = nextY
    }

    fun cancel(id: Long? = null) {
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
        resize(targetWidth, targetHeight)
        if (theme != frame.palette) loadArtwork(frame.palette)
        if (!frame.reducedMotion) time += dt * frame.motion
        val liveIds = frame.parts.mapTo(hashSetOf()) { it.id }
        instances.keys.retainAll(liveIds)
        Matrix.perspectiveM(projection, 0, 38f, width.toFloat() / height, 0.1f, 80f)
        GL.glViewport(0, 0, width, height)
        GL.glDisable(GL.GL_SCISSOR_TEST)
        // The transmission receiver: the scene behind every surface in linear light, mip-mapped so
        // rough refraction reads a wider footprint, as three.js prepares its transmission target.
        GL.glBindFramebuffer(GL.GL_FRAMEBUFFER, framebuffer)
        drawBackdrop(frame, linear = true)
        GL.glBindFramebuffer(GL.GL_FRAMEBUFFER, 0)
        GL.glBindTexture(GL.GL_TEXTURE_2D, receiver)
        GL.glGenerateMipmap(GL.GL_TEXTURE_2D)
        if (frame.transparent) {
            GL.glClearColor(0f, 0f, 0f, 0f)
            GL.glClear(GL.GL_COLOR_BUFFER_BIT)
        } else {
            drawBackdrop(frame, linear = false)
        }
        GL.glEnable(GL.GL_DEPTH_TEST)
        GL.glClear(GL.GL_DEPTH_BUFFER_BIT)
        surface.use()
        bindScene(frame)
        if (frame.environment) drawEnvironment(frame)
        // Parents first, children last. Depth is cleared between semantic surfaces so nested
        // controls cannot disappear inside a parent's thick shell. Pieces retain real self-depth.
        val ordered =
            frame.parts.sortedWith(
                compareBy<OpalinePart> { it.depth }.thenByDescending { it.bounds.width * it.bounds.height },
            )
        ordered.forEach { part ->
            if (part.bounds.width > 1f && part.bounds.height > 1f && part.clip.width > 0f && part.clip.height > 0f) {
                val instance = instances.getOrPut(part.id) { Instance(part.value, part.secondaryValue) }
                instance.coordinate.target = part.value
                instance.coordinate2.target = part.secondaryValue
                instance.selection.target = if (part.selected) 1f else 0f
                if (frame.reducedMotion) {
                    instance.pressure.reset(0f)
                    instance.coordinate.reset()
                    instance.coordinate2.reset()
                    instance.selection.reset()
                    instance.reveal.reset(1f)
                } else {
                    instance.pressure.step(dt)
                    instance.coordinate.step(dt)
                    instance.coordinate2.step(dt)
                    instance.selection.step(dt)
                    instance.reveal.step(dt)
                }
                drawPart(part, instance, frame)
            }
        }
        GL.glDisable(GL.GL_SCISSOR_TEST)
        check(GL.glGetError() == GL.GL_NO_ERROR) { "Opaline native draw failed" }
    }

    /** Uniforms shared by every surface this frame: camera, light rig, lighting textures. */
    private fun bindScene(frame: OpalineFrame) {
        val theme = OpalineMaterialTheme.of(frame.palette)
        surface.matrix("uProjection", projection)
        surface.scalar("toneMappingExposure", OpalineLightRig.TONE_MAPPING_EXPOSURE)
        surface.scalar("envMapIntensity", OpalineLightRig.ENVIRONMENT_INTENSITY)
        surface.scalar("envMapMaxLod", (OpalineRoomEnvironment.LEVELS - 1).toFloat())
        surface.vec2("transmissionSamplerSize", width.toFloat(), height.toFloat())
        surface.vec3("uHemisphereSky", OpalineLightRig.hemisphereSky)
        surface.vec3("uHemisphereGround", OpalineLightRig.hemisphereGround)
        surface.vec3("uHemisphereDirection", OpalineLightRig.hemisphereDirection)
        surface.vec3s("uDirectionalColor", OpalineLightRig.directionalColors)
        surface.vec3s("uDirectionalDirection", OpalineLightRig.directionalDirections)
        surface.scalars("uPointDecay", OpalineLightRig.pointDecays)
        surface.scalar("uOpTime", time)
        surface.scalar("uOpRadius", CONTACT_RADIUS)
        surface.color("uOpAccent", theme.accent)
        surface.color("uOpDeep", theme.deep)
        GL.glActiveTexture(GL.GL_TEXTURE0)
        GL.glBindTexture(GL.GL_TEXTURE_2D, receiver)
        surface.integer("transmissionSamplerMap", 0)
        GL.glActiveTexture(GL.GL_TEXTURE1)
        GL.glBindTexture(GL.GL_TEXTURE_CUBE_MAP, environment)
        surface.integer("envMap", 1)
        GL.glActiveTexture(GL.GL_TEXTURE2)
        GL.glBindTexture(GL.GL_TEXTURE_2D, dfg)
        surface.integer("dfgLUT", 2)
        GL.glActiveTexture(GL.GL_TEXTURE0)
    }

    private fun drawEnvironment(frame: OpalineFrame) {
        val w = frame.width
        val h = frame.height
        val clip = Rect(0f, 0f, w, h)
        listOf(
            Triple("N03", Rect(-w * .18f, h * .15f, w * .23f, h * .44f), .3f),
            Triple("N06", Rect(w * .80f, h * .04f, w * 1.05f, h * .34f), .7f),
            Triple("N01", Rect(w * .72f, h * .63f, w * 1.1f, h * .98f), .5f),
        ).forEachIndexed { index, (element, bounds, value) ->
            val drift = if (frame.reducedMotion) 0f else sin(time * .28f + index) * w * .012f
            val part = OpalinePart(-index.toLong() - 1, element, bounds.translate(0f, drift), clip, value, false, true, 0)
            val instance = Instance(value).apply { reveal.reset(1f) }
            drawPart(part, instance, frame, decorative = true)
        }
    }

    private fun drawPart(
        part: OpalinePart,
        instance: Instance,
        frame: OpalineFrame,
        decorative: Boolean = false,
    ) {
        val mesh =
            meshes.getOrPut(part.element) {
                upload(OpalineMesh.read(assets.open("opaline-native/${part.element}.glb").use { it.readBytes() }))
            }
        val bounds = part.bounds
        val clip = part.clip.intersect(Rect(0f, 0f, frame.width, frame.height))
        val sx = width / frame.width
        val sy = height / frame.height
        GL.glEnable(GL.GL_SCISSOR_TEST)
        GL.glScissor(
            (clip.left * sx).toInt().coerceAtLeast(0),
            ((frame.height - clip.bottom) * sy).toInt().coerceAtLeast(0),
            (clip.width * sx).toInt().coerceAtLeast(0),
            (clip.height * sy).toInt().coerceAtLeast(0),
        )
        GL.glClear(GL.GL_DEPTH_BUFFER_BIT)
        val source = mesh.source
        val sizeX = (source.maximum[0] - source.minimum[0]).coerceAtLeast(.001f)
        val sizeY = (source.maximum[1] - source.minimum[1]).coerceAtLeast(.001f)
        val sizeZ = (source.maximum[2] - source.minimum[2]).coerceAtLeast(.001f)
        val distance = 16f + if (decorative) 3f else 0f
        val perPixel = (2f * distance * tan(Math.toRadians(19.0))).toFloat() / frame.height
        val lift = if (frame.reducedMotion) 0f else (1f - instance.reveal.value).coerceIn(0f, 1f) * .35f
        Matrix.setIdentityM(model, 0)
        Matrix.translateM(
            model,
            0,
            (bounds.center.x - frame.width / 2) * perPixel,
            (frame.height / 2 - bounds.center.y) * perPixel + lift,
            -distance + instance.selection.value * .10f - instance.pressure.value * .035f,
        )
        // Front plane remains aligned; controlled tilt reveals curved rims and undersides.
        Matrix.rotateM(
            model,
            0,
            if (decorative) {
                -16f
            } else if (part.element.startsWith("C")) {
                -3f
            } else {
                -8f
            },
            1f,
            0f,
            0f,
        )
        Matrix.rotateM(model, 0, if (decorative) 18f else (instance.touchX - .5f) * instance.pressure.value * 3f, 0f, 1f, 0f)
        val pixelDepth = min(bounds.width, bounds.height) * if (part.element.startsWith("C")) .13f else .24f
        Matrix.scaleM(
            model,
            0,
            bounds.width * perPixel * .92f / sizeX,
            bounds.height * perPixel * .90f / sizeY,
            pixelDepth * perPixel / sizeZ,
        )
        Matrix.translateM(
            model,
            0,
            -(source.minimum[0] + source.maximum[0]) / 2,
            -(source.minimum[1] + source.maximum[1]) / 2,
            -(source.minimum[2] + source.maximum[2]) / 2,
        )
        contact[0] = source.minimum[0] + instance.touchX * sizeX
        contact[1] = source.maximum[1] - instance.touchY * sizeY
        contact[2] = source.maximum[2]
        contact[3] = 1f
        velocity[0] = instance.velocityX * sizeX
        velocity[1] = -instance.velocityY * sizeY
        velocity[2] = 0f
        velocity[3] = 0f
        val pressure = instance.pressure.value * frame.motion
        surface.scalar("uPressure", pressure)
        surface.scalar("uOpExcitation", pressure.coerceIn(0f, MAX_EXCITATION))
        surface.scalar("uEnabled", if (part.enabled) 1f else 0f)
        surface.scalar("uReveal", instance.reveal.value)
        // The workbench lights elements of about their own size: carry the rig into this part.
        val elementScale = (bounds.width * perPixel * .92f / sizeX + bounds.height * perPixel * .90f / sizeY) / 2f
        lightCenter[0] = model[12]
        lightCenter[1] = model[13]
        lightCenter[2] = model[14]
        OpalineLightRig.pointPosition(lightCenter, elementScale, pointPositions)
        OpalineLightRig.pointColor(elementScale, pointColors)
        OpalineLightRig.pointDistance(elementScale, pointDistances)
        surface.vec3s("uPointPosition", pointPositions)
        surface.vec3s("uPointColor", pointColors)
        surface.scalars("uPointDistance", pointDistances)
        for (piece in mesh.pieces) {
            // UI034/UI038 sockets are re-authored as the four native tab mounts. The source
            // support is retained; its baked five/three-slot guides must not duplicate the tabs.
            if (part.element.startsWith("D") && piece.source.role == "guide") continue
            drawPiece(piece, instance, frame, elementScale)
        }
    }

    private fun drawPiece(
        piece: GpuPiece,
        instance: Instance,
        frame: OpalineFrame,
        elementScale: Float,
    ) {
        val source = piece.source
        val pose = source.transform.copyOf()
        val coordinate = if (source.motionIndex == 1) instance.coordinate2 else instance.coordinate
        val value = coordinate.value.coerceIn(0f, 1f)
        if (source.motion == "slider") {
            val travel = source.travel[3] + source.travel[4] * value
            for (i in 0..2) pose[12 + i] += source.travel[i] * travel
            val stretch = 1f + min(.28f, abs(coordinate.velocity) * .12f)
            Matrix.scaleM(pose, 0, stretch, 1f / sqrt(stretch), 1f / sqrt(stretch))
        } else if (source.motion == "dial" || source.motion == "wheel") {
            val rotation = FloatArray(16)
            Matrix.setIdentityM(rotation, 0)
            val pivot = source.motionPivot
            val axis = source.motionAxis
            Matrix.translateM(rotation, 0, pivot[0], pivot[1], pivot[2])
            Matrix.rotateM(rotation, 0, -(value - .5f) * 288f, axis[0], axis[1], axis[2])
            Matrix.translateM(rotation, 0, -pivot[0], -pivot[1], -pivot[2])
            Matrix.multiplyMM(pose, 0, rotation, 0, source.transform, 0)
        } else if (source.motion == "wind" && !frame.reducedMotion) {
            Matrix.rotateM(pose, 0, sin(time * .7f) * 2.3f, 0f, 0f, 1f)
        }
        Matrix.multiplyMM(pieceModel, 0, model, 0, pose, 0)
        Matrix.invertM(inverse, 0, pieceModel, 0)
        for (column in 0..2) for (row in 0..2) normal[column * 3 + row] = inverse[row * 4 + column]
        surface.matrix("uModel", pieceModel)
        GL.glUniformMatrix3fv(surface.location("uNormal"), 1, false, normal, 0)
        Matrix.invertM(inverse, 0, pose, 0)
        Matrix.multiplyMV(localContact, 0, inverse, 0, contact, 0)
        Matrix.multiplyMV(localVelocity, 0, inverse, 0, velocity, 0)
        surface.vec3("uContact", localContact)
        surface.vec3("uOpTouch", localContact)
        surface.vec3("uOpVelocity", localVelocity)
        surface.scalar("uDeform", if (source.deformable) 1f else 0f)
        surface.vec3("uShapeCenter", piece.center)
        surface.vec3("uShapeHalf", piece.half)
        val material = materials.getOrPut(frame.palette to source.family) { surfaceMaterial(frame.palette, source.family) }
        bindMaterial(material, elementScale)
        GL.glBindVertexArray(piece.vao)
        if (source.morphs.isNotEmpty()) {
            val at = value * (source.morphs.size - 1)
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

    private fun surfaceMaterial(
        palette: OpalinePalette,
        family: String,
    ) = SurfaceMaterial(OpalineMaterialTheme.of(palette).material(family))

    /**
     * MeshPhysicalMaterial uniforms for one family. Attenuation distance is in world units in
     * three.js while the refracted path is scaled by the model matrix, so it scales with the element
     * like the path does. The film family's thickness range drifts as `updateMaterials` drives it.
     */
    private fun bindMaterial(
        material: SurfaceMaterial,
        elementScale: Float,
    ) {
        val source = material.source
        val drift = if (source.film >= 1f) FILM_DRIFT * sin(time * FILM_DRIFT_RATE) else 0f
        surface.vec3("uDiffuse", material.diffuse)
        surface.vec3("uEmissive", material.emissive)
        surface.scalar("uRoughness", source.roughness)
        surface.scalar("uIor", source.ior)
        surface.scalar("uClearcoat", source.clearcoat)
        surface.scalar("uClearcoatRoughness", source.clearcoatRoughness)
        surface.scalar("uIridescence", source.iridescence)
        surface.scalar("uIridescenceIOR", source.iridescenceIor)
        surface.vec2("uIridescenceThickness", source.iridescenceThickness.start + drift, source.iridescenceThickness.endInclusive + drift)
        surface.vec3("uSheenColor", material.sheenColor)
        surface.scalar("uSheenRoughness", source.sheenRoughness)
        surface.scalar("uTransmission", source.transmission)
        surface.scalar("uThickness", source.thickness)
        surface.vec3("uAttenuationColor", material.attenuationColor)
        surface.scalar("uAttenuationDistance", if (source.attenuationDistance.isFinite()) source.attenuationDistance * elementScale else 0f)
        surface.scalar("uDispersion", source.dispersion)
        surface.flag("uDoubleSide", source.doubleSided)
        surface.scalar("uOpFlow", source.flow)
        surface.scalar("uOpCloud", source.cloud)
        surface.scalar("uOpGrain", source.grain)
        surface.scalar("uOpFilm", source.film)
    }

    private fun upload(mesh: OpalineMesh): GpuMesh =
        GpuMesh(
            mesh,
            mesh.pieces.map { source ->
                val buffers = IntArray(3 + source.morphs.size)
                GL.glGenBuffers(buffers.size, buffers, 0)
                val vao = IntArray(1)
                GL.glGenVertexArrays(1, vao, 0)
                GL.glBindVertexArray(vao[0])
                (listOf(source.positions, source.normals) + source.morphs).forEachIndexed { index, values ->
                    val slot = if (index < 2) index else index + 1
                    GL.glBindBuffer(GL.GL_ARRAY_BUFFER, buffers[slot])
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
                GL.glBufferData(GL.GL_ELEMENT_ARRAY_BUFFER, source.indices.size * 4, indices, GL.GL_STATIC_DRAW)
                GL.glBindVertexArray(0)
                val low = FloatArray(3) { Float.POSITIVE_INFINITY }
                val high = FloatArray(3) { Float.NEGATIVE_INFINITY }
                source.positions.forEachIndexed { i, value ->
                    low[i % 3] = minOf(low[i % 3], value)
                    high[i % 3] = maxOf(high[i % 3], value)
                }
                GpuPiece(
                    source,
                    buffers,
                    vao[0],
                    FloatArray(3) { (low[it] + high[it]) / 2 },
                    FloatArray(3) { ((high[it] - low[it]) / 2).coerceAtLeast(.001f) },
                )
            },
        )

    private fun attribute(
        index: Int,
        buffer: Int,
    ) {
        GL.glBindBuffer(GL.GL_ARRAY_BUFFER, buffer)
        GL.glEnableVertexAttribArray(index)
        GL.glVertexAttribPointer(index, 3, GL.GL_FLOAT, false, 0, 0)
    }

    private fun resize(
        w: Int,
        h: Int,
    ) {
        if (w == width && h == height) return
        width = w.coerceAtLeast(1)
        height = h.coerceAtLeast(1)
        if (receiver != 0) GL.glDeleteTextures(1, intArrayOf(receiver), 0)
        if (framebuffer != 0) GL.glDeleteFramebuffers(1, intArrayOf(framebuffer), 0)
        // sRGB storage keeps linear light precise in 8 bits; sampling decodes it back to linear.
        receiver = texture()
        val levels = log2(max(width, height).toFloat()).toInt() + 1
        GL.glTexStorage2D(GL.GL_TEXTURE_2D, levels, GL.GL_SRGB8_ALPHA8, width, height)
        GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MIN_FILTER, GL.GL_LINEAR_MIPMAP_LINEAR)
        val ids = IntArray(1)
        GL.glGenFramebuffers(1, ids, 0)
        framebuffer = ids[0]
        GL.glBindFramebuffer(GL.GL_FRAMEBUFFER, framebuffer)
        GL.glFramebufferTexture2D(GL.GL_FRAMEBUFFER, GL.GL_COLOR_ATTACHMENT0, GL.GL_TEXTURE_2D, receiver, 0)
        check(GL.glCheckFramebufferStatus(GL.GL_FRAMEBUFFER) == GL.GL_FRAMEBUFFER_COMPLETE)
        GL.glBindFramebuffer(GL.GL_FRAMEBUFFER, 0)
    }

    private fun loadArtwork(palette: OpalinePalette) {
        if (artwork != 0) GL.glDeleteTextures(1, intArrayOf(artwork), 0)
        artwork = texture()
        val options =
            BitmapFactory.Options().apply {
                inScaled = false
                inSampleSize = 2
            }
        val bitmap = assets.open("opaline-native/background-${palette.asset}.png").use { BitmapFactory.decodeStream(it, null, options) }
        requireNotNull(bitmap)
        artworkAspect = bitmap.width.toFloat() / bitmap.height
        GLUtils.texImage2D(GL.GL_TEXTURE_2D, 0, bitmap, 0)
        GL.glGenerateMipmap(GL.GL_TEXTURE_2D)
        GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MIN_FILTER, GL.GL_LINEAR_MIPMAP_LINEAR)
        bitmap.recycle()
        theme = palette
    }

    private fun texture(): Int {
        val ids = IntArray(1)
        GL.glGenTextures(1, ids, 0)
        GL.glBindTexture(GL.GL_TEXTURE_2D, ids[0])
        GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MIN_FILTER, GL.GL_LINEAR)
        GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MAG_FILTER, GL.GL_LINEAR)
        GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_S, GL.GL_CLAMP_TO_EDGE)
        GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_T, GL.GL_CLAMP_TO_EDGE)
        return ids[0]
    }

    private fun drawBackdrop(
        frame: OpalineFrame,
        linear: Boolean,
    ) {
        GL.glDisable(GL.GL_DEPTH_TEST)
        backdrop.use()
        backdrop.flag("uLinearOutput", linear)
        GL.glActiveTexture(GL.GL_TEXTURE0)
        GL.glBindTexture(GL.GL_TEXTURE_2D, artwork)
        backdrop.integer("uArtwork", 0)
        val aspect = width.toFloat() / height
        backdrop.vec2("uCrop", min(1f, aspect / artworkAspect), min(1f, artworkAspect / aspect))
        backdrop.color("uTint", frame.palette.background)
        backdrop.scalar("uTime", time)
        backdrop.scalar("uDim", frame.dim)
        GL.glDrawArrays(GL.GL_TRIANGLES, 0, 3)
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
        GL.glDeleteTextures(4, intArrayOf(artwork, receiver, dfg, environment), 0)
        GL.glDeleteFramebuffers(1, intArrayOf(framebuffer), 0)
        if (::surface.isInitialized) surface.dispose()
        if (::backdrop.isInitialized) backdrop.dispose()
    }

    private companion object {
        /** `uOpRadius` in src/materials.js: the contact glow radius in element units. */
        const val CONTACT_RADIUS = .42f

        /** `setInteraction` clamps excitation to [0, 4]. */
        const val MAX_EXCITATION = 4f

        /** `updateMaterials`: the film's thickness range drifts by 25 nm at 0.09 rad/s. */
        const val FILM_DRIFT = 25f
        const val FILM_DRIFT_RATE = .09f
    }
}
