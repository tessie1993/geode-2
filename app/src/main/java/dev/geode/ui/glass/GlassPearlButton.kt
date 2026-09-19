package dev.geode.ui.glass

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.geode.ui.theme.StoneHapticCue
import dev.geode.ui.theme.performStoneHaptic
import kotlinx.coroutines.launch

/**
 * A true 3D volumetric spherical pearl button matching the mockup in liquid_player_lib_1789776612307.jpg:
 * - Fluid ambient & contact shadow onto the water surface
 * - Opaline iridescent spherical body with multi-spectral light refraction
 * - Top-left satin specular glint and bottom bounce reflection
 * - Iridescent rim light with thin-film color fringe
 * - Embossed icon with glass refraction depth
 * - AA-quality spring press animation and tactile water coupling
 */
@Composable
fun GlassPearlSphereButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    enabled: Boolean = true,
    tint: Color? = null,
    glow: Float = 0f,
) {
    val field = LocalWaterField.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val pressScale = remember { Animatable(1f) }

    Box(
        modifier = modifier
            .size(size)
            .floatOnWater(strength = 0.55f)
            .graphicsLayer {
                scaleX = pressScale.value
                scaleY = pressScale.value
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(
                    onPress = { offset ->
                        view.performStoneHaptic(StoneHapticCue.TAP)
                        field?.tap(offset.x, offset.y, 2.5f)
                        scope.launch {
                            pressScale.animateTo(
                                0.90f,
                                spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = Spring.DampingRatioMediumBouncy),
                            )
                        }
                        val success = tryAwaitRelease()
                        scope.launch {
                            pressScale.animateTo(
                                1f,
                                spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = Spring.DampingRatioMediumBouncy),
                            )
                        }
                        if (success) {
                            field?.tap(offset.x, offset.y, 1.5f)
                            onClick()
                        }
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.matchParentSize()) {
            val radius = size.toPx() / 2f
            val center = Offset(radius, radius)

            // 1. Contact shadow on water surface
            val shadowOffset = radius * 0.35f
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        GlassPalette.baseShadow.copy(alpha = 0.42f),
                        GlassPalette.glassShadow.copy(alpha = 0.22f),
                        Color.Transparent,
                    ),
                    center = center + Offset(0f, shadowOffset),
                    radius = radius * 1.15f,
                ),
                radius = radius * 1.15f,
                center = center + Offset(0f, shadowOffset),
            )

            // 2. Outer luminous glow ring if requested
            if (glow > 0.05f) {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            (tint ?: GlassPalette.mint).copy(alpha = 0.45f * glow),
                            Color.Transparent,
                        ),
                        center = center,
                        radius = radius * 1.35f,
                    ),
                    radius = radius * 1.35f,
                    center = center,
                    blendMode = BlendMode.Plus,
                )
            }

            // 3. Volumetric 3D pearl sphere body with iridescent pastel refraction
            val lightCenter = center - Offset(radius * 0.25f, radius * 0.28f)
            val pearlColors = if (tint != null) {
                listOf(
                    Color.White.copy(alpha = 0.90f),
                    Color.White.copy(alpha = 0.65f),
                    tint.copy(alpha = 0.45f),
                    tint.copy(alpha = 0.25f),
                    GlassPalette.baseShadow.copy(alpha = 0.20f),
                )
            } else {
                listOf(
                    Color.White.copy(alpha = 0.92f),
                    Color.White.copy(alpha = 0.70f),
                    GlassPalette.mint.copy(alpha = 0.40f),
                    GlassPalette.cyan.copy(alpha = 0.35f),
                    GlassPalette.lavender.copy(alpha = 0.30f),
                    GlassPalette.baseShadow.copy(alpha = 0.22f),
                )
            }
            drawCircle(
                brush = Brush.radialGradient(
                    colors = pearlColors,
                    center = lightCenter,
                    radius = radius * 1.05f,
                ),
                radius = radius,
                center = center,
            )

            // 4. Underside bounce reflection (soft cyan/lavender glow)
            val bounceCenter = center + Offset(radius * 0.32f, radius * 0.36f)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        GlassPalette.cyan.copy(alpha = 0.28f),
                        GlassPalette.lavender.copy(alpha = 0.15f),
                        Color.Transparent,
                    ),
                    center = bounceCenter,
                    radius = radius * 0.75f,
                ),
                radius = radius * 0.75f,
                center = bounceCenter,
            )

            // 5. Thin-film iridescent rim
            val rimBrush = Brush.linearGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.85f),
                    GlassPalette.cyan.copy(alpha = 0.60f),
                    GlassPalette.mint.copy(alpha = 0.65f),
                    GlassPalette.gold.copy(alpha = 0.55f),
                    GlassPalette.coral.copy(alpha = 0.50f),
                    GlassPalette.lavender.copy(alpha = 0.60f),
                    Color.White.copy(alpha = 0.75f),
                ),
                start = Offset(0f, 0f),
                end = Offset(size.toPx(), size.toPx()),
            )
            drawCircle(
                brush = rimBrush,
                radius = radius - 0.5f,
                center = center,
                style = Stroke(width = 1.2.dp.toPx()),
            )

            // 6. Satin specular crescent glint on upper-left
            val glintCenter = center - Offset(radius * 0.36f, radius * 0.38f)
            val glintRadius = radius * 0.44f
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.88f),
                        Color.White.copy(alpha = 0.35f),
                        Color.Transparent,
                    ),
                    center = glintCenter,
                    radius = glintRadius,
                ),
                radius = glintRadius,
                center = glintCenter,
            )
        }

        // Embossed White Icon inside the pearl
        val iconSize = size * 0.44f
        // Subtle soft shadow behind icon for engraved depth
        Icon(
            icon,
            contentDescription = null,
            tint = GlassPalette.baseShadow.copy(alpha = 0.35f),
            modifier = Modifier
                .size(iconSize)
                .offset(y = 1.dp),
        )
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = if (enabled) GlassPalette.textPrimary else GlassPalette.textSecondary.copy(alpha = 0.5f),
            modifier = Modifier.size(iconSize),
        )
    }
}

