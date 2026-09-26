package dev.geode.ui.opaline.kit

import android.view.View
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.awaitVerticalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.IntSize
import dev.geode.ui.opaline.OpalineRecipeLayout
import dev.geode.ui.opaline.binding
import dev.geode.ui.opaline.opalinePart
import dev.geode.ui.opaline.recipeFrame
import dev.geode.ui.opaline.recipePart
import dev.geode.ui.opaline.rememberOpalineComposition
import dev.geode.ui.opaline.stops
import kotlin.math.abs
import kotlin.math.hypot

/** src/motion.js MotionController.drag gains per host-view fraction: slider, rotary, xy. */
private const val SLIDER_GAIN = 3.6f
private const val ROTARY_GAIN = 2.4f
private const val XY_GAIN = 3f

/** elements.json B15 `modelAsset.bounds`: x and y span ±1.19 m around the dial axis. */
private const val B15_EXTENT = 2.38f

/** src/geometry.js case 15: inner-dial `sphere(.49, .49, .3)`, outer-dial `ring(.86, .86, .17)`. */
private const val INNER_DIAL_RADIUS = .49f
private const val OUTER_DIAL_RADIUS = .86f
private const val OUTER_DIAL_TUBE = .17f

/** UI020 Vertical fader: body B03/gel; drag → bounded-axis y over the recipe range. */
@Composable
fun OpalineVerticalFader(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
) = AxisControl("UI020", value, onValueChange, modifier, enabled, valueRange)

/** UI022 Stepped slider: body B05/gel; drag → bounded-axis x snapped to the recipe's stops. */
@Composable
fun OpalineSteppedSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
) = AxisControl("UI022", value, onValueChange, modifier, enabled, valueRange)

/**
 * UI024 Concentric dual dial: body B15/gel; drag → dual-angular-value, the inner dial driving
 * [value] and the outer dial [secondaryValue]; [content] sits in the `value` frame.
 */
@Composable
fun OpalineConcentricDualDial(
    value: Float,
    secondaryValue: Float,
    onValueChange: (Float) -> Unit,
    onSecondaryValueChange: (Float) -> Unit,
    innerLabel: String,
    outerLabel: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable BoxScope.() -> Unit = {},
) {
    val composition = rememberOpalineComposition("UI024")
    val range = composition.binding("dual-angular-value")["range"].range()
    val body = composition.part("body").dimensions
    val inner by rememberUpdatedState(value)
    val outer by rememberUpdatedState(secondaryValue)
    val changeInner by rememberUpdatedState(onValueChange)
    val changeOuter by rememberUpdatedState(onSecondaryValueChange)
    val view = LocalView.current
    OpalineRecipeLayout(
        "UI024",
        modifier
            .aspectRatio(body.x / body.y)
            .pointerInput(enabled) {
                if (enabled) {
                    var innerDial = false
                    var raw = 0f
                    motionDrag(
                        view = view,
                        orientation = null,
                        onDown = {
                            innerDial = innerDialHit(it, size)
                            raw = if (innerDial) inner else outer
                        },
                    ) { dx, dy ->
                        // motion.js rotary: `setValue(value.target + (delta.x - delta.y) * 2.4)`.
                        raw = (raw + (dx - dy) * ROTARY_GAIN).coerceIn(range)
                        if (innerDial) changeInner(raw) else changeOuter(raw)
                    }
                }
            },
    ) {
        Box(
            Modifier
                .recipePart("body")
                .opalinePart(
                    "UI024",
                    value = value,
                    enabled = enabled,
                    secondaryValue = secondaryValue,
                ).valueSemantics(outerLabel, secondaryValue, range, enabled) {
                    onSecondaryValueChange(it)
                },
        ) {
            Box(
                Modifier
                    .align(Alignment.Center)
                    .fillMaxSize(2 * INNER_DIAL_RADIUS / B15_EXTENT)
                    .valueSemantics(innerLabel, value, range, enabled, onValueChange),
            )
        }
        Box(Modifier.recipeFrame("value"), contentAlignment = Alignment.Center, content = content)
    }
}

