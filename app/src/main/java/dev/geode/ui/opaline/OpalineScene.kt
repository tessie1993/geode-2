package dev.geode.ui.opaline

import android.view.ViewTreeObserver
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.concurrent.atomic.AtomicLong

private val nextPartId = AtomicLong()
private val LocalOpaline = staticCompositionLocalOf<OpalineWorld?> { null }
val LocalOpalinePalette = staticCompositionLocalOf { OpalinePalette.TIDAL }

@Composable
fun opalineReady(): Boolean = LocalOpaline.current?.ready == true

/** Kotlin/Compose owns semantics; Kotlin/EGL draws supplied library geometry under stable content. */
@Composable
fun OpalineSceneHost(
    modifier: Modifier = Modifier,
    reducedMotion: Boolean = LocalOpalineReducedMotion.current,
    section: String = "player",
    active: Boolean = true,
    backgroundDim: Float = LocalOpalineBackgroundDim.current,
    motionAmount: Float = LocalOpalineMotionAmount.current,
    environment: Boolean = true,
    transparent: Boolean = false,
    content: @Composable () -> Unit,
) {
    val world = remember { OpalineWorld() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val palette = LocalOpalinePalette.current
    val hostView = LocalView.current
    DisposableEffect(hostView, world) {
        val listener =
            ViewTreeObserver.OnPreDrawListener {
                world.publish()
                true
            }
        hostView.viewTreeObserver.addOnPreDrawListener(listener)
        onDispose {
            if (hostView.viewTreeObserver.isAlive) hostView.viewTreeObserver.removeOnPreDrawListener(listener)
        }
    }
    SideEffect { world.configure(reducedMotion, active, palette, backgroundDim, motionAmount, environment, transparent, section) }
    DisposableEffect(lifecycle, world) {
        world.resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        val observer =
            LifecycleEventObserver { _, _ ->
                world.resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
                world.publish()
            }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            world.dispose()
        }
    }
    Box(
        modifier.background(if (transparent) Color.Transparent else OpalineColors.background).onGloballyPositioned {
            world.coordinates = it
            world.viewport = it.boundsInWindow()
            world.publish()
        },
    ) {
        AndroidView(
            factory = { context ->
                OpalineTextureView(context) { world.ready = it }.also {
                    world.view = it
                    world.publish()
                }
            },
            modifier = Modifier.matchParentSize(),
            onRelease = { it.release() },
        )
        CompositionLocalProvider(LocalOpaline provides world) { content() }
    }
}

/** Measured shells observe but never consume pointer input; Compose retains scrolling and IME. */
fun Modifier.opalinePart(
    element: String = "A01",
    value: Float = 0.5f,
    selected: Boolean = false,
    enabled: Boolean = true,
    secondaryValue: Float = value,
): Modifier =
    composed {
        val world = LocalOpaline.current
        val id = remember { nextPartId.incrementAndGet() }
        val holder = remember { PartHolder() }
        SideEffect {
            holder.element = element
            holder.value = if (value.isFinite()) value.coerceIn(0f, 1f) else .5f
            holder.secondaryValue = if (secondaryValue.isFinite()) secondaryValue.coerceIn(0f, 1f) else holder.value
            holder.selected = selected
            holder.enabled = enabled
            world?.put(id, holder)
        }
        DisposableEffect(world, id) { onDispose { world?.remove(id) } }
        this
            .drawBehind {
                if (world?.ready != true) {
                    drawRoundRect(
                        Brush.verticalGradient(listOf(Color(0xFF527C90), Color(0xFF173444))),
                        cornerRadius =
                            CornerRadius(size.minDimension * .35f),
                    )
                }
            }.onGloballyPositioned {
                holder.coordinates = it
                holder.refresh()
                world?.put(id, holder)
            }.pointerInput(world, id, enabled) {
                if (world == null || !enabled) return@pointerInput
                try {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Final)
                            event.changes.forEach { change ->
                                if (change.pressed || change.previousPressed) {
                                    // Native controls may consume their own drag. Keep their visual contact
                                    // until release or until the pointer leaves this body's current bounds.
                                    val pressed =
                                        change.pressed && change.position.x in 0f..size.width.toFloat() &&
                                            change.position.y in 0f..size.height.toFloat()
                                    world.view?.touch(
                                        id,
                                        change.id.value,
                                        pressed,
                                        change.position.x / size.width.coerceAtLeast(1),
                                        change.position.y / size.height.coerceAtLeast(1),
                                    )
                                }
                            }
                        }
                    }
                } finally {
                    world.view?.cancel(id)
                }
            }
    }

