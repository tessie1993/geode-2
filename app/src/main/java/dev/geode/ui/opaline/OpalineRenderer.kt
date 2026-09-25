package dev.geode.ui.opaline

import android.content.res.AssetManager
import android.graphics.BitmapFactory
import android.opengl.GLES30 as GL
import android.opengl.GLUtils
import android.opengl.Matrix
import androidx.compose.ui.geometry.Rect
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

internal data class OpalinePart(
    val id: Long,
    val element: String,
    val bounds: Rect,
    val clip: Rect,
    val value: Float,
    val selected: Boolean,
    val enabled: Boolean,
    val depth: Int,
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
)

/** One GLES 3 scene per Android window. All GPU objects are confined to the EGL thread. */
internal class OpalineRenderer(private val assets: AssetManager) {
    private class Instance(value: Float) {
        val pressure = OpalineSpring()
        val coordinate = OpalineSpring(value, damping = 0.8f)
        val selection = OpalineSpring()
        val reveal = OpalineSpring(0f, frequency = 2.5f, damping = 1f).apply { target = 1f }
        var touchX = 0.5f
        var touchY = 0.5f
        val pointers = mutableSetOf<Long>()
    }

    private data class GpuPiece(val source: OpalineMesh.Piece, val buffers: IntArray, val vao: Int)
    private data class GpuMesh(val source: OpalineMesh, val pieces: List<GpuPiece>)
    private val meshes = mutableMapOf<String, GpuMesh>()
    private val instances = mutableMapOf<Long, Instance>()
    private lateinit var surface: Program
    private lateinit var backdrop: Program
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

    fun create() {
        surface = Program(assets, "surface")
        backdrop = Program(assets, "backdrop")
        GL.glEnable(GL.GL_DEPTH_TEST)
        GL.glDepthFunc(GL.GL_LEQUAL)
    }

    fun touch(id: Long, pointer: Long, pressed: Boolean, x: Float, y: Float) {
        val instance = instances[id] ?: return
        if (pressed) instance.pointers.add(pointer) else instance.pointers.remove(pointer)
        instance.pressure.target = if (instance.pointers.isEmpty()) 0f else 1f
        instance.touchX = x.coerceIn(0f, 1f)
        instance.touchY = y.coerceIn(0f, 1f)
    }

    fun cancel(id: Long? = null) {
        (if (id == null) instances.values else listOfNotNull(instances[id])).forEach {
            it.pointers.clear()
            it.pressure.target = 0f
        }
    }

