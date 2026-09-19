package dev.geode.ui.glass

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * Iridescent opaline pearl / bead matrix matching ref-05, ref-06, and ref-10 (bottom-right cluster).
 * 4 columns of tiny frosted glass spheres with pastel fills, soft contact shadows, and tactile wave coupling.
 */
@Composable
fun GlassPearlMatrix(
    modifier: Modifier = Modifier,
    beadSize: Dp = 16.dp,
    spacing: Dp = 6.dp,
) {
    val field = LocalWaterField.current
    val view = LocalView.current

    // Palette matrix directly matching ref-05, ref-06, ref-10
    val beadGrid = remember {
        listOf(
            listOf(GlassPalette.gold, GlassPalette.coral, GlassPalette.gold, GlassPalette.yellow, GlassPalette.yellow),
            listOf(GlassPalette.magenta, GlassPalette.lavender, GlassPalette.magenta, GlassPalette.lavender, GlassPalette.coral),
            listOf(GlassPalette.cerulean, GlassPalette.cyan, GlassPalette.cerulean, GlassPalette.periwinkle, GlassPalette.cyan),
            listOf(GlassPalette.lime, GlassPalette.mint, GlassPalette.lime, GlassPalette.mint, GlassPalette.cyan),
        )
    }

    Row(
        modifier = modifier.floatOnWater(strength = 0.3f),
        horizontalArrangement = Arrangement.spacedBy(spacing),
    ) {
        beadGrid.forEachIndexed { colIdx, columnColors ->
            Column(
                verticalArrangement = Arrangement.spacedBy(spacing),
            ) {
                columnColors.forEachIndexed { rowIdx, tint ->
                    GlassPearlBead(
                        size = beadSize,
                        tint = tint,
                        onTap = { x, y ->
                            view.performGlassHaptic(GlassHapticCue.SLIDER_TICK)
                            field?.tap(x, y, 1.8f)
                        },
                    )
                }
            }
        }
    }
}

/** Single iridescent frosted bead with physical contact shadow and satin top-left highlight. */
@Composable
private fun GlassPearlBead(
    size: Dp,
    tint: Color,
    onTap: (Float, Float) -> Unit,
) {
    Box(
        modifier = Modifier
            .size(size)
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    onTap(offset.x, offset.y)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.matchParentSize()) {
            val radius = size.toPx() / 2f
            val center = Offset(radius, radius)

            // Contact shadow on water surface
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(GlassPalette.glassShadow.copy(alpha = 0.40f), Color.Transparent),
                    center = center + Offset(0f, radius * 0.35f),
                    radius = radius * 1.1f,
                ),
                radius = radius * 1.1f,
                center = center + Offset(0f, radius * 0.35f),
            )

            // Frosted opaline body with pastel core
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        tint.copy(alpha = 0.75f),
                        tint.copy(alpha = 0.45f),
                        GlassPalette.glassFill.copy(alpha = 0.30f),
                    ),
                    center = center - Offset(radius * 0.15f, radius * 0.15f),
                    radius = radius,
                ),
                radius = radius,
                center = center,
            )

            // Micro-beveled rim
            drawCircle(
                color = GlassPalette.glassRim.copy(alpha = 0.65f),
                radius = radius - 0.5f,
                center = center,
                style = Stroke(width = 1.dp.toPx()),
            )

            // Satin diffuse highlight (top-left)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.65f),
                        Color.White.copy(alpha = 0.0f),
                    ),
                    center = center - Offset(radius * 0.35f, radius * 0.35f),
                    radius = radius * 0.45f,
                ),
                radius = radius * 0.45f,
                center = center - Offset(radius * 0.35f, radius * 0.35f),
            )
        }
    }
}

/**
 * Interactive elastic droplet pod matching ref-06 (touch bloom) and ref-10 (upward stretch).
 * When idle, sits as a floating opaline bubble; when dragged, stretches into an ampoule teardrop
 * with a continuous gradient from cyan at the base, through lime and gold, to coral at the tip.
 */
