package dev.geode.ui.glass

import androidx.compose.foundation.background
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate

/**
 * Draws the high-end 3D liquid-glass material shared by every primitive in `ui/glass`:
 * - Ambient and contact drop shadows cast onto the water surface
 * - Convex volumetric glass body with internal depth and light refraction
 * - Dual specular highlights (primary upper-left glint + secondary underside bounce reflection)
 * - Thin-film iridescent rim with spectral color transition (cyan -> mint -> peach -> lavender)
 * - Optional selected opaline wash and glow ring
 *
 * Runs down to API 26 via cached layered rendering without allocation in the draw loop.
 *
 * @param shape the geometric outline to draw and clip to.
 * @param tint an accent colour mixed into the body and rim.
 * @param selected draws a luminous active wash, matching the "Home" pill in ref-05.
 * @param glow 0..1 strength of an outer glow ring.
 */
fun Modifier.glassSurface(
    shape: Shape = GlassShapes.tile,
    tint: Color? = null,
    selected: Boolean = false,
    glow: Float = 0f,
): Modifier =
    composed {
        this.drawWithCache {
            val outline = shape.createOutline(size, layoutDirection, this)
            val path = Path().apply { addOutline(outline) }
            onDrawBehind {
                drawGlass(outline, path, tint, selected, glow)
            }
        }
    }

private fun DrawScope.drawGlass(
    outline: Outline,
    path: Path,
    tint: Color?,
    selected: Boolean,
    glow: Float,
) {
    drawGlassShadows(outline)
    clipPath(path) {
        drawGlassBody(tint, selected)
        drawGlassInnerDepth()
        drawGlassSpecularHighlights()
    }
    drawGlassIridescentRim(outline, tint)
    if (glow > 0.01f) drawGlassGlowRing(outline, tint, glow)
}

/**
 * Dual-tier drop shadow: a tight darker contact shadow plus a soft blurred ambient shadow,
 * anchoring the glass element over the fluid water background.
 */
private fun DrawScope.drawGlassShadows(outline: Outline) {
    val maxOffset = GlassElevation.shadowBlur.toPx() * 0.65f

    // 1. Soft ambient shadow layers
    val ambientLayers = 4
    for (i in ambientLayers downTo 1) {
        val t = i / ambientLayers.toFloat()
        val alpha = 0.09f * (1f - t * 0.65f)
        translate(top = maxOffset * t) {
            drawOutline(outline, color = GlassPalette.glassShadow.copy(alpha = alpha))
        }
    }

    // 2. Direct contact shadow (tighter and slightly deeper under the base)
    val contactOffset = maxOffset * 0.28f
    translate(top = contactOffset) {
        drawOutline(outline, color = GlassPalette.baseShadow.copy(alpha = 0.16f))
    }
}

/**
 * The convex volumetric glass body: silky frosted opaline core with soft diffuse light transition
 * matching ref-02 and ref-05.
 */
private fun DrawScope.drawGlassBody(
    tint: Color?,
    selected: Boolean,
) {
    // Silky translucent frosted base
    drawRect(GlassPalette.glassFill.copy(alpha = 0.26f))

    // Soft diffuse volumetric wash (misty top-left light to velvety slate-periwinkle depth)
    drawRect(
        brush =
            Brush.linearGradient(
                colors =
                    listOf(
                        Color.White.copy(alpha = 0.14f),
                        GlassPalette.mint.copy(alpha = 0.06f),
                        GlassPalette.lavender.copy(alpha = 0.07f),
                        GlassPalette.baseShadow.copy(alpha = 0.10f),
                    ),
                start = Offset(0f, 0f),
                end = Offset(size.width, size.height),
            ),
    )

    // Internal matte scattering glow
    drawRect(
        brush =
            Brush.radialGradient(
                colors =
                    listOf(
                        Color.White.copy(alpha = 0.10f),
                        Color.Transparent,
                    ),
                center = Offset(size.width * 0.40f, size.height * 0.35f),
                radius = size.maxDimension * 0.65f,
            ),
    )

    // Accent wash
    val wash = tint ?: GlassPalette.mint
    if (selected) {
        drawRect(
            brush =
                Brush.radialGradient(
                    colors = listOf(wash.copy(alpha = 0.30f), wash.copy(alpha = 0.16f)),
                    center = Offset(size.width * 0.45f, size.height * 0.45f),
                    radius = size.maxDimension * 0.7f,
                ),
        )
    } else if (tint != null) {
        drawRect(wash.copy(alpha = 0.12f))
    }
}

