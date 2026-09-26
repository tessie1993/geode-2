package dev.geode.ui.opaline

import dev.geode.ui.opaline.optics.OpalineFilmThickness
import java.nio.ByteBuffer
import java.nio.ByteOrder
import android.opengl.GLES30 as GL

/**
 * One part's own copy of changing vertices on the GPU: a transition's deformed clone, a gel
 * lattice's displaced piece, a simulated film or a generated tube. Positions and normals are
 * re-uploaded on [upload]; [indices] are uploaded once. EGL thread only.
 */
internal class OpalineDynamicMesh(
    indices: IntArray,
) {
    private val buffers = IntArray(3)
    private val vao = IntArray(1)
    private val count = indices.size
    private var capacity = 0

    init {
        GL.glGenVertexArrays(1, vao, 0)
        GL.glGenBuffers(3, buffers, 0)
        GL.glBindVertexArray(vao[0])
        for (slot in 0..1) {
            GL.glBindBuffer(GL.GL_ARRAY_BUFFER, buffers[slot])
            GL.glEnableVertexAttribArray(slot)
            GL.glVertexAttribPointer(slot, 3, GL.GL_FLOAT, false, 0, 0)
        }
        GL.glBindBuffer(GL.GL_ELEMENT_ARRAY_BUFFER, buffers[2])
        val data = ByteBuffer.allocateDirect(count * 4).order(ByteOrder.nativeOrder())
        GL.glBufferData(
            GL.GL_ELEMENT_ARRAY_BUFFER,
            count * 4,
            data.asIntBuffer().put(indices).position(0),
            GL.GL_STATIC_DRAW,
        )
        GL.glBindVertexArray(0)
    }

    /** The version of the source last uploaded, for callers that re-upload on change. */
    var version = -1

    fun upload(
        positions: FloatArray,
        normals: FloatArray,
    ) {
        for ((slot, values) in listOf(positions, normals).withIndex()) {
            val data = ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder())
            GL.glBindBuffer(GL.GL_ARRAY_BUFFER, buffers[slot])
            val buffer = data.asFloatBuffer().put(values).position(0)
            if (values.size == capacity) {
                GL.glBufferSubData(GL.GL_ARRAY_BUFFER, 0, values.size * 4, buffer)
            } else {
                GL.glBufferData(GL.GL_ARRAY_BUFFER, values.size * 4, buffer, GL.GL_DYNAMIC_DRAW)
            }
        }
        capacity = positions.size
    }

    /** Draws the triangles; [film] adds the live film thickness at its attribute location. */
    fun draw(film: OpalineFilmThickness? = null) {
        GL.glBindVertexArray(vao[0])
        if (film != null) film.bind()
        GL.glDrawElements(GL.GL_TRIANGLES, count, GL.GL_UNSIGNED_INT, 0)
        GL.glBindVertexArray(0)
    }

    fun dispose() {
        GL.glDeleteBuffers(3, buffers, 0)
        GL.glDeleteVertexArrays(1, vao, 0)
    }
}