private class PartHolder {
    var coordinates: LayoutCoordinates? = null

    fun refresh() {
        val current =
            coordinates?.takeIf { it.isAttached } ?: run {
                clip = Rect.Zero
                return
            }
        bounds = Rect(current.positionInWindow(), Size(current.size.width.toFloat(), current.size.height.toFloat()))
        clip = current.boundsInWindow()
        var parent = current.parentLayoutCoordinates
        depth = 0
        while (parent != null) {
            depth++
            parent = parent.parentLayoutCoordinates
        }
    }

    var element = "A01"
    var value = .5f
    var secondaryValue = .5f
    var selected = false
    var enabled = true
    var bounds = Rect.Zero
    var clip = Rect.Zero
    var depth = 0
}

private class OpalineWorld {
    var ready by mutableStateOf(false)
    var view: OpalineTextureView? = null
    var coordinates: LayoutCoordinates? = null
    var viewport = Rect.Zero
    var resumed = false
    private val parts = linkedMapOf<Long, PartHolder>()
    private var frame = OpalineFrame()
    private var submitted: OpalineFrame? = null
    private var disposed = false
    private var scheduled = false

    fun configure(
        reduced: Boolean,
        active: Boolean,
        palette: OpalinePalette,
        dim: Float,
        motion: Float,
        environment: Boolean,
        transparent: Boolean,
        section: String,
    ) {
        // Section is an identity boundary for touch ownership, not a web route.
        if (currentSection != section) {
            parts.keys.forEach { view?.cancel(it) }
            currentSection = section
        }
        frame =
            frame.copy(
                reducedMotion = reduced || motion <= 0f,
                active = active,
                palette = palette,
                dim = dim.coerceIn(0f, 1f),
                motion = motion.coerceIn(0f, 1.5f),
                environment = environment,
                transparent = transparent,
            )
        publish()
    }

    private var currentSection = ""

    fun put(
        id: Long,
        holder: PartHolder,
    ) {
        if (!disposed) {
            parts[id] = holder
            publish()
        }
    }

    fun remove(id: Long) {
        view?.cancel(id)
        parts.remove(id)
        publish()
    }

    fun publish() {
        val target = view ?: return
        if (disposed || scheduled) return
        scheduled = true
        target.postOnAnimation {
            scheduled = false
            coordinates?.takeIf { it.isAttached }?.let { viewport = it.boundsInWindow() }
            if (!disposed && viewport.width > 0f && viewport.height > 0f) {
                val origin = Offset(-viewport.left, -viewport.top)
                val visible =
                    parts.mapNotNull { (id, p) ->
                        p.refresh()
                        if (!p.clip.overlaps(viewport) || p.bounds.width <= 0 || p.bounds.height <= 0) {
                            null
                        } else {
                            OpalinePart(
                                id,
                                p.element,
                                p.bounds.translate(origin),
                                p.clip.intersect(viewport).translate(origin),
                                p.value,
                                p.selected,
                                p.enabled,
                                p.depth,
                                p.secondaryValue,
                            )
                        }
                    }
                val snapshot =
                    frame.copy(
                        parts = visible,
                        width = viewport.width,
                        height = viewport.height,
                        active =
                            frame.active && resumed,
                    )
                if (snapshot != submitted) {
                    submitted = snapshot
                    target.update(snapshot)
                }
            }
        }
    }

    fun dispose() {
        disposed = true
        parts.clear()
        view?.release()
        view = null
        ready = false
    }
}