/**
 * Inner thickness shadow along the opposite contour (bottom and right), giving the optical
 * appearance of glass edge density where light undergoes internal reflection.
 */
private fun DrawScope.drawGlassInnerDepth() {
    drawRect(
        brush =
            Brush.radialGradient(
                colors =
                    listOf(
                        Color.Transparent,
                        Color.Transparent,
                        GlassPalette.baseShadow.copy(alpha = 0.12f),
                    ),
                center = Offset(size.width * 0.38f, size.height * 0.34f),
                radius = size.maxDimension * 0.68f,
            ),
    )
}

/**
 * Matte diffuse specular highlights (ref-02 and ref-05):
 * Velvety, broad satin sheen rather than harsh shiny reflections.
 */
private fun DrawScope.drawGlassSpecularHighlights() {
    // 1. Broad diffuse satin sheen on the upper-left
    val primaryRadius = size.minDimension * 0.90f
    val primaryCenter = Offset(size.width * 0.30f, size.height * 0.26f)
    drawCircle(
        brush =
            Brush.radialGradient(
                colors =
                    listOf(
                        Color.White.copy(alpha = 0.32f),
                        Color.White.copy(alpha = 0.12f),
                        Color.Transparent,
                    ),
                center = primaryCenter,
                radius = primaryRadius,
            ),
        radius = primaryRadius,
        center = primaryCenter,
    )

    // 2. Soft underside ambient bounce sheen
    val bounceRadius = size.minDimension * 0.75f
    val bounceCenter = Offset(size.width * 0.72f, size.height * 0.78f)
    drawCircle(
        brush =
            Brush.radialGradient(
                colors =
                    listOf(
                        GlassPalette.cyan.copy(alpha = 0.09f),
                        GlassPalette.lavender.copy(alpha = 0.05f),
                        Color.Transparent,
                    ),
                center = bounceCenter,
                radius = bounceRadius,
            ),
        radius = bounceRadius,
        center = bounceCenter,
    )
}

/**
 * Iridescent spectral rim: soft, silky frosted stroke with delicate thin-film pastel transitions.
 */
private fun DrawScope.drawGlassIridescentRim(
    outline: Outline,
    tint: Color?,
) {
    val rimBrush =
        if (tint != null) {
            Brush.linearGradient(
                colors =
                    listOf(
                        Color.White.copy(alpha = 0.65f),
                        tint.copy(alpha = 0.55f),
                        tint.copy(alpha = 0.30f),
                    ),
                start = Offset(0f, 0f),
                end = Offset(size.width, size.height),
            )
        } else {
            Brush.linearGradient(
                colors =
                    listOf(
                        Color.White.copy(alpha = 0.65f),
                        GlassPalette.cyan.copy(alpha = 0.45f),
                        GlassPalette.mint.copy(alpha = 0.50f),
                        GlassPalette.gold.copy(alpha = 0.45f),
                        GlassPalette.coral.copy(alpha = 0.40f),
                        GlassPalette.lavender.copy(alpha = 0.50f),
                        Color.White.copy(alpha = 0.55f),
                    ),
                start = Offset(0f, 0f),
                end = Offset(size.width, size.height),
            )
        }

    drawOutline(
        outline,
        brush = rimBrush,
        style = Stroke(width = GlassElevation.rimWidth.toPx()),
    )
}

private fun DrawScope.drawGlassGlowRing(
    outline: Outline,
    tint: Color?,
    glow: Float,
) {
    val glowColor = (tint ?: GlassPalette.mint).copy(alpha = (0.55f * glow).coerceIn(0f, 0.55f))
    drawOutline(
        outline,
        color = glowColor,
        style = Stroke(width = GlassElevation.glowBlur.toPx() * 0.25f * glow),
        blendMode = BlendMode.Plus,
    )
}

fun Modifier.glassScrim(
    color: Color = Color.Black,
    maxAlpha: Float = 0.35f,
): Modifier =
    background(
        Brush.verticalGradient(
            0f to Color.Transparent,
            1f to color.copy(alpha = maxAlpha.coerceIn(0f, 1f)),
        ),
    )
