package dev.geode.ui.opaline

import android.content.res.AssetManager
import android.graphics.BitmapFactory
import android.opengl.GLUtils
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log2
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt
import android.opengl.GLES30 as GL

/**
 * The abstract liquid-mineral realm of src/workbench.js `buildEnvironment`, in library metres:
 * the theme's distant backdrop plate, the planar Reflector under the animated water, the floor,
 * three stone shelves, sixteen islands, 52 glow motes and six mist planes. It is seen from the
 * workbench world camera ([view]); the renderer places UI parts in that camera's view space.
 * Decorations here never take input. EGL thread only; [linearFormat] is the colour format of the
 * linear targets (RGBA16F where the device renders half floats).
 */
internal class OpalineRealm(
    private val assets: AssetManager,
    private val linearFormat: Int,
) {
    /** A static mesh with positions and normals at attributes 0 and 1. */
    class Geometry(
        positions: FloatArray,
        normals: FloatArray,
        indices: IntArray,
    ) {
        private val buffers = IntArray(3)
        private val count = indices.size
        private val vao = IntArray(1)

        init {
            GL.glGenVertexArrays(1, vao, 0)
            GL.glBindVertexArray(vao[0])
            GL.glGenBuffers(3, buffers, 0)
            for ((slot, values) in listOf(positions, normals).withIndex()) {
                val data = ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder())
                GL.glBindBuffer(GL.GL_ARRAY_BUFFER, buffers[slot])
                GL.glBufferData(
                    GL.GL_ARRAY_BUFFER,
                    values.size * 4,
                    data.asFloatBuffer().put(values).position(0),
                    GL.GL_STATIC_DRAW,
                )
                GL.glEnableVertexAttribArray(slot)
                GL.glVertexAttribPointer(slot, 3, GL.GL_FLOAT, false, 0, 0)
            }
            val data = ByteBuffer.allocateDirect(count * 4).order(ByteOrder.nativeOrder())
            GL.glBindBuffer(GL.GL_ELEMENT_ARRAY_BUFFER, buffers[2])
            GL.glBufferData(
                GL.GL_ELEMENT_ARRAY_BUFFER,
                count * 4,
                data.asIntBuffer().put(indices).position(0),
                GL.GL_STATIC_DRAW,
            )
            GL.glBindVertexArray(0)
        }

        fun draw() {
            GL.glBindVertexArray(vao[0])
            GL.glDrawElements(GL.GL_TRIANGLES, count, GL.GL_UNSIGNED_INT, 0)
            GL.glBindVertexArray(0)
        }

        fun dispose() {
            GL.glDeleteBuffers(3, buffers, 0)
            GL.glDeleteVertexArrays(1, vao, 0)
        }
    }

    /** A lit realm mesh: a createMaterials family (or [FLOOR]) placed by [model], in metres. */
    class Body(
        val geometry: Geometry,
        val family: String,
        val model: FloatArray,
    )

    /** Realm metres to view space: buildWorld's `frameScene({position, target})`, up +Y. */
    val view =
        FloatArray(16).also {
            Matrix.setLookAtM(it, 0, 8f, 5.4f, 13.3f, 2.8f, 2.1f, 0f, 0f, 1f, 0f)
        }
    val inverseView = FloatArray(16).also { Matrix.invertM(it, 0, view, 0) }

    /** Reflector.onBeforeRender: the camera mirrored in the plane y = [MIRROR_Y]. */
    val reflectionView = FloatArray(16)
    val reflectionProjection = FloatArray(16)
    private val reflectionTexture = FloatArray(16)

    /** The sun's shadow camera over the realm and realm metres to its map (texture space). */
    val shadowView = FloatArray(16)
    val shadowProjection = FloatArray(16)
    val shadowMatrix = FloatArray(16)

    private val program = OpalineProgram(assets, "realm")
    private val plate = plane(PLATE_WIDTH, PLATE_HEIGHT, 1, flat = false)
    private val plateModel = compose(-8f, 12f, -40f, ry = .15f)
    private val mirror = plane(WATER_SIZE, WATER_SIZE, 1, flat = true)
    private val mirrorModel = compose(y = MIRROR_Y)
    private val mist = plane(2f, 2f, 1, flat = false)
    private val mistModels =
        List(6) { compose(sin(it.toFloat()) * 10f, 1.2f, -5f - it * 4f, sx = 17f, sy = 3f) }

    val water =
        Body(
            plane(WATER_SIZE, WATER_SIZE, WATER_SEGMENTS, flat = true),
            "water",
            compose(y = WATER_Y),
        )
    val floor = Body(plane(120f, 120f, 1, flat = true), FLOOR, compose(y = -.62f))

    /** Shelves (sphere 64×40) and the stone islands: opaque shadow casters. */
    val stones: List<Body>

    /** Every third island is nacre: transmissive, and a shadow caster. */
    val nacre: List<Body>

    /** The 52 glow motes; their models drift in [update]. */
    val motes: List<Body>

    init {
        val sphere = sphere(1f, 64, 40)
        val shelves =
            List(3) { i ->
                val side = if (i % 2 == 1) 1f else -1f
                val model =
                    compose(
                        side * (13f + i * 2.4f),
                        -.2f + i * .13f,
                        -9f - i * 3.2f,
                        0f,
                        i * .58f,
                        0f,
                        5f + i * .48f,
                        .7f + i * .11f,
                        3f + i * .24f,
                    )
                Body(sphere, "stone", model)
            }
        val icosahedron = icosahedron(4)
        val islands =
            List(16) { i ->
                val a = i * 2.39996f
                val r = 6.5f + i * .52f
                val model =
                    compose(
                        cos(a) * r,
                        -.04f + sin(i.toFloat()) * .1f,
                        sin(a) * r - 3f,
                        i * .34f,
                        i * .22f,
                        i * .12f,
                        .45f + (i % 4) * .17f,
                        .18f + (i % 3) * .07f,
                        .37f + (i % 5) * .11f,
                    )
                Body(icosahedron, if (i % 3 == 0) "nacre" else "stone", model)
            }
        stones = shelves + islands.filter { it.family == "stone" }
        nacre = islands.filter { it.family == "nacre" }
        val glow = sphere(MOTE_RADIUS, 10, 8)
        motes = List(MOTES) { Body(glow, "glow", FloatArray(16)) }
    }

    /** `palette[theme][0]`: the scene background and fog colour. */
    var fogColor = 0
        private set
    private var mistColor = 0
    private var plateTexture = 0
    private var time = 0f

    private val impulses = ArrayDeque<FloatArray>()
    private val ripples = FloatArray(32)
    private val normalRipples = FloatArray(32)
    private var waterTime = 0f
    private var normalTime = 0f

    /** False until the water's first animated frame: it stays the flat rest plane until then. */
    var waves = false
        private set

    /**
     * The sun's realm shadow map and the Reflector's targets, allocated on first use, so hosts that
     * never draw the realm never hold them.
     */
    private inner class Targets {
        val shadowMap = depthTexture(OpalineLightRig.SHADOW_MAP_SIZE)
        val shadowFramebuffer = framebuffer(depth = shadowMap)
        val reflectionMap = texture()
        val reflectionFramebuffer: Int
        val multisample = IntArray(2)
        val multisampleFramebuffer: Int

        init {
            val levels = log2(REFLECTION_SIZE.toFloat()).toInt() + 1
            val size = REFLECTION_SIZE
            GL.glTexStorage2D(GL.GL_TEXTURE_2D, levels, linearFormat, size, size)
            GL.glTexParameteri(
                GL.GL_TEXTURE_2D,
                GL.GL_TEXTURE_MIN_FILTER,
                GL.GL_LINEAR_MIPMAP_LINEAR,
            )
            reflectionFramebuffer = framebuffer(color = reflectionMap)
            // Reflector's render target: `samples: multisample` (4), as far as the format allows.
            val supported = IntArray(1)
            GL.glGetInternalformativ(
                GL.GL_RENDERBUFFER,
                linearFormat,
                GL.GL_SAMPLES,
                1,
                supported,
                0,
            )
            val samples = min(REFLECTION_SAMPLES, supported[0])
            GL.glGenRenderbuffers(2, multisample, 0)
            for ((index, format) in listOf(linearFormat, GL.GL_DEPTH_COMPONENT24).withIndex()) {
                GL.glBindRenderbuffer(GL.GL_RENDERBUFFER, multisample[index])
                GL.glRenderbufferStorageMultisample(
                    GL.GL_RENDERBUFFER,
                    samples,
                    format,
                    size,
                    size,
                )
            }
            val ids = IntArray(1)
            GL.glGenFramebuffers(1, ids, 0)
            multisampleFramebuffer = ids[0]
            GL.glBindFramebuffer(GL.GL_FRAMEBUFFER, multisampleFramebuffer)
            val attachments = listOf(GL.GL_COLOR_ATTACHMENT0, GL.GL_DEPTH_ATTACHMENT)
            for ((index, attachment) in attachments.withIndex()) {
                GL.glFramebufferRenderbuffer(
                    GL.GL_FRAMEBUFFER,
                    attachment,
                    GL.GL_RENDERBUFFER,
                    multisample[index],
                )
            }
            check(GL.glCheckFramebufferStatus(GL.GL_FRAMEBUFFER) == GL.GL_FRAMEBUFFER_COMPLETE)
            GL.glBindFramebuffer(GL.GL_FRAMEBUFFER, 0)
        }

        fun dispose() {
            GL.glDeleteTextures(2, intArrayOf(shadowMap, reflectionMap), 0)
            GL.glDeleteRenderbuffers(2, multisample, 0)
            GL.glDeleteFramebuffers(
                3,
                intArrayOf(shadowFramebuffer, reflectionFramebuffer, multisampleFramebuffer),
                0,
            )
        }
    }

    private val targets = lazy { Targets() }

    val shadowMap: Int get() = targets.value.shadowMap

    val reflectionMap: Int get() = targets.value.reflectionMap

    init {
        val sun = OpalineLightRig.sunPosition
        Matrix.setLookAtM(shadowView, 0, sun[0], sun[1], sun[2], 0f, 0f, 0f, 0f, 1f, 0f)
        val extent = OpalineLightRig.SHADOW_EXTENT
        Matrix.orthoM(
            shadowProjection,
            0,
            -extent,
            extent,
            -extent,
            extent,
            OpalineLightRig.SHADOW_NEAR,
            OpalineLightRig.SHADOW_FAR,
        )
        Matrix.multiplyMM(shadowMatrix, 0, shadowProjection, 0, shadowView, 0)
        Matrix.multiplyMM(shadowMatrix, 0, BIAS, 0, shadowMatrix.copyOf(), 0)
        reflect()
    }

    /** L18 moves the camera: [next] becomes the view, and the Reflector's mirror follows. */
    fun look(next: FloatArray) {
        next.copyInto(view)
        Matrix.invertM(inverseView, 0, view, 0)
        reflect()
    }

    /** The mirrored camera of [view]: position, look-at point and up reflected in the plane. */
    private fun reflect() {
        val eye = floatArrayOf(inverseView[12], inverseView[13], inverseView[14])
        // Camera forward (-Z) and up (+Y) in realm space are rows of the view rotation.
        val forward = floatArrayOf(-view[2], -view[6], -view[10])
        val up = floatArrayOf(view[1], view[5], view[9])
        Matrix.setLookAtM(
            reflectionView,
            0,
            eye[0],
            2 * MIRROR_Y - eye[1],
            eye[2],
            eye[0] + forward[0],
            2 * MIRROR_Y - eye[1] - forward[1],
            eye[2] + forward[2],
            up[0],
            -up[1],
            up[2],
        )
    }

    /**
     * The reflection camera's projection for this aspect: the texture matrix from the plain
     * projection, then the oblique near plane on the mirror (clipBias .003).
     */
    fun project(projection: FloatArray) {
        projection.copyInto(reflectionProjection)
        Matrix.multiplyMM(reflectionTexture, 0, reflectionView, 0, mirrorModel, 0)
        Matrix.multiplyMM(reflectionTexture, 0, projection, 0, reflectionTexture.copyOf(), 0)
        Matrix.multiplyMM(reflectionTexture, 0, BIAS, 0, reflectionTexture.copyOf(), 0)
        // The plane y = MIRROR_Y, normal +Y, carried into the reflection camera's view.
        val nx = reflectionView[4]
        val ny = reflectionView[5]
        val nz = reflectionView[6]
        val v = reflectionView
        // Plane.applyMatrix4: the unit normal rotated, the constant from the moved point (0, y, 0).
        val constant = -(nx * v[12] + ny * v[13] + nz * v[14]) - MIRROR_Y
        val p = reflectionProjection
        val qx = (sign(nx) + p[8]) / p[0]
        val qy = (sign(ny) + p[9]) / p[5]
        val qw = (1f + p[10]) / p[14]
        val scale = 2f / (nx * qx + ny * qy - nz + constant * qw)
        p[2] = nx * scale
        p[6] = ny * scale
        p[10] = nz * scale + 1f - CLIP_BIAS
        p[14] = constant * scale
    }

    fun theme(palette: OpalinePalette) {
        val colors = palette(palette)
        fogColor = colors[0]
        mistColor = colors[1]
        if (plateTexture != 0) GL.glDeleteTextures(1, intArrayOf(plateTexture), 0)
        plateTexture = texture()
        val bitmap =
            assets.open("opaline-native/background-${palette.asset}.png").use {
                BitmapFactory.decodeStream(it)
            }
        requireNotNull(bitmap)
        GLUtils.texImage2D(GL.GL_TEXTURE_2D, 0, bitmap, 0)
        GL.glGenerateMipmap(GL.GL_TEXTURE_2D)
        GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MIN_FILTER, GL.GL_LINEAR_MIPMAP_LINEAR)
        bitmap.recycle()
    }

    /** The workbench bus `touch` listener: an impulse at realm (x, z); the eight newest stay. */
    fun touch(
        x: Float,
        z: Float,
        strength: Float,
    ) {
        impulses.addLast(floatArrayOf(x, z, time, strength))
        if (impulses.size > MAX_IMPULSES) impulses.removeFirst()
    }

    /**
     * renderFrame: the motes drift on the shared clock; the water takes this frame's heights only
     * while motion runs, and its normals every third frame.
     */
    fun update(
        time: Float,
        motion: Boolean,
        frame: Long,
    ) {
        this.time = time
        for ((i, mote) in motes.withIndex()) {
            val phase = i.toFloat()
            Matrix.setIdentityM(mote.model, 0)
            Matrix.translateM(
                mote.model,
                0,
                sin(i * 33.12f) * 10f + sin(time * .18f + phase) * .25f,
                .2f + (i % 11) * .24f + sin(time * .33f + phase) * .17f,
                cos(i * 15.41f) * 8f - 2f + cos(time * .21f + phase) * .24f,
            )
        }
        if (!motion) return
        waves = true
        waterTime = time
        snapshot(ripples)
        if (frame % 3 == 0L) {
            normalTime = time
            snapshot(normalRipples)
        }
    }

    private fun snapshot(out: FloatArray) {
        out.fill(0f)
        impulses.forEachIndexed { i, impulse -> impulse.copyInto(out, i * 4) }
    }

    /** Water uniforms of the surface pass (see surface.vert). */
    fun bindWater(surface: OpalineProgram) {
        surface.scalar("uWaterTime", waterTime)
        surface.scalar("uWaterNormalTime", normalTime)
        surface.scalar("uWaterStep", WATER_SIZE / WATER_SEGMENTS)
        surface.scalar("uWaterHalf", WATER_SIZE / 2)
        surface.vec4s("uRipples", ripples)
        surface.vec4s("uNormalRipples", normalRipples)
    }

    fun bindShadow() {
        val size = OpalineLightRig.SHADOW_MAP_SIZE
        GL.glBindFramebuffer(GL.GL_FRAMEBUFFER, targets.value.shadowFramebuffer)
        GL.glViewport(0, 0, size, size)
        GL.glClear(GL.GL_DEPTH_BUFFER_BIT)
    }

    fun bindReflection() {
        GL.glBindFramebuffer(GL.GL_FRAMEBUFFER, targets.value.multisampleFramebuffer)
        GL.glViewport(0, 0, REFLECTION_SIZE, REFLECTION_SIZE)
        GL.glClearColor(0f, 0f, 0f, 0f)
        GL.glClear(GL.GL_COLOR_BUFFER_BIT or GL.GL_DEPTH_BUFFER_BIT)
    }

    /**
     * Resolves the multisampled reflection into [reflectionMap]; with [mipmaps], its levels serve
     * as the reflection pass's own transmission capture before rendering continues.
     */
    fun resolveReflection(mipmaps: Boolean) {
        val current = targets.value
        GL.glBindFramebuffer(GL.GL_READ_FRAMEBUFFER, current.multisampleFramebuffer)
        GL.glBindFramebuffer(GL.GL_DRAW_FRAMEBUFFER, current.reflectionFramebuffer)
        GL.glBlitFramebuffer(
            0,
            0,
            REFLECTION_SIZE,
            REFLECTION_SIZE,
            0,
            0,
            REFLECTION_SIZE,
            REFLECTION_SIZE,
            GL.GL_COLOR_BUFFER_BIT,
            GL.GL_NEAREST,
        )
        GL.glBindFramebuffer(GL.GL_FRAMEBUFFER, current.multisampleFramebuffer)
        if (mipmaps) {
            GL.glBindTexture(GL.GL_TEXTURE_2D, current.reflectionMap)
            GL.glGenerateMipmap(GL.GL_TEXTURE_2D)
        }
    }

    /** `scene.background`: a full-screen triangle behind everything. */
    fun drawBackground(
        linear: Boolean,
        light: Float,
    ) {
        GL.glDisable(GL.GL_DEPTH_TEST)
        GL.glDisable(GL.GL_CULL_FACE)
        use(BACKGROUND, fogColor, linear, light)
        GL.glDrawArrays(GL.GL_TRIANGLES, 0, 3)
        GL.glEnable(GL.GL_DEPTH_TEST)
    }

    /** The distant plate: `color 0x7899b8`, the theme's backdrop map, fog on, no depth write. */
    fun drawPlate(
        view: FloatArray,
        projection: FloatArray,
        linear: Boolean,
        light: Float,
    ) {
        use(PLATE, PLATE_COLOR, linear, light)
        program.vec2("uPlateSize", PLATE_WIDTH, PLATE_HEIGHT)
        program.scalar("uFogDensity", FOG_DENSITY)
        program.color("uFogColor", fogColor)
        GL.glActiveTexture(GL.GL_TEXTURE5)
        GL.glBindTexture(GL.GL_TEXTURE_2D, plateTexture)
        program.integer("uMap", 5)
        GL.glDepthMask(false)
        draw(plate, plateModel, view, projection, cull = true)
        GL.glDepthMask(true)
    }

    /** The Reflector plane (`color 0x496c8e`): the reflection blended by overlay. */
    fun drawMirror(
        view: FloatArray,
        projection: FloatArray,
        linear: Boolean,
        light: Float,
    ) {
        use(MIRROR, MIRROR_COLOR, linear, light)
        program.matrix("uTextureMatrix", reflectionTexture)
        GL.glActiveTexture(GL.GL_TEXTURE5)
        GL.glBindTexture(GL.GL_TEXTURE_2D, reflectionMap)
        program.integer("uMap", 5)
        draw(mirror, mirrorModel, view, projection, cull = true)
    }

    /** Six double-sided mist planes, normal blending, back to front, no depth write. */
    fun drawMist(
        view: FloatArray,
        projection: FloatArray,
        linear: Boolean,
        light: Float,
    ) {
        use(MIST, mistColor, linear, light)
        GL.glEnable(GL.GL_BLEND)
        GL.glBlendFuncSeparate(
            GL.GL_SRC_ALPHA,
            GL.GL_ONE_MINUS_SRC_ALPHA,
            GL.GL_ONE,
            GL.GL_ONE_MINUS_SRC_ALPHA,
        )
        GL.glDepthMask(false)
        for (model in mistModels.asReversed()) draw(mist, model, view, projection, cull = false)
        GL.glDepthMask(true)
        GL.glDisable(GL.GL_BLEND)
    }

    private val lines = IntArray(2)

    /**
     * [count] line vertices under [modelView]: the G07 trails (workbench `LineBasicMaterial`
     * 0x99d8f2, opacity .22, transparent), blended over the scene without depth writes.
     */
    fun drawLines(
        vertices: FloatArray,
        count: Int,
        modelView: FloatArray,
        projection: FloatArray,
        linear: Boolean,
    ) {
        if (lines[0] == 0) {
            GL.glGenVertexArrays(1, lines, 0)
            GL.glGenBuffers(1, lines, 1)
            GL.glBindVertexArray(lines[0])
            GL.glBindBuffer(GL.GL_ARRAY_BUFFER, lines[1])
            GL.glEnableVertexAttribArray(0)
            GL.glVertexAttribPointer(0, 3, GL.GL_FLOAT, false, 0, 0)
        }
        use(LINES, TRAIL_COLOR, linear, 1f)
        program.scalar("uOpacity", TRAIL_OPACITY)
        program.matrix("uModel", modelView)
        program.matrix("uProjection", projection)
        GL.glBindVertexArray(lines[0])
        GL.glBindBuffer(GL.GL_ARRAY_BUFFER, lines[1])
        val data = ByteBuffer.allocateDirect(count * 12).order(ByteOrder.nativeOrder())
        GL.glBufferData(
            GL.GL_ARRAY_BUFFER,
            count * 12,
            data.asFloatBuffer().put(vertices, 0, count * 3).position(0),
            GL.GL_STREAM_DRAW,
        )
        GL.glEnable(GL.GL_BLEND)
        GL.glBlendFuncSeparate(
            GL.GL_SRC_ALPHA,
            GL.GL_ONE_MINUS_SRC_ALPHA,
            GL.GL_ONE,
            GL.GL_ONE_MINUS_SRC_ALPHA,
        )
        GL.glDepthMask(false)
        GL.glDrawArrays(GL.GL_LINES, 0, count)
        GL.glDepthMask(true)
        GL.glDisable(GL.GL_BLEND)
        GL.glBindVertexArray(0)
    }

    private fun use(
        mode: Int,
        color: Int,
        linear: Boolean,
        light: Float,
    ) {
        program.use()
        program.integer("uMode", mode)
        program.color("uColor", color)
        program.flag("uLinearOutput", linear)
        program.scalar("uLight", light)
        program.scalar("uFogDensity", 0f)
        program.scalar("toneMappingExposure", OpalineLightRig.TONE_MAPPING_EXPOSURE)
    }

    private val modelView = FloatArray(16)

    private fun draw(
        geometry: Geometry,
        model: FloatArray,
        view: FloatArray,
        projection: FloatArray,
        cull: Boolean,
    ) {
        if (cull) GL.glEnable(GL.GL_CULL_FACE) else GL.glDisable(GL.GL_CULL_FACE)
        GL.glFrontFace(GL.GL_CCW)
        Matrix.multiplyMM(modelView, 0, view, 0, model, 0)
        program.matrix("uModel", modelView)
        program.matrix("uProjection", projection)
        geometry.draw()
    }

    fun dispose() {
        val bodies = listOf(water, floor) + stones + nacre + motes
        val geometries = listOf(plate, mirror, mist) + bodies.map { it.geometry }
        geometries.distinct().forEach { it.dispose() }
        program.dispose()
        GL.glDeleteVertexArrays(1, lines, 0)
        GL.glDeleteBuffers(1, lines, 1)
        GL.glDeleteTextures(1, intArrayOf(plateTexture), 0)
        if (targets.isInitialized()) targets.value.dispose()
    }

    companion object {
        /** The workbench floor, a plain MeshPhysicalMaterial rather than a materials.js family. */
        const val FLOOR = "floor"

        /** `scene.fog = new THREE.FogExp2(palette[theme][0], .025)`. */
        const val FOG_DENSITY = .025f

        private const val BACKGROUND = 0
        private const val PLATE = 1
        private const val MIRROR = 2
        private const val MIST = 3
        private const val LINES = 4
        private const val TRAIL_COLOR = 0x99D8F2
        private const val TRAIL_OPACITY = .22f

        /** `water.position.y = -.12`. */
        const val WATER_Y = -.12f

        /** The glow motes' `SphereGeometry(.025, 10, 8)`, reused for particles. */
        const val MOTE_RADIUS = .025f

        private const val PLATE_WIDTH = 72f
        private const val PLATE_HEIGHT = 40f
        private const val PLATE_COLOR = 0x7899B8
        private const val WATER_SIZE = 70f
        private const val WATER_SEGMENTS = 180
        private const val MIRROR_Y = -.2f
        private const val MIRROR_COLOR = 0x496C8E
        private const val CLIP_BIAS = .003f

        /** Reflector `textureWidth` / `textureHeight`. */
        const val REFLECTION_SIZE = 1536
        private const val REFLECTION_SAMPLES = 4
        private const val MAX_IMPULSES = 8
        private const val MOTES = 52
        private const val ICOSAHEDRON_FACES =
            "0 11 5 0 5 1 0 1 7 0 7 10 0 10 11 1 5 9 5 11 4 11 10 2 10 7 6 7 1 8 " +
                "3 9 4 3 4 2 3 2 6 3 6 8 3 8 9 4 9 5 2 4 11 6 2 10 8 6 7 9 8 1"

        /** Clip space to texture space: three.js's shadow and Reflector texture bias. */
        val BIAS =
            FloatArray(16).also {
                Matrix.setIdentityM(it, 0)
                Matrix.translateM(it, 0, .5f, .5f, .5f)
                Matrix.scaleM(it, 0, .5f, .5f, .5f)
            }

        /** `floorMat`: 0x102f50 for Tidal, else the theme's scene background colour. */
        fun floor(palette: OpalinePalette) =
            OpalineMaterial(
                color = if (palette == OpalinePalette.TIDAL) 0x102F50 else palette(palette)[0],
                roughness = .25f,
                metalness = .16f,
                clearcoat = 1f,
                clearcoatRoughness = .12f,
            )

        /** workbench.js `palette[theme]`: scene background and fog, then mist. */
        private fun palette(palette: OpalinePalette): IntArray =
            when (palette) {
                OpalinePalette.TIDAL -> intArrayOf(0x132A46, 0x7FADC9)
                OpalinePalette.OPAL -> intArrayOf(0x385452, 0xC5D7B4)
                OpalinePalette.MOSS -> intArrayOf(0x172C24, 0x718F68)
                OpalinePalette.OBSIDIAN -> intArrayOf(0x101725, 0x566989)
                OpalinePalette.AURORA -> intArrayOf(0x263142, 0xB5A6CD)
                OpalinePalette.AMBER -> intArrayOf(0x41382A, 0xC9A67B)
            }

        /** Object3D position, Euler XYZ rotation (radians) and scale as one matrix. */
        private fun compose(
            x: Float = 0f,
            y: Float = 0f,
            z: Float = 0f,
            rx: Float = 0f,
            ry: Float = 0f,
            rz: Float = 0f,
            sx: Float = 1f,
            sy: Float = 1f,
            sz: Float = 1f,
        ): FloatArray {
            val m = FloatArray(16)
            Matrix.setIdentityM(m, 0)
            Matrix.translateM(m, 0, x, y, z)
            Matrix.rotateM(m, 0, Math.toDegrees(rx.toDouble()).toFloat(), 1f, 0f, 0f)
            Matrix.rotateM(m, 0, Math.toDegrees(ry.toDouble()).toFloat(), 0f, 1f, 0f)
            Matrix.rotateM(m, 0, Math.toDegrees(rz.toDouble()).toFloat(), 0f, 0f, 1f)
            Matrix.scaleM(m, 0, sx, sy, sz)
            return m
        }

        /** PlaneGeometry; [flat] applies `rotateX(-Math.PI / 2)`. */
        private fun plane(
            width: Float,
            height: Float,
            segments: Int,
            flat: Boolean,
        ): Geometry {
            val columns = segments + 1
            val positions = FloatArray(columns * columns * 3)
            val normals = FloatArray(positions.size)
            for (iy in 0..segments) {
                for (ix in 0..segments) {
                    val i = (iy * columns + ix) * 3
                    val y = height / 2 - iy * height / segments
                    positions[i] = ix * width / segments - width / 2
                    positions[i + if (flat) 2 else 1] = if (flat) -y else y
                    normals[i + if (flat) 1 else 2] = 1f
                }
            }
            val indices = IntArray(segments * segments * 6)
            var n = 0
            for (iy in 0 until segments) {
                for (ix in 0 until segments) {
                    val a = ix + columns * iy
                    val b = ix + columns * (iy + 1)
                    for (index in intArrayOf(a, b, a + 1, b, b + 1, a + 1)) indices[n++] = index
                }
            }
            return Geometry(positions, normals, indices)
        }

        /** SphereGeometry(radius, widthSegments, heightSegments) over the full sphere. */
        private fun sphere(
            radius: Float,
            widthSegments: Int,
            heightSegments: Int,
        ): Geometry {
            val positions = FloatArray((widthSegments + 1) * (heightSegments + 1) * 3)
            val normals = FloatArray(positions.size)
            var n = 0
            for (iy in 0..heightSegments) {
                val theta = iy.toDouble() / heightSegments * PI
                val y = radius * cos(theta)
                val ring = sqrt(radius * radius - y * y)
                for (ix in 0..widthSegments) {
                    val phi = ix.toDouble() / widthSegments * 2 * PI
                    val x = -ring * cos(phi)
                    val z = ring * sin(phi)
                    val length = sqrt(x * x + y * y + z * z)
                    positions[n] = x.toFloat()
                    positions[n + 1] = y.toFloat()
                    positions[n + 2] = z.toFloat()
                    normals[n] = (x / length).toFloat()
                    normals[n + 1] = (y / length).toFloat()
                    normals[n + 2] = (z / length).toFloat()
                    n += 3
                }
            }
            val indices = mutableListOf<Int>()
            val row = widthSegments + 1
            for (iy in 0 until heightSegments) {
                for (ix in 0 until widthSegments) {
                    val a = iy * row + ix + 1
                    val b = iy * row + ix
                    val c = (iy + 1) * row + ix
                    val d = (iy + 1) * row + ix + 1
                    if (iy != 0) indices += listOf(a, b, d)
                    if (iy != heightSegments - 1) indices += listOf(b, c, d)
                }
            }
            return Geometry(positions, normals, indices.toIntArray())
        }

        /** IcosahedronGeometry(1, detail): PolyhedronGeometry's subdivision, smooth normals. */
        private fun icosahedron(detail: Int): Geometry {
            val t = ((1 + sqrt(5.0)) / 2).toFloat()

            // The corners in three.js order: (∓1, ±t, 0), then (0, ∓1, ±t), then (±t, 0, ∓1).
            fun corner(index: Int): FloatArray {
                val a = if (index % 2 == 0) -1f else 1f
                val b = if (index % 4 < 2) t else -t
                return when (index / 4) {
                    0 -> floatArrayOf(a, b, 0f)
                    1 -> floatArrayOf(0f, a, b)
                    else -> floatArrayOf(b, 0f, a)
                }
            }

            val faces = ICOSAHEDRON_FACES.split(' ').map { it.toInt() }
            val vertices = mutableListOf<Float>()
            for (face in faces.indices step 3) {
                subdivide(
                    corner(faces[face]),
                    corner(faces[face + 1]),
                    corner(faces[face + 2]),
                    detail + 1,
                    vertices,
                )
            }
            val positions = vertices.toFloatArray()
            for (i in positions.indices step 3) {
                val x = positions[i]
                val y = positions[i + 1]
                val z = positions[i + 2]
                val length = sqrt(x * x + y * y + z * z)
                for (axis in 0..2) positions[i + axis] /= length
            }
            return Geometry(positions, positions.copyOf(), IntArray(positions.size / 3) { it })
        }

        /** PolyhedronGeometry.subdivideFace: [cols] rows of triangles between a, b and c. */
        private fun subdivide(
            a: FloatArray,
            b: FloatArray,
            c: FloatArray,
            cols: Int,
            out: MutableList<Float>,
        ) {
            val v =
                List(cols + 1) { i ->
                    val aj = lerp(a, c, i.toFloat() / cols)
                    val bj = lerp(b, c, i.toFloat() / cols)
                    val rows = cols - i
                    List(rows + 1) { j ->
                        if (j == 0 && i == cols) aj else lerp(aj, bj, j.toFloat() / rows)
                    }
                }
            for (i in 0 until cols) {
                for (j in 0 until 2 * (cols - i) - 1) {
                    val k = j / 2
                    val triangle =
                        if (j % 2 == 0) {
                            listOf(v[i][k + 1], v[i + 1][k], v[i][k])
                        } else {
                            listOf(v[i][k + 1], v[i + 1][k + 1], v[i + 1][k])
                        }
                    out += triangle.flatMap { it.toList() }
                }
            }
        }

        private fun lerp(
            a: FloatArray,
            b: FloatArray,
            f: Float,
        ) = FloatArray(3) { a[it] + (b[it] - a[it]) * f }

        fun texture(): Int {
            val ids = IntArray(1)
            GL.glGenTextures(1, ids, 0)
            GL.glBindTexture(GL.GL_TEXTURE_2D, ids[0])
            GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MIN_FILTER, GL.GL_LINEAR)
            GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MAG_FILTER, GL.GL_LINEAR)
            GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_S, GL.GL_CLAMP_TO_EDGE)
            GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_T, GL.GL_CLAMP_TO_EDGE)
            return ids[0]
        }

        /** A depth texture for hardware PCF: linear filtering with LEQUAL comparison. */
        fun depthTexture(size: Int): Int {
            val id = texture()
            GL.glTexStorage2D(GL.GL_TEXTURE_2D, 1, GL.GL_DEPTH_COMPONENT24, size, size)
            GL.glTexParameteri(
                GL.GL_TEXTURE_2D,
                GL.GL_TEXTURE_COMPARE_MODE,
                GL.GL_COMPARE_REF_TO_TEXTURE,
            )
            GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_COMPARE_FUNC, GL.GL_LEQUAL)
            return id
        }

        /** A framebuffer over a colour texture or a depth-only texture (no colour draw buffer). */
        fun framebuffer(
            color: Int = 0,
            depth: Int = 0,
        ): Int {
            val ids = IntArray(1)
            GL.glGenFramebuffers(1, ids, 0)
            GL.glBindFramebuffer(GL.GL_FRAMEBUFFER, ids[0])
            val attachment = if (color != 0) GL.GL_COLOR_ATTACHMENT0 else GL.GL_DEPTH_ATTACHMENT
            GL.glFramebufferTexture2D(
                GL.GL_FRAMEBUFFER,
                attachment,
                GL.GL_TEXTURE_2D,
                if (color != 0) color else depth,
                0,
            )
            if (color == 0) GL.glDrawBuffers(1, intArrayOf(GL.GL_NONE), 0)
            check(GL.glCheckFramebufferStatus(GL.GL_FRAMEBUFFER) == GL.GL_FRAMEBUFFER_COMPLETE)
            GL.glBindFramebuffer(GL.GL_FRAMEBUFFER, 0)
            return ids[0]
        }
    }
}