/**
 * Floating decorative pearl bead (like the accent spheres flanking the controls in the mockup).
 * Tactile: bobs on water, ripples when tapped, and adds fluid physical presence to the UI.
 */
@Composable
fun GlassPearlAccentBead(
    modifier: Modifier = Modifier,
    size: Dp = 28.dp,
    tint: Color = GlassPalette.mint,
) {
    val field = LocalWaterField.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val pressScale = remember { Animatable(1f) }

    Box(
        modifier = modifier
            .size(size)
            .floatOnWater(strength = 0.7f)
            .graphicsLayer {
                scaleX = pressScale.value
                scaleY = pressScale.value
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { offset ->
                        view.performStoneHaptic(StoneHapticCue.SLIDER_TICK)
                        field?.tap(offset.x, offset.y, 1.8f)
                        scope.launch {
                            pressScale.animateTo(1.2f, spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = Spring.DampingRatioHighBouncy))
                            pressScale.animateTo(1f, spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = Spring.DampingRatioMediumBouncy))
                        }
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.matchParentSize()) {
            val radius = size.toPx() / 2f
            val center = Offset(radius, radius)

            // Contact shadow
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(GlassPalette.baseShadow.copy(alpha = 0.35f), Color.Transparent),
                    center = center + Offset(0f, radius * 0.35f),
                    radius = radius * 1.15f,
                ),
                radius = radius * 1.15f,
                center = center + Offset(0f, radius * 0.35f),
            )

            // Iridescent pearl body
            val lightCenter = center - Offset(radius * 0.28f, radius * 0.28f)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.90f),
                        Color.White.copy(alpha = 0.60f),
                        tint.copy(alpha = 0.50f),
                        GlassPalette.lavender.copy(alpha = 0.35f),
                        GlassPalette.baseShadow.copy(alpha = 0.18f),
                    ),
                    center = lightCenter,
                    radius = radius * 1.05f,
                ),
                radius = radius,
                center = center,
            )

            // Rim
            drawCircle(
                color = GlassPalette.glassRim.copy(alpha = 0.70f),
                radius = radius - 0.5f,
                center = center,
                style = Stroke(width = 1.dp.toPx()),
            )

            // Specular glint
            val glintCenter = center - Offset(radius * 0.35f, radius * 0.35f)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color.White.copy(alpha = 0.85f), Color.Transparent),
                    center = glintCenter,
                    radius = radius * 0.45f,
                ),
                radius = radius * 0.45f,
                center = glintCenter,
            )
        }
    }
}
