package dev.geode.ui.opaline.kit

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.collapse
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.expand
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import dev.geode.R
import dev.geode.ui.opaline.ActionSlot
import dev.geode.ui.opaline.OpalineColors
import dev.geode.ui.opaline.OpalineComposition
import dev.geode.ui.opaline.OpalineIconButton
import dev.geode.ui.opaline.OpalinePartEvents
import dev.geode.ui.opaline.OpalineRecipeLayout
import dev.geode.ui.opaline.OpalineSceneHost
import dev.geode.ui.opaline.binding
import dev.geode.ui.opaline.frameBox
import dev.geode.ui.opaline.opalineElement
import dev.geode.ui.opaline.opalinePart
import dev.geode.ui.opaline.partBox
import dev.geode.ui.opaline.recipeFrame
import dev.geode.ui.opaline.recipePart
import dev.geode.ui.opaline.rememberOpalineComposition
import dev.geode.ui.opaline.rememberOpalineDismiss
import dev.geode.ui.opaline.rememberOpalinePartEvents
import kotlinx.coroutines.delay
import java.text.NumberFormat

/** The A-family selector the playing pebble renders with (elements.json A22 materialSelectors). */
private const val PLAYING_SELECTOR = "blue"

/**
 * UI043 Full-height sheet: body C03/shell with [content] in the `content` frame, [dismissButton]
 * on secondary A05/nacre and [confirmButton] on primary A05/gel, in its own window and scene.
 * Open → lift; a dismiss request plays close → return-to-mount first. [title] names the pane.
 */
@Composable
fun OpalineFullHeightSheet(
    title: String,
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: @Composable () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val events = rememberOpalinePartEvents()
    LaunchedEffect(events) { events.raise("open") }
    Dialog(
        rememberOpalineDismiss(events, onDismissRequest),
        DialogProperties(usePlatformDefaultWidth = false),
    ) {
        OpalineSceneHost(Modifier.fillMaxSize(), environment = false) {
            OpalineRecipeLayout(
                "UI043",
                Modifier.fillMaxSize().semantics { paneTitle = title },
                touch = "primary",
                fitWidth = true,
                flex = "content",
                flexAlignment = Alignment.TopCenter,
            ) {
                Box(Modifier.recipePart("body").opalinePart("UI043", events = events))
                Column(Modifier.recipeFrame("content").fillMaxSize(), content = content)
                ActionSlot("UI043", "secondary", events, dismissButton)
                ActionSlot("UI043", "primary", events, confirmButton)
            }
        }
    }
}

/**
 * UI045 Popover shell: body C05/shell (tail lower left) with [content] in the `content` frame, in
 * its own popup and scene above the anchor. Open → lift; dismissing plays close first.
 */
@Composable
fun OpalinePopover(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    SpeechPopup("UI045", tailRight = false, onDismissRequest, modifier) {
        Column(content = content)
    }
}

/** UI046 Tooltip shell: body C06/shell (tail lower right) holding [text], in its own popup. */
@Composable
fun OpalineTooltip(
    text: String,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SpeechPopup("UI046", tailRight = true, onDismissRequest, modifier) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = OpalineColors.text)
    }
}

@Composable
private fun SpeechPopup(
    composition: String,
    tailRight: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    val events = rememberOpalinePartEvents()
    val position = remember(tailRight) { SpeechAnchor(tailRight) }
    LaunchedEffect(events) { events.raise("open") }
    Popup(
        popupPositionProvider = position,
        onDismissRequest = rememberOpalineDismiss(events, onDismissRequest),
        properties = PopupProperties(focusable = !tailRight),
    ) {
        OpalineSceneHost(environment = false, transparent = true) {
            OpalineRecipeLayout(composition, modifier, flex = "content") {
                Box(Modifier.recipePart("body").opalinePart(composition, events = events))
                Box(Modifier.recipeFrame("content")) { content() }
            }
        }
    }
}

/**
 * Speech shells point their tail down (elements.json C05/C06 bounds reach further down and to
 * the tail side): the popup sits above the anchor, tail side aligned, else below it.
 */
private class SpeechAnchor(
    private val tailRight: Boolean,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val start =
            if (tailRight) anchorBounds.right - popupContentSize.width else anchorBounds.left
        val above = anchorBounds.top - popupContentSize.height
        return IntOffset(
            start.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)),
            if (above >= 0) above else anchorBounds.bottom,
        )
    }
}

/**
 * UI047 Toast shell: body C07/shell and status A03/glow in the toast's own scene; [message] in
 * the `message` frame, announced politely. Show → lift; after [timeoutMillis] (stretched to the
 * accessibility recommendation) timeout → return-to-mount plays before [onTimeout].
 */
