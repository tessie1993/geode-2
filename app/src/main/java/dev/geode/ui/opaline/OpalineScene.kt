package dev.geode.ui.opaline

import android.view.ViewTreeObserver
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
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

/**
 * One recipe instance's behaviour events. Pass the same object to every part of the instance;
 * [raise] resolves the event through that composition's behaviorBindings (event → action →
 * target part ids, wildcards included) and plays the mapped transition on this instance's parts.
 */
class OpalinePartEvents internal constructor() {
    internal var recipe: OpalineComposition? = null
    internal var world: OpalineWorld? = null
    internal var parts = 0

    /** Latest raise: target part id → action, published with its [serial]. */
    @Volatile internal var actions = emptyMap<String, String>()

    @Volatile internal var serial = 0L

    /** The last [serial] whose transitions the renderer saw settle on every part. */
    @Volatile internal var settled = 0L

    /** e.g. "activate", "open", "close", "show", "timeout", "select", "focus", "blur". */
    fun raise(event: String) {
        val recipe = recipe ?: return
        val targets = mutableMapOf<String, String>()
        for (binding in recipe.behaviorBindings.filter { it.event == event }) {
            val prefix = binding.target.removeSuffix("*")
            for (part in recipe.parts) {
                val wildcard = prefix != binding.target
                if (if (wildcard) part.id.startsWith(prefix) else part.id == prefix) {
                    targets[part.id] = binding.action
                }
            }
        }
        actions = targets
        serial++
    }

    /** Suspends until every transition started by [raise] on this instance has finished. */
    suspend fun awaitSettled() {
        val target = serial
        while (settled < target && parts > 0 && world?.drawing == true) withFrameNanos { it }
    }
}

@Composable
fun rememberOpalinePartEvents(): OpalinePartEvents = remember { OpalinePartEvents() }

/** Compose owns semantics; Kotlin/EGL draws supplied library geometry under stable content. */
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
    val density = LocalDensity.current.density
    val hostView = LocalView.current
    DisposableEffect(hostView, world) {
        val listener =
            ViewTreeObserver.OnPreDrawListener {
                world.publish()
                true
            }
        hostView.viewTreeObserver.addOnPreDrawListener(listener)
        onDispose {
            val observer = hostView.viewTreeObserver
            if (observer.isAlive) observer.removeOnPreDrawListener(listener)
        }
    }
    SideEffect {
        world.configure(
            OpalineFrame(
                reducedMotion = reducedMotion || motionAmount <= 0f,
                active = active,
                palette = palette,
                dim = backgroundDim.coerceIn(0f, 1f),
                motion = motionAmount.coerceIn(0f, 1.5f),
                environment = environment,
                transparent = transparent,
                density = density,
            ),
            section,
        )
    }
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
        modifier
            .background(if (transparent) Color.Transparent else OpalineColors.background)
            .onGloballyPositioned {
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

/**
 * Registers one catalogue part: [part] of the compositions.json recipe [composition] ("UI0xx"),
 * whose element, material and depth come from the catalogue, fitted to this node's layout bounds.
 * Unknown ids fail here, when the modifier is composed. Measured shells observe but never consume
 * pointer input; Compose retains scrolling and IME.
 */
fun Modifier.opalinePart(
    composition: String,
    part: String = "body",
    value: Float = 0.5f,
    selected: Boolean = false,
    enabled: Boolean = true,
    secondaryValue: Float = value,
    events: OpalinePartEvents? = null,
): Modifier =
    composed {
        val recipe = rememberOpalineComposition(composition)
        val spec = remember(composition, part) { recipe.part(part) }
        SideEffect { events?.recipe = recipe }
        opalineBody(
            OpalineBody(
                spec.element,
                spec.material,
                spec.dimensions,
                spec.rotation,
                spec.scale,
                spec.position.z,
                spec.id,
            ),
            value,
            selected,
            enabled,
            secondaryValue,
            events,
        )
    }

/**
 * Registers one library element outside any recipe (elements.json id). With [material] set,
 * the element's `gel` family renders with that selector (instantiate-composition.js rule);
 * null keeps every authored family. x/y extents = the layout bounds; z extent = the authored
 * depth (elements.json modelAsset.bounds) scaled by the smaller of the x and y fit scales.
 */
fun Modifier.opalineElement(
    element: String,
    material: String? = null,
    value: Float = 0.5f,
    selected: Boolean = false,
    enabled: Boolean = true,
    secondaryValue: Float = value,
    events: OpalinePartEvents? = null,
): Modifier =
    composed {
        val assets = LocalContext.current.assets
        remember(element, material) {
            require(material == null || material in OpalineMaterialTheme.FAMILIES) {
                "Unknown Opaline material selector \"$material\""
            }
            assets.open("opaline-native/$element.glb").close()
        }
        opalineBody(
            OpalineBody(element, material, null, ORIGIN, UNIT, 0f, element),
            value,
            selected,
            enabled,
            secondaryValue,
            events,
        )
    }

private val ORIGIN = OpalineVec3(0f, 0f, 0f)
private val UNIT = OpalineVec3(1f, 1f, 1f)

/** What the renderer fits to a node: see [OpalinePart]. */
private data class OpalineBody(
    val element: String,
    val material: String?,
    val dimensions: OpalineVec3?,
    val rotation: OpalineVec3,
    val scale: OpalineVec3,
    val z: Float,
    val name: String,
)

private fun Modifier.opalineBody(
    body: OpalineBody,
    value: Float,
    selected: Boolean,
    enabled: Boolean,
    secondaryValue: Float,
    events: OpalinePartEvents?,
): Modifier =
    composed {
        val world = LocalOpaline.current
        val id = remember { nextPartId.incrementAndGet() }
        val holder = remember { PartHolder(body) }
        SideEffect {
            holder.body = body
            holder.value = if (value.isFinite()) value.coerceIn(0f, 1f) else .5f
            holder.secondaryValue =
                if (secondaryValue.isFinite()) secondaryValue.coerceIn(0f, 1f) else holder.value
            holder.selected = selected
            holder.enabled = enabled
            holder.events = events
            events?.world = world
            world?.put(id, holder)
        }
        DisposableEffect(world, id, events) {
            events?.let { it.parts++ }
            onDispose {
                events?.let { it.parts-- }
                world?.remove(id)
            }
        }
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
                            val contacts = event.changes.filter { it.pressed || it.previousPressed }
                            for (change in contacts) {
                                // Native controls may consume their own drag. Keep their visual
                                // contact until release or until the pointer leaves the body.
                                val pressed =
                                    change.pressed &&
                                        change.position.x in 0f..size.width.toFloat() &&
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
                } finally {
                    world.view?.cancel(id)
                }
            }
    }

