package dev.geode.ui.opaline.creative

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.geode.ui.opaline.OpalineButton
import dev.geode.ui.opaline.OpalineColors
import dev.geode.ui.opaline.OpalineDialog
import dev.geode.ui.opaline.OpalinePanel
import dev.geode.ui.opaline.OpalinePressable
import dev.geode.ui.opaline.OpalineRecipeLayout
import dev.geode.ui.opaline.OpalineSceneHost
import dev.geode.ui.opaline.OpalineTextField
import dev.geode.ui.opaline.OpalineToggle
import dev.geode.ui.opaline.binding
import dev.geode.ui.opaline.kit.OpalineThreeWaySelector
import dev.geode.ui.opaline.opalinePart
import dev.geode.ui.opaline.opalineReady
import dev.geode.ui.opaline.opalineSliderColors
import dev.geode.ui.opaline.recipeFrame
import dev.geode.ui.opaline.recipePart
import dev.geode.ui.opaline.rememberOpalineComposition
import dev.geode.ui.opaline.rememberOpalineDismiss
import dev.geode.ui.opaline.rememberOpalinePartEvents
import dev.geode.ui.opaline.rememberRecipeRail
import dev.geode.ui.opaline.sibling
import dev.geode.ui.opaline.stops
import kotlin.math.atan2
import kotlin.math.roundToInt

/** Native content planes and semantics attached to the Opaline geometry world. */
object CreativeColors {
    val textPrimary = OpalineColors.text
    val textSecondary = OpalineColors.muted
    val mint = OpalineColors.accent
    val lavender = OpalineColors.lavender
    val peach = OpalineColors.amber
    val pink = OpalineColors.error
    val sky = OpalineColors.gel
    val glassFill = OpalineColors.surface
    val glassRim = OpalineColors.rim
}

object CreativeShapes {
    val tile = RoundedCornerShape(28.dp)
    val pill = RoundedCornerShape(50)
    val bubble = CircleShape
}

object CreativeIcons {
    val Close = Icons.Filled.Close
}

/** UI039 Content card body (C01/shell) under this node. */
@Suppress("UNUSED_PARAMETER")
fun Modifier.creativeSurface(
    shape: Shape = CreativeShapes.tile,
    tint: Color? = null,
    selected: Boolean = false,
    glow: Float = 0f,
): Modifier = opalinePart("UI039", selected = selected)

@Suppress("UNUSED_PARAMETER")
fun Modifier.creativeFloat(strength: Float = 1f): Modifier = this

/** UI010 chip A05/gel holding [content], lifted while selected; activate → toggle-filter. */
@Suppress("UNUSED_PARAMETER")
@Composable
fun CreativeButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    compact: Boolean = false,
    filled: Boolean = true,
    selected: Boolean = false,
    tint: Color? = null,
    content: @Composable () -> Unit,
) {
    OpalinePressable(
        "UI010",
        "chip-0",
        "chip-0",
        onClick,
        modifier,
        enabled,
        selected || filled && tint != null,
        content = content,
    )
}

@Composable
fun CreativeButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    selected: Boolean = false,
    tint: Color? = null,
) =
    OpalineButton(
        text,
        onClick,
        modifier,
        icon = icon,
        enabled = enabled,
        selected = selected || tint != null,
    )

/**
 * UI019 Horizontal slider (B01/gel), or UI022 Stepped slider (B05/gel) when [steps] gives its
 * binding's stops. The Material slider keeps direct manipulation.
 */
@Composable
fun CreativeSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    enabled: Boolean = true,
    onValueChangeFinished: (() -> Unit)? = null,
) {
    val span = valueRange.endInclusive - valueRange.start
    val fraction =
        if (span > 0 && value.isFinite()) (value - valueRange.start) / span else 0f
    val stepped = rememberOpalineComposition("UI022").stops().size - 2 == steps
    Slider(
        value = if (value.isFinite()) value.coerceIn(valueRange) else valueRange.start,
        onValueChange = onValueChange,
        modifier =
            modifier.opalinePart(
                if (stepped) "UI022" else "UI019",
                value = fraction,
                enabled = enabled,
            ),
        enabled = enabled,
        valueRange = valueRange,
        steps = steps,
        onValueChangeFinished = onValueChangeFinished,
        colors = opalineSliderColors(),
    )
}

@Composable
fun CreativeToggle(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    OpalineToggle(
        checked,
        { onCheckedChange?.invoke(it) },
        modifier,
        enabled && onCheckedChange != null,
    )
}

/**
 * UI023 Circular dial: body B13/gel, value in the `value` frame. Drag follows motion.js
 * MotionController.drag for rotary kinds: value += (dx − dy) · 2.4 over the host view's size; a
 * tap sets the angular value on the binding's sweep, centred at 12 o'clock.
 */
