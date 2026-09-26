package dev.geode.ui.opaline

import android.content.res.AssetManager
import android.opengl.GLES30 as GL

/**
 * One linked GLES 3 program from opaline-native/shaders/[name].vert and .frag. GLSL ES has no
 * include directive, so `#include "lib/..."` lines are replaced by those shared library files
 * before compilation. Uniform locations are cached; a name the linker removed resolves to -1,
 * which GLES ignores.
 */
internal class OpalineProgram(
    private val assets: AssetManager,
    name: String,
) {
    private val id = GL.glCreateProgram()
    private val locations = mutableMapOf<String, Int>()

    init {
        val stages = listOf(GL.GL_VERTEX_SHADER to "vert", GL.GL_FRAGMENT_SHADER to "frag")
        val shaders =
            stages.map { (type, suffix) ->
                val shader = GL.glCreateShader(type)
                GL.glShaderSource(shader, source("$name.$suffix"))
                GL.glCompileShader(shader)
                val status = IntArray(1)
                GL.glGetShaderiv(shader, GL.GL_COMPILE_STATUS, status, 0)
                check(status[0] != 0) { "$name.$suffix: ${GL.glGetShaderInfoLog(shader)}" }
                GL.glAttachShader(id, shader)
                shader
            }
        GL.glLinkProgram(id)
        shaders.forEach { GL.glDeleteShader(it) }
        val status = IntArray(1)
        GL.glGetProgramiv(id, GL.GL_LINK_STATUS, status, 0)
        check(status[0] != 0) { "$name: ${GL.glGetProgramInfoLog(id)}" }
    }

    private fun source(file: String): String =
        read(file).lineSequence().joinToString("\n") { line ->
            INCLUDE.matchEntire(line.trim())?.let { read(it.groupValues[1]) } ?: line
        }

    private fun read(file: String): String = assets.open("opaline-native/shaders/$file").bufferedReader().use { it.readText() }

    fun location(name: String): Int = locations.getOrPut(name) { GL.glGetUniformLocation(id, name) }

    fun use() = GL.glUseProgram(id)

    fun scalar(
        name: String,
        value: Float,
    ) = GL.glUniform1f(location(name), value)

    fun scalars(
        name: String,
        values: FloatArray,
    ) = GL.glUniform1fv(location(name), values.size, values, 0)

    fun integer(
        name: String,
        value: Int,
    ) = GL.glUniform1i(location(name), value)

    fun flag(
        name: String,
        value: Boolean,
    ) = GL.glUniform1i(location(name), if (value) 1 else 0)

    fun vec2(
        name: String,
        x: Float,
        y: Float,
    ) = GL.glUniform2f(location(name), x, y)

    fun vec3(
        name: String,
        v: FloatArray,
    ) = GL.glUniform3f(location(name), v[0], v[1], v[2])

    /** A `vec3 name[n]` array from 3n packed floats. */
    fun vec3s(
        name: String,
        values: FloatArray,
    ) = GL.glUniform3fv(location(name), values.size / 3, values, 0)

    /** A `vec4 name[n]` array from 4n packed floats. */
    fun vec4s(
        name: String,
        values: FloatArray,
    ) = GL.glUniform4fv(location(name), values.size / 4, values, 0)

    fun vec4(
        name: String,
        x: Float,
        y: Float,
        z: Float,
        w: Float,
    ) = GL.glUniform4f(location(name), x, y, z, w)

    fun matrix(
        name: String,
        values: FloatArray,
    ) = GL.glUniformMatrix4fv(location(name), 1, false, values, 0)

    /** An sRGB hex colour as linear RGB, the space every lighting uniform is in. */
    fun color(
        name: String,
        color: Int,
    ) = vec3(name, linearRgb(color))

    fun dispose() = GL.glDeleteProgram(id)

    private companion object {
        val INCLUDE = Regex("""#include\s+"([^"]+)"""")
    }
}
