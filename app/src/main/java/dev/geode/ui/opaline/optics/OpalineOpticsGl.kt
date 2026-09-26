package dev.geode.ui.opaline.optics

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import android.opengl.GLES30 as GL

private val WRAPS = intArrayOf(GL.GL_TEXTURE_WRAP_S, GL.GL_TEXTURE_WRAP_T, GL.GL_TEXTURE_WRAP_R)

/** One GL object name from a `glGen*` call that fills a one-element array. */
internal inline fun glName(generate: (IntArray) -> Unit): Int = IntArray(1).also(generate)[0]

internal fun bindTexture(
    unit: Int,
    target: Int,
    texture: Int,
) {
    GL.glActiveTexture(GL.GL_TEXTURE0 + unit)
    GL.glBindTexture(target, texture)
}

/** three.js render-target sampling (`LinearFilter`, clamp to edge) whatever the texture's own. */
internal fun linearClampSampler(): Int {
    val sampler = glName { GL.glGenSamplers(1, it, 0) }
    GL.glSamplerParameteri(sampler, GL.GL_TEXTURE_MIN_FILTER, GL.GL_LINEAR)
    GL.glSamplerParameteri(sampler, GL.GL_TEXTURE_MAG_FILTER, GL.GL_LINEAR)
    for (wrap in WRAPS) GL.glSamplerParameteri(sampler, wrap, GL.GL_CLAMP_TO_EDGE)
    return sampler
}

/** `LinearFilter` and `ClampToEdgeWrapping` on the texture bound to [target]. */
internal fun linearClamp(target: Int) {
    GL.glTexParameteri(target, GL.GL_TEXTURE_MIN_FILTER, GL.GL_LINEAR)
    GL.glTexParameteri(target, GL.GL_TEXTURE_MAG_FILTER, GL.GL_LINEAR)
    for (wrap in WRAPS) GL.glTexParameteri(target, wrap, GL.GL_CLAMP_TO_EDGE)
}

/**
 * Storage for the library's `FloatType` + `LinearFilter` data textures: RGBA32F where the device
 * filters float textures (OES_texture_float_linear), else RGBA16F, which GLES 3 always filters.
 */
internal fun floatTextureFormat(): Int =
    if (GL.glGetString(GL.GL_EXTENSIONS)?.contains("OES_texture_float_linear") == true) {
        GL.GL_RGBA32F
    } else {
        GL.GL_RGBA16F
    }

/** [values] in a direct native-order buffer, reusing [buffer] when it is large enough. */
internal fun staged(
    buffer: FloatBuffer?,
    values: FloatArray,
): FloatBuffer {
    val target =
        if (buffer != null && buffer.capacity() >= values.size) {
            buffer
        } else {
            ByteBuffer
                .allocateDirect(values.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
        }
    target.clear()
    target.put(values)
    target.position(0)
    return target
}

/** A negative 3×3 determinant: three.js then winds front faces clockwise. */
internal fun mirrored(m: FloatArray): Boolean {
    val determinant =
        m[0] * (m[5] * m[10] - m[9] * m[6]) -
            m[4] * (m[1] * m[10] - m[9] * m[2]) +
            m[8] * (m[1] * m[6] - m[5] * m[2])
    return determinant < 0f
}
