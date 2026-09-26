package dev.geode.ui.opaline

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderColors
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import dev.geode.R
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** Unscrolling page frame; the caller owns its lazy list or scroll state. Back is UI003. */
@Composable
fun OpalinePage(
    title: String,
    subtitle: String = "",
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (onBack != null) {
                OpalineIconButton(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    stringResource(R.string.opaline_back),
                    onBack,
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    title,
                    style = MaterialTheme.typography.headlineLarge,
                    color = OpalineColors.text,
                )
                if (subtitle.isNotBlank()) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = OpalineColors.muted,
                    )
                }
            }
            actions()
        }
        content()
    }
}

/**
 * UI041 Portrait content card (C03/shell) by default; [recipe] picks any card, dialog or sheet
 * with a `body` part and a `content` frame (UI039–UI046). Raises open → lift when it appears.
 */
@Composable
fun OpalinePanel(
    modifier: Modifier = Modifier,
    recipe: String = "UI041",
    content: @Composable ColumnScope.() -> Unit,
) {
    val events = rememberOpalinePartEvents()
    LaunchedEffect(events) { events.raise("open") }
    OpalineRecipeLayout(
        recipe,
        modifier.fillMaxWidth(),
        fitWidth = true,
        flex = "content",
        flexAlignment = Alignment.TopCenter,
    ) {
        Box(Modifier.recipePart("body").opalinePart(recipe, events = events))
        Column(
            Modifier.recipeFrame("content").fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

/** UI001 Primary action pebble: body A01/gel, label in the `content` frame; activate → commit. */
@Composable
fun OpalineButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    selected: Boolean = false,
) {
    OpalinePressable("UI001", "body", "content", onClick, modifier, enabled, selected) {
        OpalineLabel(text, icon)
    }
}

/** A text label with an optional leading icon, coloured by the part it sits on. */
@Composable
internal fun OpalineLabel(
    text: String,
    icon: ImageVector?,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (icon != null) Icon(icon, null, Modifier.size(20.dp))
        Text(text, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * UI010 chip A05/gel; activate → toggle-filter. With [selected] it is a filter chip (selectable,
 * checkbox semantics, lifted while selected); without it, an action chip with button semantics.
 */
@Composable
fun OpalineChip(
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean? = null,
    enabled: Boolean = true,
) {
    OpalinePressable(
        "UI010",
        "chip-0",
        "chip-0",
        onClick,
        modifier,
        enabled,
        selected == true,
        checked = selected,
        role = if (selected != null) Role.Checkbox else Role.Button,
        content = label,
    )
}

/**
 * UI003 Circular action: body A22/gel, icon in the `content` frame; activate → commit. [recipe]
 * may name another single-body action with a `content` frame (UI001, UI002). A22's root is the
 * "puck" motion.js turns as a dial, so the part keeps its resting value .5.
 */
@Composable
fun OpalineIconButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean = false,
    recipe: String = "UI003",
) {
    OpalinePressable(
        recipe,
        "body",
        "content",
        onClick,
        modifier,
        enabled,
        selected,
        shape = CircleShape,
        flexible = false,
    ) {
        Icon(icon, description, Modifier.fillMaxSize())
    }
}

/**
 * UI057 Selectable list row: row A05/gel, content in the `item` frame, lifted while [selected];
 * activate → select. [events] joins the row to its list's recipe instance.
 */
@Composable
fun OpalineRow(
    title: String,
    subtitle: String = "",
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    leading: @Composable () -> Unit = {},
    trailing: @Composable () -> Unit = {},
    selected: Boolean = false,
    events: OpalinePartEvents? = null,
) {
    OpalinePressable(
        "UI057",
        "row-0",
        "item-0",
        onClick,
        modifier.fillMaxWidth(),
        selected = selected,
        alignment = Alignment.CenterStart,
        events = events,
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            leading()
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    color = OpalineColors.text,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle.isNotBlank()) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = OpalineColors.muted,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            trailing()
        }
    }
}

/**
 * UI019 Horizontal slider: body B01/gel, drag → bounded-axis x over [range]. The Material slider
 * keeps direct manipulation and semantics; it is the visible fallback until the scene is ready.
 */
@Composable
fun OpalineSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    range: ClosedFloatingPointRange<Float> = 0f..1f,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onValueChangeFinished: (() -> Unit)? = null,
) {
    val safe = if (value.isFinite()) value.coerceIn(range) else range.start
    val length = range.endInclusive - range.start
    Slider(
        value = safe,
        onValueChange = onValueChange,
        modifier =
            modifier.opalinePart(
                "UI019",
                value = if (length > 0) (safe - range.start) / length else 0f,
                enabled = enabled,
            ),
        enabled = enabled,
        valueRange = range,
        onValueChangeFinished = onValueChangeFinished,
        colors = opalineSliderColors(),
    )
}