    fun render(frame: OpalineFrame, targetWidth: Int, targetHeight: Int, dt: Float) {
        resize(targetWidth, targetHeight)
        if (theme != frame.palette) loadArtwork(frame.palette)
        if (!frame.reducedMotion) time += dt * frame.motion
        val liveIds = frame.parts.mapTo(hashSetOf()) { it.id }
        instances.keys.retainAll(liveIds)
        Matrix.perspectiveM(projection, 0, 38f, width.toFloat() / height, 0.1f, 80f)
        GL.glViewport(0, 0, width, height)
        GL.glDisable(GL.GL_SCISSOR_TEST)
        GL.glBindFramebuffer(GL.GL_FRAMEBUFFER, framebuffer)
        drawBackdrop(frame)
        GL.glBindFramebuffer(GL.GL_FRAMEBUFFER, 0)
        drawBackdrop(frame)
        GL.glEnable(GL.GL_DEPTH_TEST)
        GL.glClear(GL.GL_DEPTH_BUFFER_BIT)
        surface.use()
        surface.matrix("uProjection", projection)
        surface.vec2("uViewport", width.toFloat(), height.toFloat())
        surface.scalar("uTime", time)
        surface.color("uAccent", frame.palette.accent)
        GL.glActiveTexture(GL.GL_TEXTURE0)
        GL.glBindTexture(GL.GL_TEXTURE_2D, receiver)
        surface.integer("uBackdrop", 0)
        if (frame.environment) drawEnvironment(frame)
        // Parents first, children last. Depth is cleared between semantic surfaces so nested
        // controls cannot disappear inside a parent's thick shell. Pieces retain real self-depth.
        frame.parts.sortedWith(compareBy<OpalinePart> { it.depth }.thenByDescending { it.bounds.width * it.bounds.height }).forEach { part ->
            if (part.bounds.width > 1f && part.bounds.height > 1f && part.clip.width > 0f && part.clip.height > 0f) {
                val instance = instances.getOrPut(part.id) { Instance(part.value) }
                instance.coordinate.target = part.value
                instance.selection.target = if (part.selected) 1f else 0f
                if (frame.reducedMotion) {
                    instance.pressure.reset(0f)
                    instance.coordinate.reset()
                    instance.selection.reset()
                    instance.reveal.reset(1f)
                } else {
                    instance.pressure.step(dt)
                    instance.coordinate.step(dt)
                    instance.selection.step(dt)
                    instance.reveal.step(dt)
                }
                drawPart(part, instance, frame)
            }
        }
        GL.glDisable(GL.GL_SCISSOR_TEST)
        check(GL.glGetError() == GL.GL_NO_ERROR) { "Opaline native draw failed" }
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

    private fun drawPart(part: OpalinePart, instance: Instance, frame: OpalineFrame, decorative: Boolean = false) {
        val mesh = meshes.getOrPut(part.element) { upload(OpalineMesh.read(assets.open("opaline-native/${part.element}.glb").use { it.readBytes() })) }
        val bounds = part.bounds
        val clip = part.clip.intersect(Rect(0f, 0f, frame.width, frame.height))
        val sx = width / frame.width
        val sy = height / frame.height
        GL.glEnable(GL.GL_SCISSOR_TEST)
        GL.glScissor((clip.left * sx).toInt().coerceAtLeast(0), ((frame.height - clip.bottom) * sy).toInt().coerceAtLeast(0),
            (clip.width * sx).toInt().coerceAtLeast(0), (clip.height * sy).toInt().coerceAtLeast(0))
        GL.glClear(GL.GL_DEPTH_BUFFER_BIT)
        val source = mesh.source
        val sizeX = (source.maximum[0] - source.minimum[0]).coerceAtLeast(.001f)
        val sizeY = (source.maximum[1] - source.minimum[1]).coerceAtLeast(.001f)
        val sizeZ = (source.maximum[2] - source.minimum[2]).coerceAtLeast(.001f)
        val distance = 16f + if (decorative) 3f else 0f
        val perPixel = (2f * distance * tan(Math.toRadians(19.0))).toFloat() / frame.height
        val lift = if (frame.reducedMotion) 0f else (1f - instance.reveal.value).coerceIn(0f, 1f) * .35f
        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, (bounds.center.x - frame.width / 2) * perPixel,
            (frame.height / 2 - bounds.center.y) * perPixel + lift, -distance)
        // Front plane remains aligned; controlled tilt reveals curved rims and undersides.
        Matrix.rotateM(model, 0, if (decorative) -16f else -3f, 1f, 0f, 0f)
        Matrix.rotateM(model, 0, if (decorative) 18f else (instance.touchX - .5f) * instance.pressure.value * 3f, 0f, 1f, 0f)
        val pixelDepth = min(bounds.width, bounds.height) * if (part.element.startsWith("C")) .13f else .24f
        Matrix.scaleM(model, 0, bounds.width * perPixel * .92f / sizeX, bounds.height * perPixel * .90f / sizeY,
            pixelDepth * perPixel / sizeZ)
        Matrix.translateM(model, 0, -(source.minimum[0] + source.maximum[0]) / 2,
            -(source.minimum[1] + source.maximum[1]) / 2, -(source.minimum[2] + source.maximum[2]) / 2)
        contact[0] = source.minimum[0] + instance.touchX * sizeX
        contact[1] = source.maximum[1] - instance.touchY * sizeY
        contact[2] = source.maximum[2]
        contact[3] = 1f
        surface.scalar("uPressure", instance.pressure.value * frame.motion)
        surface.scalar("uSelected", instance.selection.value)
        surface.scalar("uEnabled", if (part.enabled) 1f else 0f)
        surface.scalar("uReveal", instance.reveal.value)
        for (piece in mesh.pieces) drawPiece(piece, instance, frame)
    }

