package dev.geode.ui.glass

import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.dp

/** Native content plane for Opaline's Tidal material. The scene renderer owns spatial geometry;
 * these opaque, layered shells keep native text and controls readable above that scene. */
fun Modifier.glassSurface(
    shape: Shape = GlassShapes.tile,
    tint: Color? = null,
    selected: Boolean = false,
    glow: Float = 0f,
): Modifier =
    composed {
        val settings = LocalGlass.current
        this.drawWithCache {
            val outline = shape.createOutline(size, layoutDirection, this)
            val path = Path().apply { addOutline(outline) }
            val accent = tint ?: GlassPalette.sky
            val depth = 4.dp.toPx()
            val body = Brush.linearGradient(
                listOf(Color(0xFF315967), GlassPalette.glassFill, Color(0xFF15313F)),
                Offset.Zero,
                Offset(size.width * 0.7f, size.height),
            )
            val rim = Brush.linearGradient(
                listOf(GlassPalette.sky.copy(alpha = 0.7f), accent.copy(alpha = 0.13f), Color(0xFF071720)),
                Offset.Zero,
                Offset(size.width, size.height),
            )
            onDrawBehind {
                translate(top = depth * 2f) {
                    drawOutline(outline, Color.Black.copy(alpha = 0.24f))
                }
                translate(top = depth) {
                    drawOutline(outline, Color(0xFF081C27))
                    drawOutline(outline, accent.copy(alpha = 0.2f), style = Stroke(1.dp.toPx()))
                }
                clipPath(path) {
                    drawRect(body)
                    drawRect(accent.copy(alpha = if (selected) 0.22f else 0.035f + settings.tint.coerceIn(0f, 1f) * 0.035f))
                    // A shared upper-left light; the center is quiet for semantic content.
                    drawRect(
                        Brush.verticalGradient(
                            listOf(Color.White.copy(alpha = 0.13f), Color.Transparent),
                            endY = size.height * 0.4f,
                        ),
                    )
                }
                drawOutline(outline, rim, style = Stroke(1.dp.toPx()))
                if (selected || glow > 0f) {
                    drawOutline(outline, accent.copy(alpha = (0.2f + glow * 0.3f).coerceIn(0f, 0.6f)), style = Stroke(1.5.dp.toPx()))
                }
            }
        }
    }