/** Slider colours that leave the drawing to the gel part once the scene is ready. */
@Composable
internal fun opalineSliderColors(): SliderColors {
    val ready = opalineReady()
    return SliderDefaults.colors(
        thumbColor = if (ready) Color.Transparent else OpalineColors.pearl,
        activeTrackColor = if (ready) Color.Transparent else OpalineColors.accent,
        inactiveTrackColor = if (ready) Color.Transparent else OpalineColors.surface,
    )
}

/** UI008 Capsule switch: body B09/gel at the binding's stops (off first, on last). */
@Composable
fun OpalineToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val stops = rememberOpalineComposition("UI008").stops()
    val ready = opalineReady()
    OpalineRecipeLayout(
        "UI008",
        modifier.toggleable(
            value = checked,
            enabled = enabled,
            role = Role.Switch,
            onValueChange = onCheckedChange,
        ),
        touch = "body",
    ) {
        Box(
            Modifier.recipePart("body").opalinePart(
                "UI008",
                value = if (checked) stops.last() else stops.first(),
                selected = checked,
                enabled = enabled,
            ),
            contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            if (!ready) {
                Box(
                    Modifier.fillMaxHeight().aspectRatio(1f).background(
                        if (checked) OpalineColors.accent else OpalineColors.muted,
                        CircleShape,
                    ),
                )
            }
        }
    }
}

/** UI071 Immersive empty-state stage: J03/stone, halo D08/shell, seed A08/gel; show → settle. */
@Composable
fun OpalineEmptyState(
    title: String,
    message: String,
    action: @Composable () -> Unit = {},
) {
    val events = rememberOpalinePartEvents()
    LaunchedEffect(events) { events.raise("show") }
    OpalineRecipeLayout("UI071", Modifier.fillMaxWidth(), fitWidth = true, flex = "content") {
        for (part in listOf("body", "halo", "seed")) {
            Box(Modifier.recipePart(part).opalinePart("UI071", part, events = events))
        }
        Column(
            Modifier.recipeFrame("content"),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge, color = OpalineColors.text)
            Text(message, style = MaterialTheme.typography.bodyMedium, color = OpalineColors.muted)
            action()
        }
    }
}

/**
 * One pressable recipe part with its content frame: the shape of UI001–UI003, UI010 chips, UI035
 * tabs, UI042 actions and UI027/UI028/UI057 rows. Inside a dialog action slot it becomes that
 * slot's part and fills it. A click raises the recipe's `activate` binding.
 */
@Composable
internal fun OpalinePressable(
    composition: String,
    part: String,
    frame: String,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean = false,
    checked: Boolean? = null,
    role: Role = Role.Button,
    shape: Shape = RoundedCornerShape(50),
    flexible: Boolean = true,
    alignment: Alignment = Alignment.Center,
    events: OpalinePartEvents? = null,
    content: @Composable () -> Unit,
) {
    val slot = LocalOpalineActionSlot.current
    val own = rememberOpalinePartEvents()
    val instance = slot?.events ?: events ?: own
    val recipe = slot?.composition ?: composition
    val body = slot?.part ?: part
    val area = slot?.frame ?: frame
    val view = LocalView.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val press: () -> Unit = {
        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        instance.raise("activate")
        onClick?.invoke()
    }
    val action =
        when {
            onClick == null -> Modifier
            checked != null -> Modifier.selectable(checked, interaction, null, enabled, role, press)
            else -> Modifier.clickable(interaction, null, enabled, role = role, onClick = press)
        }
    OpalineRecipeLayout(
        recipe,
        modifier
            .then(if (slot != null) Modifier.fillMaxSize() else Modifier)
            .then(if (focused) Modifier.border(2.dp, OpalineColors.pearl, shape) else Modifier)
            .then(action),
        touch = body,
        flex = area.takeIf { flexible },
        flexAlignment = alignment,
    ) {
        Box(
            Modifier.recipePart(body).opalinePart(
                recipe,
                body,
                selected = selected,
                enabled = enabled,
                events = instance,
            ),
        )
        Box(Modifier.recipeFrame(area).alpha(if (enabled) 1f else 0.45f)) {
            CompositionLocalProvider(
                LocalOpalineActionSlot provides null,
                LocalContentColor provides OpalineColors.pearl,
                LocalTextStyle provides MaterialTheme.typography.labelLarge,
                content = content,
            )
        }
    }
}

