package dev.geode.ui.opaline.optics

import android.content.res.AssetManager
import dev.geode.ui.opaline.OpalineLightRig
import dev.geode.ui.opaline.OpalineProgram
import kotlin.math.exp
import kotlin.math.roundToInt
import android.opengl.GLES30 as GL

/**
 * The workbench post chain (src/workbench.js `init`): `RenderPass`, then
 * `UnrealBloomPass(resolution, .3, .7, 1.05)`, then `OutputPass`, under `ACESFilmicToneMapping`,
 * `toneMappingExposure = .78` and `SRGBColorSpace` (I18). Ported from three.js r186
 * UnrealBloomPass.js and OutputPass.js. The caller's linear HDR scene texture is the RenderPass
 * read buffer; UnrealBloomPass adds its composite onto that buffer (ONE, ONE) and OutputPass reads
 * it at the same UV, so the output pass samples both and adds them, leaving the scene untouched.
 * Bloom targets are RGBA16F (`HalfFloatType`), which needs EXT_color_buffer_half_float or
 * EXT_color_buffer_float like the caller's HDR target. EGL thread only.
 */
internal class OpalinePostChain(
    assets: AssetManager,
) {
    private val highPass = OpalineProgram(assets, "optics/bloom-highpass")
    private val blur = OpalineProgram(assets, "optics/bloom-blur")
    private val composite = OpalineProgram(assets, "optics/bloom-composite")
    private val output = OpalineProgram(assets, "optics/output")
    private val kernels = KERNEL_SIZES.map { SeparableKernel(it) }
    private val sampler = linearClampSampler()
    private val binding = IntArray(1)
    private val viewport = IntArray(4)
    private var targets: Targets? = null
    private var width = 0
    private var height = 0

    /**
     * Runs the chain on [sceneColor], a linear HDR texture of [width]×[height] pixels, and writes
     * the tone-mapped sRGB image into the framebuffer and viewport bound on entry. Returns with
     * depth test, blending and scissor disabled and texture unit 0 active.
     */
    fun render(
        sceneColor: Int,
        width: Int,
        height: Int,
    ) {
        resize(width, height)
        val current = checkNotNull(targets)
        GL.glGetIntegerv(GL.GL_FRAMEBUFFER_BINDING, binding, 0)
        GL.glGetIntegerv(GL.GL_VIEWPORT, viewport, 0)
        GL.glDisable(GL.GL_DEPTH_TEST)
        GL.glDisable(GL.GL_BLEND)
        GL.glDisable(GL.GL_SCISSOR_TEST)
        GL.glBindVertexArray(0)
        for (unit in 0 until MIPS) GL.glBindSampler(unit, sampler)
        // 1. Extract bright areas.
        highPass.use()
        target(current.bright)
        bindTexture(0, GL.GL_TEXTURE_2D, sceneColor)
        highPass.integer("tDiffuse", 0)
        highPass.scalar("luminosityThreshold", THRESHOLD)
        highPass.scalar("smoothWidth", SMOOTH_WIDTH)
        GL.glDrawArrays(GL.GL_TRIANGLES, 0, 3)
        // 2. Blur all the mips progressively (BlurDirectionX, then BlurDirectionY).
        blur.use()
        blur.integer("colorTexture", 0)
        var input = current.bright
        for (mip in 0 until MIPS) {
            val kernel = kernels[mip]
            val horizontal = current.horizontal[mip]
            blur.integer("kernelPairs", kernel.offsets.size)
            blur.scalar("centerWeight", kernel.centerWeight)
            blur.scalars("gaussianOffsets", kernel.offsets)
            blur.scalars("gaussianWeights", kernel.weights)
            blur.vec2("invSize", 1f / horizontal.width, 1f / horizontal.height)
            blurInto(input, horizontal, 1f, 0f)
            blurInto(horizontal, current.vertical[mip], 0f, 1f)
            input = current.vertical[mip]
        }
        // 3. Composite all the mips.
        composite.use()
        target(current.horizontal[0])
        for (mip in 0 until MIPS) {
            bindTexture(mip, GL.GL_TEXTURE_2D, current.vertical[mip].texture)
            composite.integer(BLUR_TEXTURES[mip], mip)
        }
        composite.scalar("bloomStrength", STRENGTH)
        composite.scalar("bloomRadius", RADIUS)
        composite.scalars("bloomFactors", BLOOM_FACTORS)
        composite.vec3s("bloomTintColors", BLOOM_TINT_COLORS)
        GL.glDrawArrays(GL.GL_TRIANGLES, 0, 3)
        // OutputPass, with the bloom's additive blend over the read buffer.
        GL.glBindFramebuffer(GL.GL_FRAMEBUFFER, binding[0])
        GL.glViewport(viewport[0], viewport[1], viewport[2], viewport[3])
        output.use()
        bindTexture(0, GL.GL_TEXTURE_2D, sceneColor)
        output.integer("tDiffuse", 0)
        bindTexture(1, GL.GL_TEXTURE_2D, current.horizontal[0].texture)
        output.integer("bloomTexture", 1)
        output.scalar("toneMappingExposure", OpalineLightRig.TONE_MAPPING_EXPOSURE)
        GL.glDrawArrays(GL.GL_TRIANGLES, 0, 3)
        for (unit in 0 until MIPS) GL.glBindSampler(unit, 0)
        GL.glActiveTexture(GL.GL_TEXTURE0)
    }

    /** UnrealBloomPass.setSize: the bright target and mip i at successive `Math.round` halves. */
    fun resize(
        width: Int,
        height: Int,
    ) {
        if (width == this.width && height == this.height && targets != null) return
        GL.glGetIntegerv(GL.GL_FRAMEBUFFER_BINDING, binding, 0)
        targets?.release()
        targets = Targets(width.coerceAtLeast(1), height.coerceAtLeast(1))
        this.width = width
        this.height = height
        GL.glBindFramebuffer(GL.GL_FRAMEBUFFER, binding[0])
    }

    fun release() {
        targets?.release()
        targets = null
        GL.glDeleteSamplers(1, intArrayOf(sampler), 0)
        for (program in listOf(highPass, blur, composite, output)) program.dispose()
    }

    private fun blurInto(
        source: Target,
        destination: Target,
        directionX: Float,
        directionY: Float,
    ) {
        target(destination)
        bindTexture(0, GL.GL_TEXTURE_2D, source.texture)
        blur.vec2("direction", directionX, directionY)
        GL.glDrawArrays(GL.GL_TRIANGLES, 0, 3)
    }

    /** `renderer.setRenderTarget(target)`, then `clear()` to the pass's clear colour, zero. */
    private fun target(target: Target) {
        GL.glBindFramebuffer(GL.GL_FRAMEBUFFER, target.framebuffer)
        GL.glViewport(0, 0, target.width, target.height)
        GL.glClearBufferfv(GL.GL_COLOR, 0, CLEAR, 0)
    }

    /** `_getSeparableBlurMaterial(kernelRadius)`: sigma = radius / 3, taps paired bilinearly. */
    private class SeparableKernel(
        kernelRadius: Int,
    ) {
        val centerWeight: Float
        val offsets = FloatArray(kernelRadius / 2)
        val weights = FloatArray(kernelRadius / 2)

        init {
            val sigma = kernelRadius / 3.0
            val coefficients =
                DoubleArray(kernelRadius) { i ->
                    0.39894 * exp(-0.5 * i * i / (sigma * sigma)) / sigma
                }
            centerWeight = coefficients[0].toFloat()
            for (pair in offsets.indices) {
                val i = pair * 2 + 1
                val wa = coefficients[i]
                val wb = if (i + 1 < kernelRadius) coefficients[i + 1] else 0.0
                offsets[pair] = ((i * wa + (i + 1) * wb) / (wa + wb)).toFloat()
                weights[pair] = (wa + wb).toFloat()
            }
        }
    }

    /** One `WebGLRenderTarget(w, h, {type: HalfFloatType, depthBuffer: false})`. */
    private class Target(
        val width: Int,
        val height: Int,
    ) {
        val texture = glName { GL.glGenTextures(1, it, 0) }
        val framebuffer = glName { GL.glGenFramebuffers(1, it, 0) }

        init {
            GL.glBindTexture(GL.GL_TEXTURE_2D, texture)
            GL.glTexStorage2D(GL.GL_TEXTURE_2D, 1, GL.GL_RGBA16F, width, height)
            GL.glBindFramebuffer(GL.GL_FRAMEBUFFER, framebuffer)
            GL.glFramebufferTexture2D(
                GL.GL_FRAMEBUFFER,
                GL.GL_COLOR_ATTACHMENT0,
                GL.GL_TEXTURE_2D,
                texture,
                0,
            )
            check(GL.glCheckFramebufferStatus(GL.GL_FRAMEBUFFER) == GL.GL_FRAMEBUFFER_COMPLETE) {
                "RGBA16F bloom target is not renderable"
            }
        }

        fun release() {
            GL.glDeleteFramebuffers(1, intArrayOf(framebuffer), 0)
            GL.glDeleteTextures(1, intArrayOf(texture), 0)
        }
    }

    /** `renderTargetBright`, `renderTargetsHorizontal` and `renderTargetsVertical`. */
    private class Targets(
        width: Int,
        height: Int,
    ) {
        val bright = Target(half(width), half(height))
        val horizontal = ArrayList<Target>(MIPS)
        val vertical = ArrayList<Target>(MIPS)

        init {
            var x = bright.width
            var y = bright.height
            repeat(MIPS) {
                horizontal.add(Target(x, y))
                vertical.add(Target(x, y))
                x = half(x)
                y = half(y)
            }
        }

        fun release() {
            bright.release()
            for (target in horizontal + vertical) target.release()
        }
    }

    private companion object {
        /** src/workbench.js `new UnrealBloomPass(new THREE.Vector2(1, 1), .3, .7, 1.05)`. */
        const val STRENGTH = .3f
        const val RADIUS = .7f
        const val THRESHOLD = 1.05f

        /** UnrealBloomPass `nMips`, `smoothWidth`, `kernelSizeArray`, `bloomFactors`, tints. */
        const val MIPS = 5
        const val SMOOTH_WIDTH = .01f
        val KERNEL_SIZES = intArrayOf(6, 10, 14, 18, 22)
        val BLOOM_FACTORS = floatArrayOf(1f, .8f, .6f, .4f, .2f)
        val BLOOM_TINT_COLORS = FloatArray(MIPS * 3) { 1f }
        val BLUR_TEXTURES = List(MIPS) { "blurTexture${it + 1}" }
        val CLEAR = FloatArray(4)

        /** `Math.round(size / 2)`. */
        fun half(size: Int): Int = (size / 2f).roundToInt()
    }
}