    private fun drawPiece(piece: GpuPiece, instance: Instance, frame: OpalineFrame) {
        val source = piece.source
        val pose = source.transform.copyOf()
        val value = instance.coordinate.value.coerceIn(0f, 1f)
        if (source.motion == "slider") {
            val travel = source.travel[3] + source.travel[4] * value
            for (i in 0..2) pose[12 + i] += source.travel[i] * travel
            val stretch = 1f + min(.28f, abs(instance.coordinate.velocity) * .12f)
            Matrix.scaleM(pose, 0, stretch, 1f / sqrt(stretch), 1f / sqrt(stretch))
        } else if (source.motion == "dial" || source.motion == "wheel") {
            Matrix.rotateM(pose, 0, -(value - .5f) * 288f, 0f, 0f, 1f)
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
        surface.vec3("uContact", localContact)
        surface.scalar("uDeform", if (source.deformable) 1f else 0f)
        surface.vec3("uBase", source.color)
        surface.vec3("uAbsorption", source.attenuation)
        surface.vec3("uEmission", source.emission)
        surface.vec4("uOptics", source.roughness, source.transmission, source.ior, source.thickness)
        val cloud = when (source.family) { "pigment" -> .78f; "gel" -> .18f; "blue" -> .14f; "nacre" -> .27f; else -> 0f }
        surface.vec4("uFinish", source.iridescence, cloud, source.clearcoat, source.attenuationDistance)
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

    private fun upload(mesh: OpalineMesh): GpuMesh = GpuMesh(mesh, mesh.pieces.map { source ->
        val buffers = IntArray(3 + source.morphs.size)
        GL.glGenBuffers(buffers.size, buffers, 0)
        val vao = IntArray(1)
        GL.glGenVertexArrays(1, vao, 0)
        GL.glBindVertexArray(vao[0])
        (listOf(source.positions, source.normals) + source.morphs).forEachIndexed { index, values ->
            val slot = if (index < 2) index else index + 1
            GL.glBindBuffer(GL.GL_ARRAY_BUFFER, buffers[slot])
            val data = ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(values)
            data.position(0)
            GL.glBufferData(GL.GL_ARRAY_BUFFER, values.size * 4, data, GL.GL_STATIC_DRAW)
        }
        attribute(0, buffers[0]); attribute(1, buffers[1])
        GL.glBindBuffer(GL.GL_ELEMENT_ARRAY_BUFFER, buffers[2])
        val indices = ByteBuffer.allocateDirect(source.indices.size * 4).order(ByteOrder.nativeOrder()).asIntBuffer().put(source.indices)
        indices.position(0)
        GL.glBufferData(GL.GL_ELEMENT_ARRAY_BUFFER, source.indices.size * 4, indices, GL.GL_STATIC_DRAW)
        GL.glBindVertexArray(0)
        GpuPiece(source, buffers, vao[0])
    })

    private fun attribute(index: Int, buffer: Int) {
        GL.glBindBuffer(GL.GL_ARRAY_BUFFER, buffer)
        GL.glEnableVertexAttribArray(index)
        GL.glVertexAttribPointer(index, 3, GL.GL_FLOAT, false, 0, 0)
    }

    private fun resize(w: Int, h: Int) {
        if (w == width && h == height) return
        width = w.coerceAtLeast(1); height = h.coerceAtLeast(1)
        if (receiver != 0) GL.glDeleteTextures(1, intArrayOf(receiver), 0)
        if (framebuffer != 0) GL.glDeleteFramebuffers(1, intArrayOf(framebuffer), 0)
        receiver = texture()
        GL.glTexImage2D(GL.GL_TEXTURE_2D, 0, GL.GL_RGBA, width, height, 0, GL.GL_RGBA, GL.GL_UNSIGNED_BYTE, null)
        val ids = IntArray(1)
        GL.glGenFramebuffers(1, ids, 0); framebuffer = ids[0]
        GL.glBindFramebuffer(GL.GL_FRAMEBUFFER, framebuffer)
        GL.glFramebufferTexture2D(GL.GL_FRAMEBUFFER, GL.GL_COLOR_ATTACHMENT0, GL.GL_TEXTURE_2D, receiver, 0)
        check(GL.glCheckFramebufferStatus(GL.GL_FRAMEBUFFER) == GL.GL_FRAMEBUFFER_COMPLETE)
        GL.glBindFramebuffer(GL.GL_FRAMEBUFFER, 0)
    }

    private fun loadArtwork(palette: OpalinePalette) {
        if (artwork != 0) GL.glDeleteTextures(1, intArrayOf(artwork), 0)
        artwork = texture()
        val options = BitmapFactory.Options().apply { inScaled = false; inSampleSize = 2 }
        val bitmap = assets.open("opaline-native/background-${palette.asset}.png").use { BitmapFactory.decodeStream(it, null, options) }
        requireNotNull(bitmap)
        artworkAspect = bitmap.width.toFloat() / bitmap.height
        GLUtils.texImage2D(GL.GL_TEXTURE_2D, 0, bitmap, 0)
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

    private fun drawBackdrop(frame: OpalineFrame) {
        GL.glDisable(GL.GL_DEPTH_TEST)
        backdrop.use()
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
        meshes.values.forEach { mesh -> mesh.pieces.forEach { piece ->
            GL.glDeleteBuffers(piece.buffers.size, piece.buffers, 0)
            GL.glDeleteVertexArrays(1, intArrayOf(piece.vao), 0)
        } }
        meshes.clear(); instances.clear()
        GL.glDeleteTextures(2, intArrayOf(artwork, receiver), 0)
        GL.glDeleteFramebuffers(1, intArrayOf(framebuffer), 0)
        if (::surface.isInitialized) surface.dispose()
        if (::backdrop.isInitialized) backdrop.dispose()
    }
}

private class Program(assets: AssetManager, name: String) {
    private val id = GL.glCreateProgram()
    private val locations = mutableMapOf<String, Int>()
    init {
        val shaders = listOf(GL.GL_VERTEX_SHADER to "vert", GL.GL_FRAGMENT_SHADER to "frag").map { (type, suffix) ->
            val shader = GL.glCreateShader(type)
            val source = assets.open("opaline-native/shaders/$name.$suffix").bufferedReader().use { it.readText() }
            GL.glShaderSource(shader, source); GL.glCompileShader(shader)
            val status = IntArray(1); GL.glGetShaderiv(shader, GL.GL_COMPILE_STATUS, status, 0)
            check(status[0] != 0) { GL.glGetShaderInfoLog(shader) }
            GL.glAttachShader(id, shader)
            shader
        }
        GL.glLinkProgram(id)
        shaders.forEach { GL.glDeleteShader(it) }
        val status = IntArray(1); GL.glGetProgramiv(id, GL.GL_LINK_STATUS, status, 0)
        check(status[0] != 0) { GL.glGetProgramInfoLog(id) }
    }
    fun location(name: String): Int = locations.getOrPut(name) { GL.glGetUniformLocation(id, name) }
    fun use() = GL.glUseProgram(id)
    fun scalar(name: String, value: Float) = GL.glUniform1f(location(name), value)
    fun integer(name: String, value: Int) = GL.glUniform1i(location(name), value)
    fun vec2(name: String, x: Float, y: Float) = GL.glUniform2f(location(name), x, y)
    fun vec3(name: String, v: FloatArray) = GL.glUniform3f(location(name), v[0], v[1], v[2])
    fun vec4(name: String, x: Float, y: Float, z: Float, w: Float) = GL.glUniform4f(location(name), x, y, z, w)
    fun matrix(name: String, values: FloatArray) = GL.glUniformMatrix4fv(location(name), 1, false, values, 0)
    fun color(name: String, color: Int) = GL.glUniform3f(location(name),
        ((color shr 16 and 255) / 255f).pow(2.2f), ((color shr 8 and 255) / 255f).pow(2.2f), ((color and 255) / 255f).pow(2.2f))
    fun dispose() = GL.glDeleteProgram(id)
}