/** A child of [OpalineRecipeLayout]: recipe part or content frame [id], [index] pitches along. */
private class OpalineSlot(
    val id: String,
    val frame: Boolean,
    val index: Int,
)

/** Places this child on recipe part [id]; [index] repeats it at the recipe's sibling pitch. */
internal fun Modifier.recipePart(
    id: String,
    index: Int = 0,
): Modifier = layoutId(OpalineSlot(id, false, index))

/** Places this child in content frame [id]; [index] repeats it at the recipe's sibling pitch. */
internal fun Modifier.recipeFrame(
    id: String,
    index: Int = 0,
): Modifier = layoutId(OpalineSlot(id, true, index))

/** [prefix][index] when the recipe has that part, else its first sibling (plan Part B). */
internal fun OpalineComposition.sibling(
    prefix: String,
    index: Int,
): String = "$prefix$index".takeIf { id -> parts.any { it.id == id } } ?: "${prefix}0"

/** The parameters of the first behaviour binding with [action]. */
internal fun OpalineComposition.binding(action: String): Map<String, Any?> =
    requireNotNull(behaviorBindings.find { it.action == action }) {
        "Opaline composition $id has no \"$action\" binding"
    }.parameters

/** The `stops` of the first behaviour binding that declares them. */
internal fun OpalineComposition.stops(): List<Float> =
    behaviorBindings
        .firstNotNullOf { it.parameters["stops"] as? List<*> }
        .map { (it as Number).toFloat() }

/** Px per metre that brings [part]'s shorter side to the 48 dp touch floor (plan Part B). */
internal fun Density.touchScale(
    recipe: OpalineComposition,
    part: String,
): Float = recipe.partBox(part).let { 48.dp.toPx() / min(it.width, it.height) }

/**
 * Part [id]'s layout extents in recipe metres: its dimensions box turned by its Euler XYZ
 * rotation (three.js Matrix4.makeRotationFromEuler, rows x and y), axis-aligned, y flipped to
 * screen order; [index] pitches along.
 */
internal fun OpalineComposition.partBox(
    id: String,
    index: Int = 0,
): Rect {
    val spec = part(id)
    val (rx, ry, rz) = spec.rotation
    val (dx, dy, dz) = spec.dimensions
    val width = abs(cos(ry) * cos(rz)) * dx + abs(cos(ry) * sin(rz)) * dy + abs(sin(ry)) * dz
    val height =
        abs(cos(rx) * sin(rz) + sin(rx) * sin(ry) * cos(rz)) * dx +
            abs(cos(rx) * cos(rz) - sin(rx) * sin(ry) * sin(rz)) * dy +
            abs(sin(rx) * cos(ry)) * dz
    return box(spec.position, width, height, partPitch(id), index)
}

/** Part [id]'s unrotated x/y dimensions box: the node the renderer fits the element to. */
private fun OpalineComposition.nodeBox(
    id: String,
    index: Int,
): Rect {
    val spec = part(id)
    return box(spec.position, spec.dimensions.x, spec.dimensions.y, partPitch(id), index)
}

private fun OpalineComposition.partPitch(id: String): Offset = pitch(id) { key -> parts.find { it.id == key }?.position }

/** Content frame [id]'s `size` box in recipe metres, y flipped to screen order. */
internal fun OpalineComposition.frameBox(
    id: String,
    index: Int = 0,
): Rect {
    val spec = frame(id)
    val step = pitch(id) { key -> contentFrames.find { it.id == key }?.position }
    return box(spec.position, spec.width, spec.height, step, index)
}

/**
 * Padding from [container] to the recipe's first [item] and the gap to the item's sibling, at the
 * touch scale of [item]; items beyond the recipe's count repeat at this pitch.
 */