@Composable
fun CreativeKnob(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    enabled: Boolean = true,
    knobSize: Dp = 72.dp,
) {
    val span = valueRange.endInclusive - valueRange.start
    val fraction = if (span > 0) ((value - valueRange.start) / span).coerceIn(0f, 1f) else 0f
    val latest = rememberUpdatedState(value)
    val change = rememberUpdatedState(onValueChange)
    val view = LocalView.current
    val sweep =
        (rememberOpalineComposition("UI023").binding("angular-value")["sweepRadians"] as Number)
            .toFloat()
    OpalineRecipeLayout(
        "UI023",
        modifier
            .size(knobSize)
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(value, valueRange)
                if (!enabled) disabled()
                setProgress {
                    if (enabled) change.value(it.coerceIn(valueRange))
                    enabled
                }
            }.pointerInput(enabled, valueRange) {
                if (enabled) {
                    var turned = latest.value
                    detectDragGestures(onDragStart = { turned = latest.value }) { contact, drag ->
                        contact.consume()
                        val dx = drag.x / view.width.coerceAtLeast(1)
                        val dy = drag.y / view.height.coerceAtLeast(1)
                        turned = (turned + (dx - dy) * 2.4f * span).coerceIn(valueRange)
                        change.value(turned)
                    }
                }
            }.pointerInput(enabled, valueRange, sweep) {
                if (enabled) {
                    detectTapGestures { at ->
                        val angle = atan2(at.x - size.width / 2f, size.height / 2f - at.y)
                        val turn = (angle / sweep + .5f).coerceIn(0f, 1f)
                        change.value(valueRange.start + turn * span)
                    }
                }
            },
    ) {
        Box(Modifier.recipePart("body").opalinePart("UI023", value = fraction, enabled = enabled))
        Text(
            "${(fraction * 100).roundToInt()}",
            Modifier.recipeFrame("value"),
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

/**
 * UI035 Tab rail: body D02/shell, tab-* A05/gel (selected tab lifted); activate → select-tab.
 * A tab that [enabled] rejects looks and acts disabled.
 */
@Composable
fun CreativeTabs(
    titles: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    scrollable: Boolean = true,
    enabled: (Int) -> Boolean = { true },
) {
    val recipe = rememberOpalineComposition("UI035")
    val (padding, gap) = rememberRecipeRail("UI035", "body", "tab-0")
    Box(if (scrollable) modifier.horizontalScroll(rememberScrollState()) else modifier) {
        Box(Modifier.matchParentSize().opalinePart("UI035"))
        Row(
            Modifier.selectableGroup().padding(padding),
            horizontalArrangement = Arrangement.spacedBy(gap),
        ) {
            titles.forEachIndexed { index, title ->
                val tab = recipe.sibling("tab-", index)
                OpalinePressable(
                    "UI035",
                    tab,
                    tab,
                    { onSelect(index) },
                    enabled = enabled(index),
                    selected = index == selected,
                    checked = index == selected,
                    role = Role.Tab,
                ) { Text(title) }
            }
        }
    }
}

/** UI009 Three-way selector for as many options as it has frames, else the UI035 tab rail. */
@Composable
fun CreativeSegments(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (options.size == rememberOpalineComposition("UI009").contentFrames.size) {
        OpalineThreeWaySelector(options, selected, onSelect, modifier)
    } else {
        CreativeTabs(options, selected, onSelect, modifier)
    }
}

/**
 * UI049 Linear progress channel: body E12/shell, progress → set-fill. The Material indicator
 * keeps progress semantics and is the visible fallback until the scene is ready.
 */
@Composable
fun CreativeProgress(
    progress: Float,
    modifier: Modifier = Modifier,
) {
    val fraction = if (progress.isFinite()) progress.coerceIn(0f, 1f) else 0f
    val ready = opalineReady()
    OpalineRecipeLayout("UI049", modifier, fitWidth = true) {
        Box(Modifier.recipePart("body").opalinePart("UI049", value = fraction)) {
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxSize(),
                color = if (ready) Color.Transparent else OpalineColors.accent,
                trackColor = if (ready) Color.Transparent else OpalineColors.surface,
            )
        }
    }
}

/** UI011 / UI012 / UI014 through [OpalineTextField]. */
@Composable
fun CreativeTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    enabled: Boolean = true,
    singleLine: Boolean = true,
    keyboardType: KeyboardType = KeyboardType.Text,
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    OpalineTextField(
        value,
        onValueChange,
        modifier,
        enabled = enabled,
        singleLine = singleLine,
        placeholder = placeholder?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        visualTransformation = visualTransformation,
    )
}

/**
 * UI044 Bottom sheet: body C01/shell with the content in its `content` frame. Open → lift; a
 * dismiss request plays close → return-to-mount first.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreativeSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    content: @Composable () -> Unit,
) {
    val events = rememberOpalinePartEvents()
    LaunchedEffect(events) { events.raise("open") }
    ModalBottomSheet(
        rememberOpalineDismiss(events, onDismissRequest),
        modifier,
        sheetState,
        containerColor = OpalineColors.surface,
        contentColor = CreativeColors.textPrimary,
    ) {
        OpalineSceneHost(Modifier.fillMaxWidth(), environment = false) {
            OpalineRecipeLayout(
                "UI044",
                Modifier.fillMaxWidth(),
                fitWidth = true,
                flex = "content",
                flexAlignment = Alignment.TopCenter,
            ) {
                Box(Modifier.recipePart("body").opalinePart("UI044", events = events))
                Column(Modifier.recipeFrame("content").fillMaxWidth()) { content() }
            }
        }
    }
}

/** UI042 Dialog shell holding the title, text and actions in its `content` frame. */
@Composable
fun CreativeDialog(
    onDismissRequest: () -> Unit,
    title: String,
    modifier: Modifier = Modifier,
    text: String? = null,
    actions: @Composable () -> Unit = {},
) {
    OpalineDialog(onDismissRequest) {
        OpalinePanel(
            modifier.widthIn(min = 280.dp, max = 440.dp).semantics { paneTitle = title },
            recipe = "UI042",
        ) {
            Text(title, style = MaterialTheme.typography.headlineSmall)
            text?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) { actions() }
        }
    }
}

/** UI041 Portrait content card through [OpalinePanel]. */
@Composable
fun CreativeTile(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    OpalinePanel(modifier.clickable(onClick = onClick)) {
        Column(Modifier.padding(contentPadding), content = content)
    }
}

@Composable
fun CreativeTopBar(title: String) {
    Text(title, Modifier.padding(20.dp), style = MaterialTheme.typography.headlineMedium)
}
