package dev.geode.ui.glass

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * A fluid liquid-glass slider matching ref-02 and video-v3/v4:
 * - Frosted pill guide tube with soft ambient shadow
 * - Luminous pastel fill gradient (mint -> peach -> lavender)
 * - Liquid pearl / droplet thumb that elastically stretches along drag axis and rebounds on release
 * - Tactile wave coupling into [LocalWaterField]
 */
@Composable
fun GlassDropletSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    enabled: Boolean = true,
    orientation: Orientation = Orientation.Horizontal,
    thumbSize: Dp = 26.dp,
    trackThickness: Dp = 12.dp,
) {
    val span = valueRange.endInclusive - valueRange.start
    val fraction = if (span > 0f) ((value - valueRange.start) / span).coerceIn(0f, 1f) else 0f
    val currentOnChange = rememberUpdatedState(onValueChange)
    val currentRange = rememberUpdatedState(valueRange)
    val stretchScale = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    val waterField = LocalWaterField.current

    if (orientation == Orientation.Horizontal) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .height(thumbSize + 8.dp)
                .floatOnWater(strength = 0.35f),
            contentAlignment = Alignment.CenterStart,
        ) {
            var widthPx = 1f
            // 1. Frosted Glass Track
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(trackThickness)
                    .glassSurface(shape = GlassShapes.pill)
                    .onSizeChanged { widthPx = it.width.toFloat().coerceAtLeast(1f) },
            ) {
                Canvas(Modifier.matchParentSize()) {
                    val y = size.height / 2f
                    val filledWidth = size.width * fraction
                    if (filledWidth > 1f) {
                        drawLine(
                            brush = Brush.horizontalGradient(
                                colors = listOf(
                                    GlassPalette.mint,
                                    GlassPalette.butter,
                                    GlassPalette.lilac,
                                ),
                                endX = filledWidth,
                            ),
                            start = Offset(0f, y),
                            end = Offset(filledWidth, y),
                            strokeWidth = trackThickness.toPx() * 0.65f,
                            cap = StrokeCap.Round,
                            alpha = if (enabled) 0.80f else 0.35f,
                        )
                    }
                }
            }

            // 2. Elastic Droplet Thumb
            val thumbOffsetFraction = fraction.coerceIn(0f, 1f)
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = thumbSize / 2f),
            ) {
                Box(
                    Modifier
                        .align(Alignment.CenterStart)
                        .graphicsLayer {
                            translationX = (widthPx - thumbSize.toPx()) * thumbOffsetFraction
                            scaleX = stretchScale.value
                            scaleY = 1f / kotlin.math.sqrt(stretchScale.value.toDouble()).toFloat()
                        }
                        .size(thumbSize)
                        .glassSurface(shape = GlassShapes.bubble, tint = GlassPalette.mint)
                        .draggable(
                            orientation = Orientation.Horizontal,
                            enabled = enabled,
                            state = rememberDraggableState { deltaPx ->
                                val deltaFraction = deltaPx / widthPx
                                val newFraction = (fraction + deltaFraction).coerceIn(0f, 1f)
                                val r = currentRange.value
                                currentOnChange.value(r.start + newFraction * (r.endInclusive - r.start))
                                waterField?.tap(widthPx * newFraction, 0f, 0.4f)
                            },
                            onDragStarted = {
                                scope.launch { stretchScale.animateTo(GlassMotion.DROPLET_ELONGATION_MAX, spring()) }
                            },
                            onDragStopped = {
                                scope.launch {
                                    stretchScale.animateTo(
                                        1f,
                                        spring(
                                            dampingRatio = GlassMotion.REBOUND_DAMPING,
                                            stiffness = GlassMotion.SPRING_STIFFNESS,
                                        ),
                                    )
                                }
                            },
                        ),
                )
            }
        }
    } else {
        // Vertical Slider (as seen in video-v3)
        Box(
            modifier = modifier
                .width(thumbSize + 8.dp)
                .fillMaxHeight()
                .floatOnWater(strength = 0.35f),
            contentAlignment = Alignment.BottomCenter,
        ) {
            var heightPx = 1f
            Box(
                Modifier
                    .fillMaxHeight()
                    .width(trackThickness)
                    .glassSurface(shape = GlassShapes.pill)
                    .onSizeChanged { heightPx = it.height.toFloat().coerceAtLeast(1f) },
            ) {
                Canvas(Modifier.matchParentSize()) {
                    val x = size.width / 2f
                    val filledHeight = size.height * fraction
                    if (filledHeight > 1f) {
                        drawLine(
                            brush = Brush.verticalGradient(
                                colors = listOf(
                                    GlassPalette.lilac,
                                    GlassPalette.butter,
                                    GlassPalette.mint,
                                ),
                                startY = size.height - filledHeight,
                                endY = size.height,
                            ),
                            start = Offset(x, size.height),
                            end = Offset(x, size.height - filledHeight),
                            strokeWidth = trackThickness.toPx() * 0.65f,
                            cap = StrokeCap.Round,
                            alpha = if (enabled) 0.80f else 0.35f,
                        )
                    }
                }
            }

            // Elastic Droplet Thumb
            Box(
                Modifier
                    .fillMaxHeight()
                    .padding(vertical = thumbSize / 2f),
            ) {
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .graphicsLayer {
                            translationY = -(heightPx - thumbSize.toPx()) * fraction
                            scaleY = stretchScale.value
                            scaleX = 1f / kotlin.math.sqrt(stretchScale.value.toDouble()).toFloat()
                        }
                        .size(thumbSize)
                        .glassSurface(shape = GlassShapes.bubble, tint = GlassPalette.mint)
                        .draggable(
                            orientation = Orientation.Vertical,
                            enabled = enabled,
                            state = rememberDraggableState { deltaPx ->
                                val deltaFraction = -deltaPx / heightPx
                                val newFraction = (fraction + deltaFraction).coerceIn(0f, 1f)
                                val r = currentRange.value
                                currentOnChange.value(r.start + newFraction * (r.endInclusive - r.start))
                                waterField?.tap(0f, heightPx * (1f - newFraction), 0.4f)
                            },
                            onDragStarted = {
                                scope.launch { stretchScale.animateTo(GlassMotion.DROPLET_ELONGATION_MAX, spring()) }
                            },
                            onDragStopped = {
                                scope.launch {
                                    stretchScale.animateTo(
                                        1f,
                                        spring(
                                            dampingRatio = GlassMotion.REBOUND_DAMPING,
                                            stiffness = GlassMotion.SPRING_STIFFNESS,
                                        ),
                                    )
                                }
                            },
                        ),
                )
            }
        }
    }
}