@Composable
internal fun rememberRecipeRail(
    composition: String,
    container: String,
    item: String,
): Pair<PaddingValues, Dp> {
    val recipe = rememberOpalineComposition(composition)
    val density = LocalDensity.current
    return remember(recipe, density) {
        with(density) {
            val scale = touchScale(recipe, item)
            val outer = recipe.partBox(container)
            val first = recipe.partBox(item)
            val second = recipe.partBox(item, 1)
            val gap = max(second.left - first.right, second.top - first.bottom)
            PaddingValues(
                horizontal = ((first.left - outer.left) * scale).toDp(),
                vertical = ((first.top - outer.top) * scale).toDp(),
            ) to (gap * scale).toDp()
        }
    }
}

private fun box(
    centre: OpalineVec3,
    width: Float,
    height: Float,
    step: Offset,
    index: Int,
): Rect {
    val x = centre.x + step.x * index
    val y = -centre.y + step.y * index
    return Rect(x - width / 2f, y - height / 2f, x + width / 2f, y + height / 2f)
}

private val SERIAL = Regex("(.*\\D)(\\d+)")

/** Screen-ordered step from [id] to its numbered sibling (`row-0` → `row-1`), else zero. */
private fun pitch(
    id: String,
    position: (String) -> OpalineVec3?,
): Offset {
    val match = SERIAL.matchEntire(id) ?: return Offset.Zero
    val (prefix, digits) = match.destructured
    val here = position(id) ?: return Offset.Zero
    val next = position("$prefix${digits.toInt() + 1}")
    val previous = position("$prefix${digits.toInt() - 1}")
    return when {
        next != null -> Offset(next.x - here.x, here.y - next.y)
        previous != null -> Offset(here.x - previous.x, previous.y - here.y)
        else -> Offset.Zero
    }
}

/**
 * Lays out children tagged by [recipePart] / [recipeFrame] at their recipe extents. The box is the
 * union of the tagged parts; [touch] names the part whose shorter side meets the 48 dp floor and
 * [fitWidth] fills the incoming width. The content in frame [flex] sets that frame's extents:
 * parts containing it stretch, the others keep their size and follow. Without [flex] the recipe
 * stretches to the incoming constraints, as the renderer fits each part to its bounds.
 */
@Composable
internal fun OpalineRecipeLayout(
    composition: String,
    modifier: Modifier = Modifier,
    touch: String? = null,
    fitWidth: Boolean = false,
    flex: String? = null,
    flexAlignment: Alignment = Alignment.Center,
    content: @Composable () -> Unit,
) {
    val recipe = rememberOpalineComposition(composition)
    Layout(content, modifier) { measurables, constraints ->
        measureRecipe(recipe, measurables, constraints, touch, fitWidth, flex, flexAlignment)
    }
}

private fun MeasureScope.measureRecipe(
    recipe: OpalineComposition,
    measurables: List<Measurable>,
    constraints: Constraints,
    touch: String?,
    fitWidth: Boolean,
    flex: String?,
    flexAlignment: Alignment,
): MeasureResult {
    val slots = measurables.map { requireNotNull(it.layoutId as? OpalineSlot) { "Untagged child" } }
    val boxes =
        slots.map {
            if (it.frame) recipe.frameBox(it.id, it.index) else recipe.partBox(it.id, it.index)
        }
    val parts = boxes.filterIndexed { i, _ -> !slots[i].frame }
    val union =
        Rect(
            parts.minOf { it.left },
            parts.minOf { it.top },
            parts.maxOf { it.right },
            parts.maxOf { it.bottom },
        )
    val floor = 48.dp.toPx()
    val base = touch?.let { touchScale(recipe, it) } ?: (floor / min(union.width, union.height))
    val sx =
        if (fitWidth && constraints.hasBoundedWidth) constraints.maxWidth / union.width else base
    val sy = if (touch == null) sx else max(sx, base)
    val flexAt = slots.indexOfFirst { it.frame && it.id == flex }
    val flexBox = if (flexAt < 0) Rect.Zero else uncovered(recipe, flexAt, slots, boxes)
    val fixedW = sx * (union.width - flexBox.width).coerceAtLeast(0f)
    val fixedH = sy * (union.height - flexBox.height).coerceAtLeast(0f)
    val flexed =
        measurables.getOrNull(flexAt)?.measure(
            Constraints(
                maxWidth = room(constraints.maxWidth, fixedW),
                maxHeight = room(constraints.maxHeight, fixedH),
            ),
        )
    var targetW = flexed?.width?.toFloat() ?: 0f
    var targetH = flexed?.height?.toFloat() ?: 0f
    val held = touch?.let { recipe.partBox(it) }
    if (flexed != null && held != null) {
        if (held.left <= flexBox.left && held.right >= flexBox.right) {
            targetW = max(targetW, floor - sx * (held.width - flexBox.width))
        }
        if (held.top <= flexBox.top && held.bottom >= flexBox.bottom) {
            targetH = max(targetH, floor - sy * (held.height - flexBox.height))
        }
    }
    val width = constraints.constrainWidth((fixedW + targetW).roundToInt())
    val height = constraints.constrainHeight((fixedH + targetH).roundToInt())
    val x = RecipeAxis(union.left, sx, flexBox.left, flexBox.right, fixedW, width)
    val y = RecipeAxis(union.top, sy, flexBox.top, flexBox.bottom, fixedH, height)
    val placed =
        measurables.mapIndexed { i, measurable ->
            val slot = slots[i]
            val box =
                when {
                    i == flexAt -> flexBox
                    slot.frame -> boxes[i]
                    else -> recipe.nodeBox(slot.id, slot.index)
                }
            val left = x.start(box.left, box.right)
            val top = y.start(box.top, box.bottom)
            val w = x.size(box.left, box.right).roundToInt().coerceAtLeast(0)
            val h = y.size(box.top, box.bottom).roundToInt().coerceAtLeast(0)
            val placeable =
                when {
                    i == flexAt && flexed != null -> flexed
                    slot.frame -> measurable.measure(Constraints(maxWidth = w, maxHeight = h))
                    else -> measurable.measure(Constraints.fixed(w, h))
                }
            val align = if (i == flexAt) flexAlignment else Alignment.Center
            val inset =
                align.align(
                    IntSize(placeable.width, placeable.height),
                    IntSize(w, h),
                    LayoutDirection.Ltr,
                )
            placeable to inset + IntOffset(left.roundToInt(), top.roundToInt())
        }
    return layout(width, height) {
        placed.forEach { (placeable, at) -> placeable.placeRelative(at) }
    }
}