/** UI025 XY control: body B21/gel; drag → bounded-plane, x → [value], y → [secondaryValue]. */
@Composable
fun OpalineXYControl(
    value: Float,
    secondaryValue: Float,
    onValueChange: (Float, Float) -> Unit,
    xLabel: String,
    yLabel: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val composition = rememberOpalineComposition("UI025")
    val axes = composition.binding("bounded-plane")["range"] as? List<*>
    val ranges = axes.orEmpty().map { it.range() }
    val body = composition.part("body").dimensions
    val x by rememberUpdatedState(value)
    val y by rememberUpdatedState(secondaryValue)
    val change by rememberUpdatedState(onValueChange)
    val view = LocalView.current
    Box(
        modifier
            .aspectRatio(body.x / body.y)
            .opalinePart("UI025", value = value, enabled = enabled, secondaryValue = secondaryValue)
            .pointerInput(enabled) {
                if (enabled) {
                    var rawX = 0f
                    var rawY = 0f
                    motionDrag(
                        view = view,
                        orientation = null,
                        onDown = {
                            rawX = x
                            rawY = y
                        },
                    ) { dx, dy ->
                        // motion.js xy: `value + delta.x * 3`, `value2 - delta.y * 3`.
                        rawX = (rawX + dx * XY_GAIN).coerceIn(ranges[0])
                        rawY = (rawY - dy * XY_GAIN).coerceIn(ranges[1])
                        change(rawX, rawY)
                    }
                }
            },
    ) {
        Box(
            Modifier
                .matchParentSize()
                .valueSemantics(xLabel, value, ranges[0], enabled) { change(it, secondaryValue) },
        )
        Box(
            Modifier
                .matchParentSize()
                .valueSemantics(yLabel, secondaryValue, ranges[1], enabled) { change(value, it) },
        )
    }
}

/** UI026 Pressure surface: body B20/gel; pressurechange → bounded-pressure as [value]. */
@Composable
fun OpalinePressureSurface(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val composition = rememberOpalineComposition("UI026")
    val range = composition.binding("bounded-pressure")["range"].range()
    val body = composition.part("body").dimensions
    val change by rememberUpdatedState(onValueChange)
    Box(
        modifier
            .aspectRatio(body.x / body.y)
            .opalinePart("UI026", value = value, enabled = enabled)
            .valueSemantics(null, value, range, enabled, onValueChange)
            .pointerInput(enabled) {
                if (enabled) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        var contact: PointerInputChange? = down
                        while (contact != null && contact.pressed && !contact.isConsumed) {
                            change(contact.pressure.coerceIn(range))
                            contact.consume()
                            contact = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                        }
                        change(range.start)
                    }
                }
            },
    )
}

/**
 * UI020/UI022 bounded-axis: the binding's axis, range and stops (the catalogue's stops win over
 * motion.js's B05 sixths); [valueRange] maps linearly onto the recipe range.
 */
@Composable
private fun AxisControl(
    recipe: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    valueRange: ClosedFloatingPointRange<Float>,
) {
    val composition = rememberOpalineComposition(recipe)
    val axis = composition.binding("bounded-axis")
    val vertical = axis["axis"] == "y"
    val range = axis["range"].range()
    val stops = if ("stops" in axis) composition.stops() else emptyList()
    val body = composition.part("body").dimensions
    val coordinate = remap(value, valueRange, range)
    val latest by rememberUpdatedState(coordinate)
    val change by rememberUpdatedState(onValueChange)
    val view = LocalView.current
    val snap = { raw: Float ->
        remap(stops.minByOrNull { abs(it - raw) } ?: raw, range, valueRange)
    }
    Box(
        modifier
            .aspectRatio(body.x / body.y, matchHeightConstraintsFirst = vertical)
            .opalinePart(recipe, value = remap(coordinate, range, 0f..1f), enabled = enabled)
            .semantics {
                val current = value.coerceIn(valueRange)
                val steps = (stops.size - 2).coerceAtLeast(0)
                progressBarRangeInfo = ProgressBarRangeInfo(current, valueRange, steps)
                if (!enabled) disabled()
                setProgress {
                    if (enabled) change(snap(remap(it, valueRange, range)))
                    enabled
                }
            }.pointerInput(enabled, valueRange) {
                if (enabled) {
                    var raw = 0f
                    val orientation = if (vertical) Orientation.Vertical else Orientation.Horizontal
                    motionDrag(view, orientation, { raw = latest }) { dx, dy ->
                        // motion.js slider: `value + (id === 'B03' ? -delta.y : delta.x) * 3.6`.
                        raw = (raw + (if (vertical) -dy else dx) * SLIDER_GAIN).coerceIn(range)
                        change(snap(raw))
                    }
                }
            },
    )
}