@Composable
fun OpalineToast(
    message: String,
    timeoutMillis: Long,
    onTimeout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val events = rememberOpalinePartEvents()
    val accessibility = LocalAccessibilityManager.current
    val timeout = rememberOpalineDismiss(events, onTimeout, "timeout")
    LaunchedEffect(message) {
        events.raise("show")
        delay(
            accessibility?.calculateRecommendedTimeoutMillis(timeoutMillis, containsText = true)
                ?: timeoutMillis,
        )
        timeout()
    }
    OpalineSceneHost(modifier, environment = false, transparent = true) {
        MessageShell("UI047", "status", false, events, Modifier) {
            Text(
                message,
                Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.bodyMedium,
                color = OpalineColors.text,
            )
        }
    }
}

/**
 * UI048 Inline validation shell: body C04/shell and status A03/glow (lifted while [isError]);
 * [message] in the `message` frame, announced politely. Raises validationchange (set-status).
 */
@Composable
fun OpalineInlineValidation(
    message: String,
    isError: Boolean,
    modifier: Modifier = Modifier,
) {
    val events = rememberOpalinePartEvents()
    LaunchedEffect(message, isError) { events.raise("validationchange") }
    MessageShell("UI048", "status", isError, events, modifier) {
        Text(
            message,
            Modifier.semantics {
                liveRegion = LiveRegionMode.Polite
                if (isError) error(message)
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (isError) OpalineColors.error else OpalineColors.muted,
        )
    }
}

/**
 * UI053 Status badge: body A05/nacre and signal A03/glow (lifted while [active]); [text] in the
 * `status` frame. Raises statuschange (set-status on signal).
 */
@Composable
fun OpalineStatusBadge(
    text: String,
    modifier: Modifier = Modifier,
    active: Boolean = true,
) {
    val events = rememberOpalinePartEvents()
    LaunchedEffect(text, active) { events.raise("statuschange") }
    MessageShell("UI053", "signal", active, events, modifier, frame = "status") {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = OpalineColors.text,
            maxLines = 1,
        )
    }
}

/** Body, one status light and the content frame whose content sizes the shell. */
@Composable
private fun MessageShell(
    composition: String,
    light: String,
    lit: Boolean,
    events: OpalinePartEvents,
    modifier: Modifier,
    frame: String = "message",
    content: @Composable () -> Unit,
) {
    OpalineRecipeLayout(
        composition,
        modifier,
        flex = frame,
        flexAlignment = Alignment.CenterStart,
    ) {
        Box(Modifier.recipePart("body").opalinePart(composition, events = events))
        Box(
            Modifier
                .recipePart(light)
                .opalinePart(composition, light, selected = lit, events = events),
        )
        Box(Modifier.recipeFrame(frame)) { content() }
    }
}

/** UI050 Circular progress ring: body E14/water valued by [progress]; raises progress (set-arc). */
@Composable
fun OpalineProgressRing(
    progress: Float,
    modifier: Modifier = Modifier,
) {
    val range = rememberOpalineComposition("UI050").bindingRange("set-arc")
    val events = rememberOpalinePartEvents()
    val current = if (progress.isFinite()) progress.coerceIn(range) else range.start
    val fraction = (current - range.start) / (range.endInclusive - range.start)
    LaunchedEffect(current) { events.raise("progress") }
    OpalineRecipeLayout(
        "UI050",
        modifier.semantics { progressBarRangeInfo = ProgressBarRangeInfo(current, range) },
    ) {
        Box(Modifier.recipePart("body").opalinePart("UI050", value = fraction, events = events))
        Box(Modifier.recipeFrame("progress"), contentAlignment = Alignment.Center) {
            Text(
                NumberFormat.getPercentInstance().format(fraction.toDouble()),
                style = MaterialTheme.typography.labelMedium,
                color = OpalineColors.text,
            )
        }
    }
}

/** UI051 Orbit loading assembly: body D08/gel; raises pending (orbit) and resolve (settle). */
@Composable
fun OpalineOrbitLoading(
    pending: Boolean,
    modifier: Modifier = Modifier,
) {
    val events = rememberOpalinePartEvents()
    LaunchedEffect(pending) { events.raise(if (pending) "pending" else "resolve") }
    OpalineRecipeLayout(
        "UI051",
        modifier.semantics {
            if (pending) progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
        },
    ) { Box(Modifier.recipePart("body").opalinePart("UI051", events = events)) }
}

/**
 * UI052 Skeleton content shell: body C01/shell and skeleton-0…2 A05/nacre while [loading]
 * (pending → soft-pulse); resolve → content-reveal, then [content] where the bars stood.
 */
@Composable
fun OpalineSkeleton(
    loading: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val recipe = rememberOpalineComposition("UI052")
    val events = rememberOpalinePartEvents()
    LaunchedEffect(loading) { events.raise(if (loading) "pending" else "resolve") }
    if (loading) {
        OpalineRecipeLayout(
            "UI052",
            modifier.semantics { progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate },
            fitWidth = true,
        ) {
            for (part in recipe.parts) {
                Box(Modifier.recipePart(part.id).opalinePart("UI052", part.id, events = events))
            }
        }
        return
    }
    val body = recipe.partBox("body")
    val bars = recipe.parts.filter { it.id != "body" }.map { recipe.partBox(it.id) }
    BoxWithConstraints(modifier) {
        val scale = maxWidth.value / body.width
        Box(Modifier.matchParentSize().opalinePart("UI052", events = events))
        Column(
            Modifier.padding(
                start = ((bars.minOf { it.left } - body.left) * scale).dp,
                top = ((bars.minOf { it.top } - body.top) * scale).dp,
                end = ((body.right - bars.maxOf { it.right }) * scale).dp,
                bottom = ((body.bottom - bars.maxOf { it.bottom }) * scale).dp,
            ),
            content = content,
        )
    }
}

