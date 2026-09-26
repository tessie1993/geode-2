package dev.geode.ui.opaline.optics

import java.nio.FloatBuffer
import android.opengl.GLES30 as GL

/**
 * The GPU copies of [caustics]: [receiver] (RefractiveCaustics `texture`, sampled with the
 * receiver's UVs) and, with a photon volume, its six directional grids [volume]
 * (`volumeTextures`). [upload] copies what changed, as three.js uploads a `needsUpdate` texture.
 */
internal class OpalineCausticTextures(
    val caustics: OpalineRefractiveCaustics,
) {
    val receiver = glName { GL.glGenTextures(1, it, 0) }
    val volume = IntArray(if (caustics.volume == null) 0 else 6)
    private var receiverVersion = -1
    private var volumeVersion = -1
    private var staging: FloatBuffer? = null

    init {
        val format = floatTextureFormat()
        GL.glBindTexture(GL.GL_TEXTURE_2D, receiver)
        GL.glTexStorage2D(GL.GL_TEXTURE_2D, 1, format, caustics.resolution, caustics.resolution)
        linearClamp(GL.GL_TEXTURE_2D)
        val photons = caustics.volume
        if (photons != null) {
            GL.glGenTextures(volume.size, volume, 0)
            val (x, y, z) = photons.resolution
            for (texture in volume) {
                GL.glBindTexture(GL.GL_TEXTURE_3D, texture)
                GL.glTexStorage3D(GL.GL_TEXTURE_3D, 1, format, x, y, z)
                linearClamp(GL.GL_TEXTURE_3D)
            }
        }
    }

    fun upload() {
        if (caustics.version != receiverVersion) {
            val data = staged(staging, caustics.pixels)
            staging = data
            val size = caustics.resolution
            GL.glBindTexture(GL.GL_TEXTURE_2D, receiver)
            GL.glTexSubImage2D(GL.GL_TEXTURE_2D, 0, 0, 0, size, size, GL.GL_RGBA, GL.GL_FLOAT, data)
            receiverVersion = caustics.version
        }
        val photons = caustics.volume ?: return
        if (photons.version == volumeVersion) return
        val (x, y, z) = photons.resolution
        for (bin in volume.indices) {
            val data = staged(staging, photons.textures[bin])
            staging = data
            GL.glBindTexture(GL.GL_TEXTURE_3D, volume[bin])
            GL.glTexSubImage3D(
                GL.GL_TEXTURE_3D,
                0,
                0,
                0,
                0,
                x,
                y,
                z,
                GL.GL_RGBA,
                GL.GL_FLOAT,
                data,
            )
        }
        volumeVersion = photons.version
    }

    fun release() {
        GL.glDeleteTextures(1, intArrayOf(receiver), 0)
        if (volume.isNotEmpty()) GL.glDeleteTextures(volume.size, volume, 0)
    }
}
