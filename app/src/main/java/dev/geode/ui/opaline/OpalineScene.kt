package dev.geode.ui.opaline

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
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
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
    backgroundDim: Float = 0f,
    motionAmount: Float = 1f,
    environment: Boolean = true,
    content: @Composable () -> Unit,
) {
    val world = remember { OpalineWorld() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val palette = LocalOpalinePalette.current
    SideEffect { world.configure(reducedMotion, active, palette, backgroundDim, motionAmount, environment, section) }
    DisposableEffect(lifecycle, world) {
        world.resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        val observer = LifecycleEventObserver { _, _ ->
            world.resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            world.publish()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); world.dispose() }
    }
    Box(modifier.background(OpalineColors.background).onGloballyPositioned { world.viewport = it.boundsInWindow(); world.publish() }) {
        AndroidView(
            factory = { context -> OpalineTextureView(context) { world.ready = it }.also { world.view = it; world.publish() } },
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
): Modifier = composed {
    val world = LocalOpaline.current
    val id = remember { nextPartId.incrementAndGet() }
    val holder = remember { PartHolder() }
    SideEffect {
        holder.element = element
        holder.value = if (value.isFinite()) value.coerceIn(0f, 1f) else .5f
        holder.selected = selected; holder.enabled = enabled
        world?.put(id, holder)
    }
    DisposableEffect(world, id) { onDispose { world?.remove(id) } }
    this.drawBehind {
        if (world?.ready != true) {
            drawRoundRect(Brush.verticalGradient(listOf(Color(0xFF527C90), Color(0xFF173444))), cornerRadius = CornerRadius(size.minDimension * .35f))
        }
    }.onGloballyPositioned {
        holder.bounds = Rect(it.positionInWindow(), Size(it.size.width.toFloat(), it.size.height.toFloat()))
        holder.clip = it.boundsInWindow()
        var parent = it.parentLayoutCoordinates
        var depth = 0
        while (parent != null) { depth++; parent = parent.parentLayoutCoordinates }
        holder.depth = depth
        world?.put(id, holder)
    }.pointerInput(world, id, enabled) {
        if (world == null || !enabled) return@pointerInput
        try {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Final)
                    event.changes.forEach { change ->
                        if (change.pressed || change.previousPressed) {
                            // Scroll ownership cancels contact in the old body.
                            val pressed = change.pressed && (!change.isConsumed || !change.previousPressed)
                            world.view?.touch(id, change.id.value, pressed,
                                change.position.x / size.width.coerceAtLeast(1), change.position.y / size.height.coerceAtLeast(1))
                        }
                    }
                }
            }
        } finally { world.view?.cancel(id) }
    }
}

private class PartHolder {
    var element = "A01"
    var value = .5f
    var selected = false
    var enabled = true
    var bounds = Rect.Zero
    var clip = Rect.Zero
    var depth = 0
}

private class OpalineWorld {
    var ready by mutableStateOf(false)
    var view: OpalineTextureView? = null
    var viewport = Rect.Zero
    var resumed = false
    private val parts = linkedMapOf<Long, PartHolder>()
    private var frame = OpalineFrame()
    private var disposed = false
    private var scheduled = false

    fun configure(reduced: Boolean, active: Boolean, palette: OpalinePalette, dim: Float, motion: Float, environment: Boolean, section: String) {
        // Section is an identity boundary for touch ownership, not a web route.
        if (currentSection != section) { parts.keys.forEach { view?.cancel(it) }; currentSection = section }
        frame = frame.copy(reducedMotion = reduced || motion <= 0f, active = active, palette = palette,
            dim = dim.coerceIn(0f, 1f), motion = motion.coerceIn(0f, 1.5f), environment = environment)
        publish()
    }
    private var currentSection = ""
    fun put(id: Long, holder: PartHolder) { if (!disposed) { parts[id] = holder; publish() } }
    fun remove(id: Long) { view?.cancel(id); parts.remove(id); publish() }
    fun publish() {
        val target = view ?: return
        if (disposed || scheduled) return
        scheduled = true
        target.postOnAnimation {
            scheduled = false
            if (!disposed && viewport.width > 0f && viewport.height > 0f) {
                val origin = Offset(-viewport.left, -viewport.top)
                val visible = parts.mapNotNull { (id, p) ->
                    if (!p.clip.overlaps(viewport) || p.bounds.width <= 0 || p.bounds.height <= 0) null
                    else OpalinePart(id, p.element, p.bounds.translate(origin), p.clip.intersect(viewport).translate(origin),
                        p.value, p.selected, p.enabled, p.depth)
                }
                target.update(frame.copy(parts = visible, width = viewport.width, height = viewport.height, active = frame.active && resumed))
            }
        }
    }
    fun dispose() { disposed = true; parts.clear(); view?.release(); view = null; ready = false }
}
