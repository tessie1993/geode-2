package dev.geode.ui.opaline.optics

import java.nio.FloatBuffer
import android.opengl.GLES30 as GL

/**
 * src/materials.js `enableFilmThickness`: the per-film vertex attribute `filmThickness` in metres
 * (BubbleFilm `thickness`), which the surface shader turns into nanometres for the thin-film
 * interference. [update] uploads the live values each step (the workbench flags
 * `filmThickness.needsUpdate` every frame); [bind] attaches them at [LOCATION] of the bound VAO.
 */
internal class OpalineFilmThickness {
    private val buffer = glName { GL.glGenBuffers(1, it, 0) }
    private var staging: FloatBuffer? = null
    private var capacity = 0

    fun update(thickness: FloatArray) {
        val data = staged(staging, thickness)
        staging = data
        GL.glBindBuffer(GL.GL_ARRAY_BUFFER, buffer)
        if (thickness.size == capacity) {
            GL.glBufferSubData(GL.GL_ARRAY_BUFFER, 0, thickness.size * 4, data)
        } else {
            GL.glBufferData(GL.GL_ARRAY_BUFFER, thickness.size * 4, data, GL.GL_DYNAMIC_DRAW)
            capacity = thickness.size
        }
    }

    fun bind() {
        GL.glBindBuffer(GL.GL_ARRAY_BUFFER, buffer)
        GL.glEnableVertexAttribArray(LOCATION)
        GL.glVertexAttribPointer(LOCATION, 1, GL.GL_FLOAT, false, 0, 0)
    }

    fun release() = GL.glDeleteBuffers(1, intArrayOf(buffer), 0)

    companion object {
        /** `layout(location = 4) in float aFilmThickness` in surface.vert. */
        const val LOCATION = 4
    }
}