/**
 * UI054 Notification stack: body C15/shell; the first of [messages] in `front-message`, all of
 * them while [expanded]. Activation raises activate (depth-fan-expand) or dismiss (collapse).
 */
@Composable
fun OpalineNotificationStack(
    messages: List<String>,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val events = rememberOpalinePartEvents()
    val toggle = {
        events.raise(if (expanded) "dismiss" else "activate")
        onExpandedChange(!expanded)
    }
    OpalineRecipeLayout(
        "UI054",
        modifier
            .clickable(role = Role.Button, onClick = toggle)
            .semantics { expandable(expanded, toggle) },
        flex = "front-message",
        flexAlignment = Alignment.TopStart,
    ) {
        Box(Modifier.recipePart("body").opalinePart("UI054", events = events))
        Column(
            Modifier.recipeFrame("front-message").semantics { liveRegion = LiveRegionMode.Polite },
        ) {
            for (message in if (expanded) messages else messages.take(1)) {
                Text(
                    message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = OpalineColors.text,
                )
            }
        }
    }
}

/** The expand / collapse accessibility action of a control that toggles expansion. */
internal fun SemanticsPropertyReceiver.expandable(
    expanded: Boolean,
    toggle: () -> Unit,
) {
    val action = {
        toggle()
        true
    }
    if (expanded) collapse(action = action) else expand(action = action)
}

/**
 * Mini player bar (plan §12a ◆1, video V3): the UI047 body (C07/shell capsule) as the bar, the
 * artwork in a UI061 frame, title, artist and a thin UI019 progress in the UI047 `message`
 * frame, a large UI003 play/pause pebble in the `blue` selector while [isPlaying], then next and
 * queue UI003 actions.
 */
@Composable
fun OpalineMiniPlayerBar(
    title: String,
    artist: String,
    isPlaying: Boolean,
    progress: Float,
    onOpen: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onQueue: () -> Unit,
    modifier: Modifier = Modifier,
    artwork: @Composable () -> Unit = {},
) {
    val toast = rememberOpalineComposition("UI047")
    val action = rememberOpalineComposition("UI003")
    val range = rememberOpalineComposition("UI019").bindingRange("bounded-axis")
    val current = if (progress.isFinite()) progress.coerceIn(range) else range.start
    val bar = toast.partBox("body")
    val message = toast.frameBox("message")
    val padding = toast.frame("message").padding
    val pebble = action.part("body")
    val label = stringResource(if (isPlaying) R.string.action_pause else R.string.action_play)
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val scale = maxWidth.value / bar.width
        Row(
            Modifier
                .padding(vertical = ((pebble.dimensions.y - bar.height) / 2 * scale).dp)
                .fillMaxWidth()
                .height((bar.height * scale).dp)
                .opalinePart("UI047")
                .clickable(role = Role.Button, onClick = onOpen)
                .padding(
                    // Leading clearance of the status slot, trailing clearance of the message.
                    start = ((toast.partBox("status").left - bar.left) * scale).dp,
                    end = ((bar.right - message.right) * scale).dp,
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy((padding * scale).dp),
        ) {
            OpalineCircularImageFrame(
                Modifier.size(((bar.height - 2 * padding) * scale).dp),
                image = artwork,
            )
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    color = OpalineColors.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = OpalineColors.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height((padding * scale).dp)
                        .opalinePart("UI019", value = current)
                        .semantics { progressBarRangeInfo = ProgressBarRangeInfo(current, range) },
                )
            }
            Box(
                Modifier
                    .requiredSize((pebble.dimensions.x * scale).dp)
                    .opalineElement(
                        pebble.element,
                        if (isPlaying) PLAYING_SELECTOR else pebble.material,
                    ).clickable(role = Role.Button, onClick = onPlayPause),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    label,
                    Modifier.size((action.frame("content").width * scale).dp),
                    tint = OpalineColors.text,
                )
            }
            OpalineIconButton(Icons.Filled.SkipNext, stringResource(R.string.action_next), onNext)
            OpalineIconButton(
                Icons.AutoMirrored.Filled.QueueMusic,
                stringResource(R.string.queue),
                onQueue,
            )
        }
    }
}

/** The `range` parameter of the binding with [action], e.g. UI050 set-arc [0, 1]. */
internal fun OpalineComposition.bindingRange(action: String): ClosedFloatingPointRange<Float> {
    val range = (binding(action)["range"] as List<*>).map { (it as Number).toFloat() }
    return range[0]..range[1]
}
