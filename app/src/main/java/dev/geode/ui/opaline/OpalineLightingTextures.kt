package dev.geode.ui.opaline

import android.annotation.SuppressLint
import android.util.Half
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.ShortBuffer
import android.opengl.GLES30 as GL

/** GPU copies of the lighting inputs the physical surface pass samples. EGL thread only. */
internal object OpalineLightingTextures {
    /** The three.js DFG table as an RG16F texture with linear filtering and clamped edges. */
    fun dfg(): Int {
        val id = generate(GL.GL_TEXTURE_2D)
        GL.glTexImage2D(
            GL.GL_TEXTURE_2D,
            0,
            GL.GL_RG16F,
            OpalineDfgLut.SIZE,
            OpalineDfgLut.SIZE,
            0,
            GL.GL_RG,
            GL.GL_HALF_FLOAT,
            shorts(OpalineDfgLut.halfFloats()),
        )
        GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MIN_FILTER, GL.GL_LINEAR)
        GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MAG_FILTER, GL.GL_LINEAR)
        GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_S, GL.GL_CLAMP_TO_EDGE)
        GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_T, GL.GL_CLAMP_TO_EDGE)
        return id
    }

    /** The prefiltered RoomEnvironment as an RGBA16F cube map, one mip per roughness step. */
    fun environment(): Int {
        val id = generate(GL.GL_TEXTURE_CUBE_MAP)
        OpalineRoomEnvironment.levels.forEachIndexed { level, faces ->
            val size = OpalineRoomEnvironment.BASE_SIZE shr level
            faces.forEachIndexed { face, rgb ->
                GL.glTexImage2D(
                    GL.GL_TEXTURE_CUBE_MAP_POSITIVE_X + face,
                    level,
                    GL.GL_RGBA16F,
                    size,
                    size,
                    0,
                    GL.GL_RGBA,
                    GL.GL_HALF_FLOAT,
                    shorts(rgba(rgb)),
                )
            }
        }
        GL.glTexParameteri(GL.GL_TEXTURE_CUBE_MAP, GL.GL_TEXTURE_MIN_FILTER, GL.GL_LINEAR_MIPMAP_LINEAR)
        GL.glTexParameteri(GL.GL_TEXTURE_CUBE_MAP, GL.GL_TEXTURE_MAG_FILTER, GL.GL_LINEAR)
        GL.glTexParameteri(GL.GL_TEXTURE_CUBE_MAP, GL.GL_TEXTURE_BASE_LEVEL, 0)
        GL.glTexParameteri(GL.GL_TEXTURE_CUBE_MAP, GL.GL_TEXTURE_MAX_LEVEL, OpalineRoomEnvironment.LEVELS - 1)
        GL.glTexParameteri(GL.GL_TEXTURE_CUBE_MAP, GL.GL_TEXTURE_WRAP_S, GL.GL_CLAMP_TO_EDGE)
        GL.glTexParameteri(GL.GL_TEXTURE_CUBE_MAP, GL.GL_TEXTURE_WRAP_T, GL.GL_CLAMP_TO_EDGE)
        GL.glTexParameteri(GL.GL_TEXTURE_CUBE_MAP, GL.GL_TEXTURE_WRAP_R, GL.GL_CLAMP_TO_EDGE)
        return id
    }

    // Lint's HalfFloat check misreads the ShortArray initializer lambda as widening a half;
    // every value is produced by android.util.Half.toHalf as the check asks.
    @SuppressLint("HalfFloat")
    private fun rgba(rgb: FloatArray): ShortArray =
        ShortArray(rgb.size / 3 * 4) { index ->
            val channel = index % 4
            Half.toHalf(if (channel == 3) 1f else rgb[index / 4 * 3 + channel])
        }

    private fun shorts(values: ShortArray): ShortBuffer =
        ByteBuffer
            .allocateDirect(values.size * 2)
            .order(ByteOrder.nativeOrder())
            .asShortBuffer()
            .put(values)
            .also { it.position(0) }

    private fun generate(target: Int): Int {
        val ids = IntArray(1)
        GL.glGenTextures(1, ids, 0)
        GL.glBindTexture(target, ids[0])
        return ids[0]
    }
}
