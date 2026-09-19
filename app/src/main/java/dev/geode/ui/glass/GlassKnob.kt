package dev.geode.ui.glass

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

private const val SWEEP_START_DEG = 135f
private const val SWEEP_TOTAL_DEG = 270f
private const val DRAG_DEGREES_PER_PX = 0.40f

/**
 * A rotary matte frosted glass dial matching ref-02 and ref-05:
 * - Outer concentric progress arc and tick track
 * - Inner floating matte opaline dome with soft diffuse specular sheen
 * - Pearl indicator pip rotating along the active arc
 * - Water field tactile coupling
 */
@Composable
fun GlassKnob(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    enabled: Boolean = true,
    knobSize: Dp = 64.dp,
) {
    val span = valueRange.endInclusive - valueRange.start
    val fraction = if (span > 0f) ((value - valueRange.start) / span).coerceIn(0f, 1f) else 0f
    val currentOnChange = rememberUpdatedState(onValueChange)
    val currentRange = rememberUpdatedState(valueRange)

    Box(
        modifier = modifier
            .size(knobSize)
            .floatOnWater(strength = 0.35f)
            .then(
                if (enabled) {
                    Modifier.pointerInput(Unit) {
                        var accumulated = fraction
                        detectDragGestures { _, dragAmount ->
                            val delta = -dragAmount.y * DRAG_DEGREES_PER_PX / SWEEP_TOTAL_DEG
                            accumulated = (accumulated + delta).coerceIn(0f, 1f)
                            val r = currentRange.value
                            currentOnChange.value(r.start + accumulated * (r.endInclusive - r.start))
                        }
                    }
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        // 1. Outer Arc & Ticks Layer
        Canvas(Modifier.size(knobSize)) {
            val strokeWidth = 3.dp.toPx()
            val arcPadding = strokeWidth / 2f + 2.dp.toPx()
            val arcSize = Size(size.width - arcPadding * 2f, size.height - arcPadding * 2f)
            val topLeft = Offset(arcPadding, arcPadding)
            val center = Offset(size.width / 2f, size.height / 2f)
            val radius = arcSize.width / 2f

            // Faint frosted background track
            drawArc(
                color = GlassPalette.glassRim.copy(alpha = 0.22f),
                startAngle = SWEEP_START_DEG,
                sweepAngle = SWEEP_TOTAL_DEG,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
            )

            // Tick marks along the outer perimeter (ref-02)
            val tickCount = 9
            for (i in 0 until tickCount) {
                val tickFraction = i / (tickCount - 1).toFloat()
                val tickAngleRad = Math.toRadians((SWEEP_START_DEG + SWEEP_TOTAL_DEG * tickFraction).toDouble())
                val dotCenter = Offset(
                    center.x + (radius + 4.dp.toPx()) * cos(tickAngleRad).toFloat(),
                    center.y + (radius + 4.dp.toPx()) * sin(tickAngleRad).toFloat(),
                )
                val dotAlpha = if (tickFraction <= fraction) 0.55f else 0.20f
                drawCircle(
                    color = GlassPalette.textPrimary.copy(alpha = dotAlpha),
                    radius = 1.2.dp.toPx(),
                    center = dotCenter,
                )
            }

            // Glowing pastel active progress arc
            if (fraction > 0.01f) {
                drawArc(
                    brush = Brush.sweepGradient(
                        colors = listOf(
                            GlassPalette.mint,
                            GlassPalette.peach,
                            GlassPalette.lavender,
                            GlassPalette.mint,
                        ),
                        center = center,
                    ),
                    startAngle = SWEEP_START_DEG,
                    sweepAngle = SWEEP_TOTAL_DEG * fraction,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
                )
            }
        }

        // 2. Inner Floating Matte Frosted Dome
        val innerSize = knobSize - 16.dp
        Box(
            modifier = Modifier
                .size(innerSize)
                .glassSurface(shape = GlassShapes.bubble),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(innerSize)) {
                val center = Offset(size.width / 2f, size.height / 2f)
                val dialRadius = size.width / 2f

                // Subtle center matte dimple
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.12f),
                            GlassPalette.baseShadow.copy(alpha = 0.10f),
                            Color.Transparent,
                        ),
                        center = center,
                        radius = dialRadius * 0.45f,
                    ),
                    radius = dialRadius * 0.45f,
                    center = center,
                )

                // Rotating pearl indicator pip (ref-02)
                val currentAngleRad = Math.toRadians((SWEEP_START_DEG + SWEEP_TOTAL_DEG * fraction).toDouble())
                val pipDistance = dialRadius - 6.dp.toPx()
                val pipCenter = Offset(
                    center.x + pipDistance * cos(currentAngleRad).toFloat(),
                    center.y + pipDistance * sin(currentAngleRad).toFloat(),
                )

                // Pip contact shadow
                drawCircle(
                    color = GlassPalette.baseShadow.copy(alpha = 0.28f),
                    radius = 3.dp.toPx(),
                    center = pipCenter + Offset(0f, 1.dp.toPx()),
                )
                // Pip pearl body with diffuse highlight
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.90f),
                            GlassPalette.mint.copy(alpha = 0.70f),
                        ),
                        center = pipCenter - Offset(1.dp.toPx(), 1.dp.toPx()),
                        radius = 2.5.dp.toPx(),
                    ),
                    radius = 2.5.dp.toPx(),
                    center = pipCenter,
                )
            }
        }
    }
}