@Composable
fun InteractiveElasticDropletPod(
    modifier: Modifier = Modifier,
    baseSize: Dp = 72.dp,
) {
    val scope = rememberCoroutineScope()
    val stretchY = remember { Animatable(1f) }
    val dragOffsetY = remember { Animatable(0f) }
    val field = LocalWaterField.current
    val view = LocalView.current

    Box(
        modifier = modifier
            .size(baseSize, baseSize * 1.8f)
            .floatOnWater(strength = 0.5f)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { offset ->
                        view.performGlassHaptic(GlassHapticCue.TAP)
                        field?.splat(offset.x, offset.y, 0f, -15f, GlassPalette.coral)
                    },
                    onDragEnd = {
                        scope.launch {
                            stretchY.animateTo(
                                1f,
                                spring(
                                    dampingRatio = GlassMotion.REBOUND_DAMPING,
                                    stiffness = GlassMotion.SPRING_STIFFNESS,
                                ),
                            )
                        }
                        scope.launch {
                            dragOffsetY.animateTo(
                                0f,
                                spring(
                                    dampingRatio = GlassMotion.REBOUND_DAMPING,
                                    stiffness = GlassMotion.SPRING_STIFFNESS,
                                ),
                            )
                        }
                        view.performGlassHaptic(GlassHapticCue.SLIDER_TICK)
                    },
                    onDragCancel = {
                        scope.launch { stretchY.animateTo(1f, spring()) }
                        scope.launch { dragOffsetY.animateTo(0f, spring()) }
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        val currentY = dragOffsetY.value + dragAmount.y
                        if (currentY < 0f) {
                            // Dragging upward: stretch along Y and narrow along X
                            val stretchRatio = (1f + (-currentY / 120f)).coerceIn(1f, 1.85f)
                            scope.launch { stretchY.snapTo(stretchRatio) }
                            scope.launch { dragOffsetY.snapTo(currentY) }
                            field?.splat(change.position.x, change.position.y, 0f, dragAmount.y, GlassPalette.yellow)
                        }
                    },
                )
            },
        contentAlignment = Alignment.BottomCenter,
    ) {
        val sY = stretchY.value
        val sX = 1f / kotlin.math.sqrt(sY.toDouble()).toFloat()

        Box(
            modifier = Modifier
                .size(baseSize)
                .graphicsLayer {
                    translationY = dragOffsetY.value * 0.4f
                    scaleY = sY
                    scaleX = sX
                },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.matchParentSize()) {
                val radius = size.minDimension / 2f
                val center = Offset(size.width / 2f, size.height / 2f)

                // Draw contact shadow
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(GlassPalette.glassShadow.copy(alpha = 0.45f), Color.Transparent),
                        center = center + Offset(0f, radius * 0.3f),
                        radius = radius * 1.2f,
                    ),
                    radius = radius * 1.2f,
                    center = center + Offset(0f, radius * 0.3f),
                )

                // Multi-stop gradient: cyan base -> lime/gold neck -> coral tip (ref-10)
                drawCircle(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            GlassPalette.coral,
                            GlassPalette.gold,
                            GlassPalette.lime,
                            GlassPalette.cyan,
                        ),
                        startY = 0f,
                        endY = size.height,
                    ),
                    radius = radius,
                    center = center,
                    alpha = 0.78f,
                )

                // Opaline frosted white wrap
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.42f),
                            Color.White.copy(alpha = 0.12f),
                        ),
                        center = center - Offset(radius * 0.2f, radius * 0.25f),
                        radius = radius,
                    ),
                    radius = radius,
                    center = center,
                )

                // Soft specular crescent highlight
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.55f),
                            Color.Transparent,
                        ),
                        center = center - Offset(radius * 0.35f, radius * 0.35f),
                        radius = radius * 0.5f,
                    ),
                    radius = radius * 0.5f,
                    center = center - Offset(radius * 0.35f, radius * 0.35f),
                )

                // Rim stroke
                drawCircle(
                    color = GlassPalette.glassRim.copy(alpha = 0.70f),
                    radius = radius - 0.5f,
                    center = center,
                    style = Stroke(width = 1.2.dp.toPx()),
                )
            }
        }
    }
}