private class PartHolder(
    var body: OpalineBody,
) {
    var coordinates: LayoutCoordinates? = null

    fun refresh() {
        val current =
            coordinates?.takeIf { it.isAttached } ?: run {
                clip = Rect.Zero
                return
            }
        val size = Size(current.size.width.toFloat(), current.size.height.toFloat())
        bounds = Rect(current.positionInWindow(), size)
        clip = current.boundsInWindow()
        var parent = current.parentLayoutCoordinates
        depth = 0
        while (parent != null) {
            depth++
            parent = parent.parentLayoutCoordinates
        }
    }

    var value = .5f
    var secondaryValue = .5f
    var selected = false
    var enabled = true
    var events: OpalinePartEvents? = null
    var bounds = Rect.Zero
    var clip = Rect.Zero
    var depth = 0
}

internal class OpalineWorld {
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

    /** Whether the renderer is animating this host's parts right now. */
    val drawing: Boolean get() = ready && resumed && frame.active && !frame.reducedMotion

    fun configure(
        next: OpalineFrame,
        section: String,
    ) {
        // Section is an identity boundary for touch ownership, not a web route.
        if (currentSection != section) {
            parts.keys.forEach { view?.cancel(it) }
            currentSection = section
        }
        frame = next.copy(parts = frame.parts, width = frame.width, height = frame.height)
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
                        val empty = p.bounds.width <= 0 || p.bounds.height <= 0
                        if (!p.clip.overlaps(viewport) || empty) {
                            null
                        } else {
                            OpalinePart(
                                id = id,
                                element = p.body.element,
                                material = p.body.material,
                                dimensions = p.body.dimensions,
                                rotation = p.body.rotation,
                                scale = p.body.scale,
                                z = p.body.z,
                                bounds = p.bounds.translate(origin),
                                clip = p.clip.intersect(viewport).translate(origin),
                                value = p.value,
                                selected = p.selected,
                                enabled = p.enabled,
                                depth = p.depth,
                                secondaryValue = p.secondaryValue,
                                name = p.body.name,
                                events = p.events,
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