private fun room(
    max: Int,
    fixed: Float,
): Int =
    if (max == Constraints.Infinity) {
        Constraints.Infinity
    } else {
        (max - fixed).roundToInt().coerceAtLeast(0)
    }

/** The flex frame's box without the rows covered by present non-owner parts in front of it. */
private fun uncovered(
    recipe: OpalineComposition,
    flexAt: Int,
    slots: List<OpalineSlot>,
    boxes: List<Rect>,
): Rect {
    val frame = recipe.frame(slots[flexAt].id)
    val box = boxes[flexAt]
    var top = box.top
    var bottom = box.bottom
    slots.forEachIndexed { i, slot ->
        val part = recipe.parts.find { it.id == slot.id && it.id != frame.owner && !slot.frame }
        val front = part != null && part.position.z + part.dimensions.z / 2f > frame.position.z
        if (front && boxes[i].overlaps(box)) {
            if (boxes[i].center.y > box.center.y) {
                bottom = min(bottom, boxes[i].top)
            } else {
                top = max(top, boxes[i].bottom)
            }
        }
    }
    return Rect(box.left, top, box.right, max(top, bottom))
}

/**
 * One screen axis: recipe metres from [origin] at [scale] px per metre. The flex interval
 * [flexStart]..[flexEnd] takes whatever the [total] size leaves after the [fixed] rest; without
 * one, the whole axis scales from its natural [fixed] size to [total].
 */
private class RecipeAxis(
    private val origin: Float,
    scale: Float,
    private val flexStart: Float,
    private val flexEnd: Float,
    fixed: Float,
    total: Int,
) {
    private val flexible = flexEnd > flexStart
    private val scale = if (flexible || fixed <= 0f) scale else scale * total / fixed
    private val extra = if (flexible) total - fixed - scale * (flexEnd - flexStart) else 0f

    private fun map(v: Float): Float {
        val stretch =
            when {
                !flexible || v <= flexStart -> 0f
                v >= flexEnd -> extra
                else -> extra * (v - flexStart) / (flexEnd - flexStart)
            }
        return scale * (v - origin) + stretch
    }

    private fun spans(
        a: Float,
        b: Float,
    ) = flexible && a <= flexStart && b >= flexEnd

    fun start(
        a: Float,
        b: Float,
    ): Float = if (spans(a, b)) map(a) else map((a + b) / 2f) - scale * (b - a) / 2f

    fun size(
        a: Float,
        b: Float,
    ): Float = if (spans(a, b)) map(b) - map(a) else scale * (b - a)
}
