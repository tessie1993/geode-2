package dev.geode.ui.glass

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection

/** Orientation direction for the tapered apex of the teardrop. */
enum class TeardropOrientation {
    UP,
    DOWN,
    LEFT,
    RIGHT,
}

/**
 * A smooth cubic-bezier teardrop / water drop shape matching the reference imagery
 * (ref-01, ref-02, video-v3, and video-v4).
 */
class TeardropShape(
    val orientation: TeardropOrientation = TeardropOrientation.UP,
) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val w = size.width
        val h = size.height
        val path = Path()

        when (orientation) {
            TeardropOrientation.UP -> {
                path.moveTo(w * 0.5f, 0f)
                // Down right flank
                path.cubicTo(
                    w * 0.55f, h * 0.15f,
                    w, h * 0.38f,
                    w, h * 0.65f,
                )
                // Bottom right to bottom center
                path.cubicTo(
                    w, h * 0.95f,
                    w * 0.78f, h,
                    w * 0.5f, h,
                )
                // Bottom center to bottom left
                path.cubicTo(
                    w * 0.22f, h,
                    0f, h * 0.95f,
                    0f, h * 0.65f,
                )
                // Up left flank to apex
                path.cubicTo(
                    0f, h * 0.38f,
                    w * 0.45f, h * 0.15f,
                    w * 0.5f, 0f,
                )
            }
            TeardropOrientation.DOWN -> {
                path.moveTo(w * 0.5f, h)
                path.cubicTo(
                    w * 0.55f, h * 0.85f,
                    w, h * 0.62f,
                    w, h * 0.35f,
                )
                path.cubicTo(
                    w, h * 0.05f,
                    w * 0.78f, 0f,
                    w * 0.5f, 0f,
                )
                path.cubicTo(
                    w * 0.22f, 0f,
                    0f, h * 0.05f,
                    0f, h * 0.35f,
                )
                path.cubicTo(
                    0f, h * 0.62f,
                    w * 0.45f, h * 0.85f,
                    w * 0.5f, h,
                )
            }
            TeardropOrientation.LEFT -> {
                path.moveTo(0f, h * 0.5f)
                path.cubicTo(
                    w * 0.15f, h * 0.45f,
                    w * 0.38f, 0f,
                    w * 0.65f, 0f,
                )
                path.cubicTo(
                    w * 0.95f, 0f,
                    w, h * 0.22f,
                    w, h * 0.5f,
                )
                path.cubicTo(
                    w, h * 0.78f,
                    w * 0.95f, h,
                    w * 0.65f, h,
                )
                path.cubicTo(
                    w * 0.38f, h,
                    w * 0.15f, h * 0.55f,
                    0f, h * 0.5f,
                )
            }
            TeardropOrientation.RIGHT -> {
                path.moveTo(w, h * 0.5f)
                path.cubicTo(
                    w * 0.85f, h * 0.45f,
                    w * 0.62f, 0f,
                    w * 0.35f, 0f,
                )
                path.cubicTo(
                    w * 0.05f, 0f,
                    0f, h * 0.22f,
                    0f, h * 0.5f,
                )
                path.cubicTo(
                    0f, h * 0.78f,
                    w * 0.05f, h,
                    w * 0.35f, h,
                )
                path.cubicTo(
                    w * 0.62f, h,
                    w * 0.85f, h * 0.55f,
                    w, h * 0.5f,
                )
            }
        }
        path.close()
        return Outline.Generic(path)
    }
}

/**
 * A floating liquid-glass teardrop container with authentic refractive body, dual specular glints,
 * and contact shadow. Frames album art or visualizer scenes as seen in video-v3 and video-v4.
 */
@Composable
fun GlassTeardropContainer(
    modifier: Modifier = Modifier,
    orientation: TeardropOrientation = TeardropOrientation.UP,
    tint: Color? = null,
    selected: Boolean = false,
    contentAlignment: Alignment = Alignment.Center,
    content: @Composable BoxScope.() -> Unit,
) {
    val shape = TeardropShape(orientation)
    Box(
        modifier = modifier
            .glassSurface(shape = shape, tint = tint, selected = selected)
            .floatOnWater(strength = 0.8f),
        contentAlignment = contentAlignment,
        content = content,
    )
}
