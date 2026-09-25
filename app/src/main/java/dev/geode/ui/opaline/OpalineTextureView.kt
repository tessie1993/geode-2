package dev.geode.ui.opaline

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Choreographer
import android.view.TextureView
import android.view.View
import java.util.concurrent.atomic.AtomicReference

/** TextureView participates in Compose clipping and window composition; it never owns input. */
internal class OpalineTextureView(
    context: Context,
    private val onReady: (Boolean) -> Unit,
) : TextureView(context),
    TextureView.SurfaceTextureListener {
    private val thread = HandlerThread("Opaline-native").apply { start() }
    private val handler = Handler(thread.looper)
    private val frame = AtomicReference(OpalineFrame())
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var surface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var renderer: OpalineRenderer? = null
    private var targetWidth = 1
    private var targetHeight = 1
    private var clock: Choreographer? = null
    private var lastTime = 0L
    private var pending = false
    private var ready = false

    @Volatile private var released = false

    init {
        isOpaque = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        isFocusable = false
        surfaceTextureListener = this
    }

    fun update(next: OpalineFrame) {
        if (released) return
        frame.set(next)
        handler.post {
            if (!next.active) {
                renderer?.cancel()
                clock?.removeFrameCallback(draw)
                pending = false
                lastTime = 0L
            } else {
                schedule()
            }
        }
    }

    fun touch(
        id: Long,
        pointer: Long,
        pressed: Boolean,
        x: Float,
        y: Float,
    ) {
        if (released || !frame.get().active) return
        handler.post {
            renderer?.touch(id, pointer, pressed, x, y)
            schedule()
        }
    }

    fun cancel(id: Long) {
        if (!released) {
            handler.post {
                renderer?.cancel(id)
                schedule()
            }
        }
    }

    private fun schedule() {
        if (released || pending || renderer == null || !frame.get().active) return
        if (clock == null) clock = Choreographer.getInstance()
        pending = true
        clock?.postFrameCallback(draw)
    }

    private val draw =
        Choreographer.FrameCallback { now ->
            pending = false
            val snapshot = frame.get()
            if (!released && snapshot.active && renderer != null) {
                try {
                    val dt = if (lastTime == 0L) 1f / 60f else ((now - lastTime) / 1_000_000_000f).coerceIn(0f, .05f)
                    lastTime = now
                    renderer?.render(snapshot, targetWidth, targetHeight, dt)
                    check(EGL14.eglSwapBuffers(display, surface)) { "Opaline EGL swap: ${EGL14.eglGetError()}" }
                    if (!ready) {
                        ready = true
                        post { if (!released) onReady(true) }
                    }
                    if (!snapshot.reducedMotion) schedule()
                } catch (error: Exception) {
                    Log.e("Opaline", "Native scene unavailable; keeping native controls", error)
                    destroyGpu()
                    post { onReady(false) }
                }
            }
        }

    override fun onSurfaceTextureAvailable(
        texture: SurfaceTexture,
        width: Int,
        height: Int,
    ) {
        // Bound pixel cost independently from density; semantic coordinates remain physical pixels.
        val scale = minOf(1f, 1600f / maxOf(width, height))
        val w = (width * scale).toInt().coerceAtLeast(1)
        val h = (height * scale).toInt().coerceAtLeast(1)
        texture.setDefaultBufferSize(w, h)
        handler.post {
            if (released) return@post
            try {
                targetWidth = w
                targetHeight = h
                display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
                check(display != EGL14.EGL_NO_DISPLAY)
                val version = IntArray(2)
                check(EGL14.eglInitialize(display, version, 0, version, 1))
                val configs = arrayOfNulls<EGLConfig>(1)
                val count = IntArray(1)
                val attributes =
                    intArrayOf(
                        EGL14.EGL_RENDERABLE_TYPE,
                        0x40,
                        EGL14.EGL_SURFACE_TYPE,
                        EGL14.EGL_WINDOW_BIT,
                        EGL14.EGL_RED_SIZE,
                        8,
                        EGL14.EGL_GREEN_SIZE,
                        8,
                        EGL14.EGL_BLUE_SIZE,
                        8,
                        EGL14.EGL_ALPHA_SIZE,
                        8,
                        EGL14.EGL_DEPTH_SIZE,
                        24,
                        EGL14.EGL_NONE,
                    )
                check(EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) && count[0] > 0)
                eglContext =
                    EGL14.eglCreateContext(
                        display,
                        configs[0],
                        EGL14.EGL_NO_CONTEXT,
                        intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE),
                        0,
                    )
                check(eglContext != EGL14.EGL_NO_CONTEXT)
                surface = EGL14.eglCreateWindowSurface(display, configs[0], texture, intArrayOf(EGL14.EGL_NONE), 0)
                check(surface != EGL14.EGL_NO_SURFACE)
                check(EGL14.eglMakeCurrent(display, surface, surface, eglContext))
                renderer = OpalineRenderer(context.assets).also { it.create() }
                schedule()
            } catch (error: Exception) {
                Log.e("Opaline", "Unable to create native world", error)
                destroyGpu()
                post { onReady(false) }
            }
        }
    }

    override fun onSurfaceTextureSizeChanged(
        texture: SurfaceTexture,
        width: Int,
        height: Int,
    ) {
        val scale = minOf(1f, 1600f / maxOf(width, height))
        val w = (width * scale).toInt().coerceAtLeast(1)
        val h = (height * scale).toInt().coerceAtLeast(1)
        texture.setDefaultBufferSize(w, h)
        handler.post {
            targetWidth = w
            targetHeight = h
            schedule()
        }
    }

    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
        // Return false: this thread releases the texture only AFTER EGL relinquishes the surface.
        handler.post {
            destroyGpu()
            texture.release()
            if (released) thread.quitSafely()
        }
        onReady(false)
        return false
    }

    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit

    private fun destroyGpu() {
        clock?.removeFrameCallback(draw)
        pending = false
        lastTime = 0
        ready = false
        renderer?.dispose()
        renderer = null
        if (display != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
            if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, eglContext)
            EGL14.eglTerminate(display)
        }
        surface = EGL14.EGL_NO_SURFACE
        eglContext = EGL14.EGL_NO_CONTEXT
        display = EGL14.EGL_NO_DISPLAY
    }

    fun release() {
        if (released) return
        released = true
        val awaitingSurfaceDestruction = isAvailable
        handler.post {
            destroyGpu()
            // The destruction callback retains the SurfaceTexture until EGL has released it.
            if (!awaitingSurfaceDestruction) thread.quitSafely()
        }
        onReady(false)
    }
}