/**
 * One contact: [onDown] at first touch, then, past touch slop along [orientation] (any direction
 * when null), each move as a fraction of the host view, as workbench.js pointermove hands
 * MotionController.drag `delta = {x: dx / rect.width, y: dy / rect.height}`.
 */
private suspend fun PointerInputScope.motionDrag(
    view: View,
    orientation: Orientation?,
    onDown: (Offset) -> Unit,
    onDrag: (Float, Float) -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown()
        onDown(down.position)
        val start =
            when (orientation) {
                Orientation.Vertical ->
                    awaitVerticalTouchSlopOrCancellation(down.id) { over, _ -> over.consume() }

                Orientation.Horizontal ->
                    awaitHorizontalTouchSlopOrCancellation(down.id) { over, _ -> over.consume() }

                null -> awaitTouchSlopOrCancellation(down.id) { over, _ -> over.consume() }
            }
        var last = down.position
        val move = { change: PointerInputChange ->
            onDrag(
                (change.position.x - last.x) / view.width.coerceAtLeast(1),
                (change.position.y - last.y) / view.height.coerceAtLeast(1),
            )
            last = change.position
            change.consume()
        }
        if (start != null) {
            move(start)
            drag(start.id, move)
        }
    }
}

/** True when [point] lies nearer the B15 inner dial than the outer dial ring (authored metres). */
private fun innerDialHit(
    point: Offset,
    size: IntSize,
): Boolean {
    val x = (point.x / size.width.coerceAtLeast(1) - .5f) * B15_EXTENT
    val y = (.5f - point.y / size.height.coerceAtLeast(1)) * B15_EXTENT
    val radius = hypot(x, y)
    val toInner = (radius - INNER_DIAL_RADIUS).coerceAtLeast(0f)
    val toOuter = (abs(radius - OUTER_DIAL_RADIUS) - OUTER_DIAL_TUBE).coerceAtLeast(0f)
    return toInner <= toOuter
}

private fun Modifier.valueSemantics(
    label: String?,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    enabled: Boolean,
    onValueChange: (Float) -> Unit,
) =
    semantics {
        if (label != null) contentDescription = label
        progressBarRangeInfo = ProgressBarRangeInfo(value.coerceIn(range), range)
        if (!enabled) disabled()
        setProgress {
            if (enabled) onValueChange(it.coerceIn(range))
            enabled
        }
    }

/** A behaviour binding's `range` [low, high]. */
private fun Any?.range(): ClosedFloatingPointRange<Float> {
    val bounds = this as List<*>
    return (bounds[0] as Number).toFloat()..(bounds[1] as Number).toFloat()
}

/** [value] of [from] carried linearly onto [to]. */
private fun remap(
    value: Float,
    from: ClosedFloatingPointRange<Float>,
    to: ClosedFloatingPointRange<Float>,
): Float {
    val span = from.endInclusive - from.start
    val t = if (span > 0f) ((value - from.start) / span).coerceIn(0f, 1f) else 0f
    return to.start + t * (to.endInclusive - to.start)
}
