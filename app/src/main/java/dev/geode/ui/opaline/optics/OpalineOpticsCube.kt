package dev.geode.ui.opaline.optics

import java.nio.ByteBuffer
import java.nio.ByteOrder
import android.opengl.GLES30 as GL

/**
 * The proxy box of both media: the unit cube [-1, 1]³ with outward counter-clockwise faces,
 * drawn as three.js draws a `transparent`, `depthWrite: false`, `side: BackSide` ShaderMaterial
 * with `NormalBlending` (straight alpha).
 */
internal class OpalineOpticsCube {
    private val buffers = IntArray(2).also { GL.glGenBuffers(2, it, 0) }
    private val vao = glName { GL.glGenVertexArrays(1, it, 0) }

    init {
        GL.glBindVertexArray(vao)
        GL.glBindBuffer(GL.GL_ARRAY_BUFFER, buffers[0])
        GL.glBufferData(
            GL.GL_ARRAY_BUFFER,
            CORNERS.size * 4,
            staged(null, CORNERS),
            GL.GL_STATIC_DRAW,
        )
        GL.glEnableVertexAttribArray(0)
        GL.glVertexAttribPointer(0, 3, GL.GL_FLOAT, false, 0, 0)
        val faces = ByteBuffer.allocateDirect(FACES.size).order(ByteOrder.nativeOrder())
        faces.put(FACES)
        faces.position(0)
        GL.glBindBuffer(GL.GL_ELEMENT_ARRAY_BUFFER, buffers[1])
        GL.glBufferData(GL.GL_ELEMENT_ARRAY_BUFFER, FACES.size, faces, GL.GL_STATIC_DRAW)
        GL.glBindVertexArray(0)
    }

    /**
     * Blends the back faces over the target (SRC_ALPHA, ONE_MINUS_SRC_ALPHA; alpha ONE,
     * ONE_MINUS_SRC_ALPHA) without depth writes, then restores blending and culling as found.
     */
    fun drawMedium(mirrored: Boolean) {
        val blend = GL.glIsEnabled(GL.GL_BLEND)
        val cull = GL.glIsEnabled(GL.GL_CULL_FACE)
        GL.glEnable(GL.GL_BLEND)
        GL.glBlendEquation(GL.GL_FUNC_ADD)
        GL.glBlendFuncSeparate(
            GL.GL_SRC_ALPHA,
            GL.GL_ONE_MINUS_SRC_ALPHA,
            GL.GL_ONE,
            GL.GL_ONE_MINUS_SRC_ALPHA,
        )
        GL.glEnable(GL.GL_CULL_FACE)
        GL.glCullFace(GL.GL_FRONT)
        GL.glFrontFace(if (mirrored) GL.GL_CW else GL.GL_CCW)
        GL.glDepthMask(false)
        GL.glBindVertexArray(vao)
        GL.glDrawElements(GL.GL_TRIANGLES, FACES.size, GL.GL_UNSIGNED_BYTE, 0)
        GL.glBindVertexArray(0)
        GL.glDepthMask(true)
        GL.glFrontFace(GL.GL_CCW)
        GL.glCullFace(GL.GL_BACK)
        if (!cull) GL.glDisable(GL.GL_CULL_FACE)
        if (!blend) GL.glDisable(GL.GL_BLEND)
    }

    fun release() {
        GL.glDeleteBuffers(2, buffers, 0)
        GL.glDeleteVertexArrays(1, intArrayOf(vao), 0)
    }

    private companion object {
        /** Corner i sits at ±1 on x, y and z from bits 0, 1 and 2 of i. */
        val CORNERS = FloatArray(24) { if (((it / 3) shr (it % 3) and 1) == 1) 1f else -1f }

        /** Two outward counter-clockwise triangles per face: +X, −X, +Y, −Y, +Z, −Z. */
        val FACES =
            byteArrayOf(1, 3, 7, 1, 7, 5, 0, 4, 6, 0, 6, 2) +
                byteArrayOf(2, 6, 7, 2, 7, 3, 0, 1, 5, 0, 5, 4) +
                byteArrayOf(4, 5, 7, 4, 7, 6, 0, 2, 3, 0, 3, 1)
    }
}
