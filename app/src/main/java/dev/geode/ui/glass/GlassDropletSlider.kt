package dev.geode.ui.glass

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.progressSemantics
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * A fluid liquid-glass slider directly matching the mockup in liquid_player_lib_1789776612307.jpg:
 * - Organic pinched liquid glass track: wider bulbous ends that gently taper and pinch in the middle waist
 * - Frosted glass body with internal diffuse glow and iridescent rim light
 * - Volumetric 3D opalescent pearl thumb that elastically stretches during drag and rebounds with a spring
 * - Tactile wave and caustic disturbance coupled into [LocalWaterField]
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
    trackThickness: Dp = 26.dp,
    pinchedTrack: Boolean = true,
) {
    val span = valueRange.endInclusive - valueRange.start
    val fraction = if (span > 0f) ((value - valueRange.start) / span).coerceIn(0f, 1f) else 0f
    val currentOnChange = rememberUpdatedState(onValueChange)
    val currentRange = rememberUpdatedState(valueRange)
    val stretchScale = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    val waterField = LocalWaterField.current
    val seek: (Float) -> Unit = { target -> currentOnChange.value(target.coerceIn(currentRange.value)) }

    if (orientation == Orientation.Horizontal) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .height(maxOf(thumbSize + 8.dp, trackThickness + 6.dp))
                .dropletSliderControls(value, valueRange, enabled, Orientation.Horizontal, seek)
                .floatOnWater(strength = 0.35f),
            contentAlignment = Alignment.CenterStart,
        ) {
            var widthPx = 1f

            // 1. Organic Pinched Frosted Glass Track
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(trackThickness)
                    .onSizeChanged { widthPx = it.width.toFloat().coerceAtLeast(1f) },
            ) {
                if (pinchedTrack) {
                    drawOrganicPinchedTrack(size.width, size.height, fraction, enabled)
                } else {
                    drawStandardTrack(size.width, size.height, fraction, enabled)
                }
            }

            // 2. 3D Iridescent Spherical Pearl Thumb
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
                        .draggable(
                            orientation = Orientation.Horizontal,
                            enabled = enabled,
                            state = rememberDraggableState { deltaPx ->
                                val deltaFraction = deltaPx / widthPx
                                val newFraction = (fraction + deltaFraction).coerceIn(0f, 1f)
                                val r = currentRange.value
                                currentOnChange.value(r.start + newFraction * (r.endInclusive - r.start))
                                waterField?.tap(widthPx * newFraction, 0f, 0.45f)
                            },
                            onDragStarted = {
                                scope.launch {
                                    stretchScale.animateTo(
                                        GlassMotion.DROPLET_ELONGATION_MAX,
                                        spring(stiffness = GlassMotion.SPRING_STIFFNESS),
                                    )
                                }
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
                    contentAlignment = Alignment.Center,
                ) {
                    // Volumetric 3D Pearl Rendering
                    Canvas(Modifier.matchParentSize()) {
                        val radius = size.minDimension / 2f
                        val center = Offset(radius, radius)

                        // Contact shadow on track
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(GlassPalette.baseShadow.copy(alpha = 0.40f), Color.Transparent),
                                center = center + Offset(0f, radius * 0.35f),
                                radius = radius * 1.15f,
                            ),
                            radius = radius * 1.15f,
                            center = center + Offset(0f, radius * 0.35f),
                        )

                        // Opaline iridescent pearl core
                        val lightCenter = center - Offset(radius * 0.28f, radius * 0.28f)
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    Color.White.copy(alpha = 0.95f),
                                    Color.White.copy(alpha = 0.70f),
                                    GlassPalette.mint.copy(alpha = 0.45f),
                                    GlassPalette.lavender.copy(alpha = 0.35f),
                                    GlassPalette.baseShadow.copy(alpha = 0.20f),
                                ),
                                center = lightCenter,
                                radius = radius * 1.05f,
                            ),
                            radius = radius,
                            center = center,
                        )

                        // Iridescent rim
                        drawCircle(
                            brush = Brush.linearGradient(
                                colors = listOf(
                                    Color.White.copy(alpha = 0.85f),
                                    GlassPalette.cyan.copy(alpha = 0.60f),
                                    GlassPalette.mint.copy(alpha = 0.65f),
                                    GlassPalette.lavender.copy(alpha = 0.60f),
                                    Color.White.copy(alpha = 0.70f),
                                ),
                                start = Offset(0f, 0f),
                                end = Offset(size.width, size.height),
                            ),
                            radius = radius - 0.5f,
                            center = center,
                            style = Stroke(width = 1.2.dp.toPx()),
                        )

                        // Specular glint
                        val glintCenter = center - Offset(radius * 0.35f, radius * 0.35f)
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(Color.White.copy(alpha = 0.88f), Color.Transparent),
                                center = glintCenter,
                                radius = radius * 0.42f,
                            ),
                            radius = radius * 0.42f,
                            center = glintCenter,
                        )
                    }
                }
            }
        }
    } else {
        // Vertical Slider
        Box(
            modifier = modifier
                .width(thumbSize + 8.dp)
                .fillMaxHeight()
                .dropletSliderControls(value, valueRange, enabled, Orientation.Vertical, seek)
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
                                    GlassPalette.coral,
                                    GlassPalette.gold,
                                    GlassPalette.mint,
                                    GlassPalette.cyan,
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

            // Vertical Elastic Droplet Thumb
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

/**
 * Slider semantics for the whole control, plus tap-to-seek on the track.
 *
 * The only gesture handler used to be `draggable` on the [GlassDropletSlider] thumb, a 26dp dot.
 * That failed two groups of people at once: sighted users had to hit the dot precisely, because
 * tapping the track - which is what most people try first - did nothing at all, and screen-reader
 * users could not seek by any means, since there was no [Role.Slider], no reported position and no
 * `setProgress` action to dispatch. Its sibling `GlassSlider` delegates to the Material slider and
 * so was never affected.
 */
private fun Modifier.dropletSliderControls(
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    enabled: Boolean,
    orientation: Orientation,
    onSeek: (Float) -> Unit,
): Modifier =
    this
        .progressSemantics(value, valueRange)
        .semantics {
            role = Role.Slider
            if (enabled) {
                setProgress { target ->
                    onSeek(target)
                    true
                }
            }
        }.pointerInput(orientation, enabled) {
            if (!enabled) return@pointerInput
            detectTapGestures { offset ->
                val horizontal = orientation == Orientation.Horizontal
                val extent = if (horizontal) size.width else size.height
                // A zero extent would make this NaN, and a NaN passes straight through coerceIn.
                if (extent <= 0) return@detectTapGestures
                val along = if (horizontal) offset.x / extent else 1f - offset.y / extent
                val span = valueRange.endInclusive - valueRange.start
                onSeek(valueRange.start + along.coerceIn(0f, 1f) * span)
            }
        }

/** Draws the organic waist-pinched glass tube matching the mockup in liquid_player_lib_1789776612307.jpg. */
private fun DrawScope.drawOrganicPinchedTrack(
    w: Float,
    h: Float,
    fraction: Float,
    enabled: Boolean,
) {
    val rEnd = h / 2f
    val rWaist = h * 0.28f
    val midX = w / 2f
    val midY = h / 2f

    val path = Path().apply {
        // Start at top of left bulb
        moveTo(rEnd, midY - rEnd)
        // Curve inward to top of waist
        cubicTo(
            w * 0.25f, midY - rEnd,
            w * 0.38f, midY - rWaist,
            midX, midY - rWaist,
        )
        // Curve outward to top of right bulb
        cubicTo(
            w * 0.62f, midY - rWaist,
            w * 0.75f, midY - rEnd,
            w - rEnd, midY - rEnd,
        )
        // Right rounded cap arc
        arcTo(
            rect = androidx.compose.ui.geometry.Rect(w - h, 0f, w, h),
            startAngleDegrees = -90f,
            sweepAngleDegrees = 180f,
            forceMoveTo = false,
        )
        // Bottom curve inward to bottom of waist
        cubicTo(
            w * 0.75f, midY + rEnd,
            w * 0.62f, midY + rWaist,
            midX, midY + rWaist,
        )
        // Bottom curve outward to bottom of left bulb
        cubicTo(
            w * 0.38f, midY + rWaist,
            w * 0.25f, midY + rEnd,
            rEnd, midY + rEnd,
        )
        // Left rounded cap arc
        arcTo(
            rect = androidx.compose.ui.geometry.Rect(0f, 0f, h, h),
            startAngleDegrees = 90f,
            sweepAngleDegrees = 180f,
            forceMoveTo = false,
        )
        close()
    }

    // 1. Soft contact shadow beneath the glass tube
    drawPath(
        path = path,
        color = GlassPalette.baseShadow.copy(alpha = 0.16f),
    )

    // 2. Translucent frosted glass body
    drawPath(
        path = path,
        brush = Brush.linearGradient(
            colors = listOf(
                Color.White.copy(alpha = 0.25f),
                GlassPalette.glassFill.copy(alpha = 0.18f),
                GlassPalette.cyan.copy(alpha = 0.08f),
                GlassPalette.baseShadow.copy(alpha = 0.12f),
            ),
            start = Offset(0f, 0f),
            end = Offset(w, h),
        ),
    )

    // 3. Subtle internal light glow along filled fraction
    if (fraction > 0.01f) {
        val filledW = (w * fraction).coerceIn(0f, w)
        val glowAlpha = if (enabled) 1f else 0.4f
        drawLine(
            brush = Brush.horizontalGradient(
                colors = listOf(
                    GlassPalette.cyan.copy(alpha = 0.65f * glowAlpha),
                    GlassPalette.mint.copy(alpha = 0.55f * glowAlpha),
                    GlassPalette.lavender.copy(alpha = 0.45f * glowAlpha),
                ),
                endX = filledW,
            ),
            start = Offset(rEnd, midY),
            end = Offset(filledW, midY),
            strokeWidth = rWaist * 1.2f,
            cap = StrokeCap.Round,
            alpha = if (enabled) 0.80f else 0.35f,
        )
    }

    // 4. Thin iridescent rim stroke
    drawPath(
        path = path,
        brush = Brush.linearGradient(
            colors = listOf(
                Color.White.copy(alpha = 0.70f),
                GlassPalette.cyan.copy(alpha = 0.50f),
                GlassPalette.mint.copy(alpha = 0.45f),
                GlassPalette.lavender.copy(alpha = 0.50f),
                Color.White.copy(alpha = 0.65f),
            ),
            start = Offset(0f, 0f),
            end = Offset(w, h),
        ),
        style = Stroke(width = 1.2.dp.toPx()),
    )
}

private fun DrawScope.drawStandardTrack(
    w: Float,
    h: Float,
    fraction: Float,
    enabled: Boolean,
) {
    val y = h / 2f
    val filledWidth = w * fraction
    if (filledWidth > 1f) {
        drawLine(
            brush = Brush.horizontalGradient(
                colors = listOf(
                    GlassPalette.cyan,
                    GlassPalette.mint,
                    GlassPalette.gold,
                    GlassPalette.coral,
                ),
                endX = filledWidth,
            ),
            start = Offset(0f, y),
            end = Offset(filledWidth, y),
            strokeWidth = h * 0.65f,
            cap = StrokeCap.Round,
            alpha = if (enabled) 0.80f else 0.35f,
        )
    }
}
