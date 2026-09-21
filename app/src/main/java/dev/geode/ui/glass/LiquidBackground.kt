package dev.geode.ui.glass

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.geode.R
import kotlinx.coroutines.isActive
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

private data class Bubble(
    val baseX: Float,
    val baseY: Float,
    val radiusDp: Float,
    val phase: Float,
    val periodMs: Float,
    val clear: Boolean,
)

/**
 * The liquid-glass background: a simulated water surface (see docs/design/liquid-glass/README.md
 * "Water field"). Each frame it renders a small caustic bitmap from the shared [WaterField]'s
 * height normals over the pastel base, composites the dye field on top, and draws it scaled with
 * bilinear filtering (API 26+); on API >= 33 an AGSL refraction pass is layered over the same two
 * bitmaps. Drifting bubbles and droplets ride the field's flow and bob on its height. Pauses all
 * animation when [motion] is 0 or under reduced motion.
 *
 * Must fill its screen's root at position (0, 0): touch/float coupling on other glass elements
 * samples the field in root-relative pixels, so the background and the sampled coordinate space
 * must line up.
 */
@Composable
fun LiquidBackground(
    modifier: Modifier = Modifier,
    motion: Float = 1f,
    bubbleDensity: Float = 0.5f,
    tint: Float = 0.5f,
) {
    val glass = LocalGlass.current
    val reducedMotion = glass.reducedMotion
    val field = LocalWaterField.current ?: rememberWaterField(liquidMotion = motion, reducedMotion = reducedMotion)
    val bubbles = remember(bubbleDensity) { generateBubbles(bubbleDensity) }

    val context = LocalContext.current
    val baseImage =
        remember {
            ImageBitmap.imageResource(context.resources, R.drawable.tp_opaline_ambient_portrait)
        }

    val (hw, hh) = field.heightGridSize
    val causticBitmap = remember(hw, hh) { Bitmap.createBitmap(hw, hh, Bitmap.Config.ARGB_8888) }
    val causticPixels = remember(hw, hh) { IntArray(hw * hh) }
    val causticImage = remember(causticBitmap) { causticBitmap.asImageBitmap() }
    var frameTick by remember { mutableIntStateOf(0) }
    val shader = rememberRefractionShader()

    LaunchedEffect(reducedMotion, motion, hw, hh, tint) {
        renderCausticFrame(field, causticPixels, hw, hh, tint)
        causticBitmap.setPixels(causticPixels, 0, hw, 0, 0, hw, hh)
        frameTick++
        if (reducedMotion || motion <= 0f) return@LaunchedEffect
        while (isActive) {
            withFrameNanos {
                renderCausticFrame(field, causticPixels, hw, hh, tint)
                causticBitmap.setPixels(causticPixels, 0, hw, 0, 0, hw, hh)
                frameTick++
            }
        }
    }

    Canvas(
        modifier
            .fillMaxSize()
            .onGloballyPositioned { field.updateCanvasSize(it.size.width.toFloat(), it.size.height.toFloat()) },
    ) {
        // Reads frameTick so this draw block re-runs every time a new frame's caustics/dye land.
        frameTick.let { }
        drawBase(baseImage)
        drawCausticLayer(causticImage, shader, tint)
        drawBubbles(bubbles, field, reducedMotion)
    }
}

private fun DrawScope.drawBase(image: ImageBitmap) {
    drawImage(
        image,
        dstSize = IntSize(size.width.toInt().coerceAtLeast(1), size.height.toInt().coerceAtLeast(1)),
        filterQuality = FilterQuality.Low,
    )
}

private fun DrawScope.drawCausticLayer(
    image: ImageBitmap,
    shader: RuntimeShader?,
    tint: Float,
) {
    val dst = IntSize(size.width.toInt().coerceAtLeast(1), size.height.toInt().coerceAtLeast(1))
    if (shader != null) {
        drawRefractedCaustics(image, shader, dst, tint)
    } else {
        drawImage(image, dstSize = dst, filterQuality = FilterQuality.Low, alpha = 0.55f, blendMode = BlendMode.Screen)
    }
}

/** Best-effort AGSL refraction pass; falls back to the plain bitmap draw on any failure so a
 * shader-compile quirk on a given device never breaks the background. */
private fun DrawScope.drawRefractedCaustics(
    image: ImageBitmap,
    shader: RuntimeShader,
    dst: IntSize,
    tint: Float,
) {
    // Redundant at runtime: rememberRefractionShader returns null below API 33, so a non-null
    // RuntimeShader can only exist above it. Lint cannot follow that across two functions, and
    // :app:lintDebug runs with abortOnError, so without this the whole build fails on five NewApi
    // errors. Guarding here rather than suppressing also makes the function correct on its own
    // terms, instead of resting on a precondition established by a different composable.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        drawImage(image, dstSize = dst, filterQuality = FilterQuality.Low, alpha = 0.85f)
        return
    }
    val drewViaShader =
        runCatching {
            val bitmapShader =
                BitmapShader(image.asAndroidBitmap(), Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            shader.setInputShader("base", bitmapShader)
            shader.setFloatUniform("resolution", size.width, size.height)
            shader.setFloatUniform("time", (System.nanoTime() / 1_000_000_000f))
            shader.setFloatUniform("tint", tint)
            drawRect(brush = ShaderBrush(shader))
        }.isSuccess
    if (!drewViaShader) {
        drawImage(image, dstSize = dst, filterQuality = FilterQuality.Low, alpha = 0.85f)
    }
}

@Composable
private fun rememberRefractionShader(): RuntimeShader? =
    remember {
        if (Build.VERSION.SDK_INT >= 33) runCatching { RuntimeShader(REFRACTION_AGSL) }.getOrNull() else null
    }

/** brightness = base + k . max(0, n.L)^2, n from the height gradient, L from device tilt. */
private fun renderCausticFrame(
    field: WaterField,
    pixels: IntArray,
    hw: Int,
    hh: Int,
    tint: Float,
) {
    val grid = field.heightGrid()
    val baseR = GlassPalette.base.red
    val baseG = GlassPalette.base.green
    val baseB = GlassPalette.base.blue
    val lx = 0.35f
    val ly = 0.45f
    for (y in 0 until hh) {
        for (x in 0 until hw) {
            val idx = y * hw + x
            val left = grid[if (x > 0) idx - 1 else idx]
            val right = grid[if (x < hw - 1) idx + 1 else idx]
            val up = grid[if (y > 0) idx - hw else idx]
            val down = grid[if (y < hh - 1) idx + hw else idx]
            val nx = left - right
            val ny = up - down
            val dot = max(0f, nx * lx + ny * ly)
            val bright = (0.75f + CAUSTIC_GAIN * dot * dot * (0.5f + tint)).coerceIn(0.5f, 1.45f)
            val r = (baseR * bright).coerceIn(0f, 1f)
            val g = (baseG * bright).coerceIn(0f, 1f)
            val b = (baseB * bright).coerceIn(0f, 1f)
            pixels[idx] = argb(1f, r, g, b)
        }
    }
    blendDye(field, pixels, hw, hh)
}

private fun blendDye(
    field: WaterField,
    pixels: IntArray,
    hw: Int,
    hh: Int,
) {
    val (dr, dg, db) = field.dyeGrids()
    val (vw, vh) = field.dyeGridSize
    for (y in 0 until hh) {
        val vy = (y * vh / hh).coerceIn(0, vh - 1)
        for (x in 0 until hw) {
            val vx = (x * vw / hw).coerceIn(0, vw - 1)
            val vIdx = vy * vw + vx
            val a = (dr[vIdx] + dg[vIdx] + db[vIdx]).coerceIn(0f, 1f) / 1.6f
            if (a < 0.01f) continue
            val idx = y * hw + x
            val base = pixels[idx]
            pixels[idx] =
                mixArgb(base, argb(1f, min(1f, dr[vIdx]), min(1f, dg[vIdx]), min(1f, db[vIdx])), a)
        }
    }
}

private fun argb(
    a: Float,
    r: Float,
    g: Float,
    b: Float,
): Int =
    ((a * 255).toInt().coerceIn(0, 255) shl 24) or
        ((r * 255).toInt().coerceIn(0, 255) shl 16) or
        ((g * 255).toInt().coerceIn(0, 255) shl 8) or
        (b * 255).toInt().coerceIn(0, 255)

private fun mixArgb(
    base: Int,
    overlay: Int,
    t: Float,
): Int {
    fun channel(shift: Int): Int {
        val bC = (base ushr shift) and 0xFF
        val oC = (overlay ushr shift) and 0xFF
        return (bC + (oC - bC) * t).toInt().coerceIn(0, 255)
    }
    return (0xFF shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
}

private fun generateBubbles(density: Float): List<Bubble> {
    val count = (MIN_BUBBLES + (MAX_BUBBLES - MIN_BUBBLES) * density.coerceIn(0f, 1f)).toInt()
    val random = Random(BUBBLE_SEED)
    return List(count) { i ->
        Bubble(
            baseX = random.nextFloat(),
            baseY = random.nextFloat(),
            radiusDp = if (i % 3 == 0) random.nextFloat() * 10f + 14f else random.nextFloat() * 6f + 5f,
            phase = random.nextFloat() * TWO_PI,
            periodMs =
                (
                    GlassMotion.BUBBLE_DRIFT_PERIOD_MIN_MS + random.nextFloat() *
                        (GlassMotion.BUBBLE_DRIFT_PERIOD_MAX_MS - GlassMotion.BUBBLE_DRIFT_PERIOD_MIN_MS)
                ),
            clear = i % 4 == 0,
        )
    }
}

private fun DrawScope.drawBubbles(
    bubbles: List<Bubble>,
    field: WaterField,
    reducedMotion: Boolean,
) {
    val t = if (reducedMotion) 0f else (System.nanoTime() / 1_000_000f)
    bubbles.forEach { b -> drawBubble(b, field, t) }
}

private fun DrawScope.drawBubble(
    bubble: Bubble,
    field: WaterField,
    tMs: Float,
) {
    val driftX = sin(tMs / bubble.periodMs * TWO_PI + bubble.phase) * DRIFT_AMPLITUDE
    val driftY = cos(tMs / bubble.periodMs * TWO_PI * 0.8f + bubble.phase) * DRIFT_AMPLITUDE
    var cx = (bubble.baseX + driftX) * size.width
    var cy = (bubble.baseY + driftY) * size.height
    val flow = field.flowAt(cx, cy)
    cx += flow.x * FLOW_DRIFT_GAIN
    cy += flow.y * FLOW_DRIFT_GAIN
    val bob = field.heightAt(cx, cy) * BOB_PX
    val center = Offset(cx, cy + bob)
    val radius = bubble.radiusDp.dp.toPx()
    val fillAlpha = if (bubble.clear) 0.14f else 0.22f
    drawCircle(
        brush =
            Brush.radialGradient(
                listOf(Color.White.copy(alpha = fillAlpha), Color.White.copy(alpha = fillAlpha * 0.3f)),
                center = center,
                radius = radius,
            ),
        radius = radius,
        center = center,
    )
    drawCircle(GlassPalette.glassRim.copy(alpha = 0.5f), radius = radius, center = center, style = Stroke(1.dp.toPx()))
    drawCircle(
        Color.White.copy(alpha = 0.45f),
        radius = radius * 0.28f,
        center = center + Offset(-radius * 0.3f, -radius * 0.32f),
    )
}

private const val MIN_BUBBLES = 10
private const val MAX_BUBBLES = 30
private const val BUBBLE_SEED = 1234
private val TWO_PI = (Math.PI * 2).toFloat()
private const val DRIFT_AMPLITUDE = 0.04f
private const val FLOW_DRIFT_GAIN = 40f
private const val BOB_PX = 3f
private const val CAUSTIC_GAIN = 3.2f

private const val REFRACTION_AGSL =
    """
    uniform shader base;
    uniform float2 resolution;
    uniform float time;
    uniform float tint;

    half4 main(float2 fragCoord) {
        float2 d = float2(2.5, 0.0);
        half4 cL = base.eval(fragCoord - d.xy);
        half4 cR = base.eval(fragCoord + d.xy);
        half4 cU = base.eval(fragCoord - d.yx);
        half4 cD = base.eval(fragCoord + d.yx);

        // Water surface normal gradient from caustics/ripples
        float nx = (cL.r + cL.g + cL.b) - (cR.r + cR.g + cR.b);
        float ny = (cU.r + cU.g + cU.b) - (cD.r + cD.g + cD.b);
        float2 norm = float2(nx, ny);

        // Optical chromatic dispersion refraction
        float dispersion = 2.4 * (0.8 + 0.4 * tint);
        half4 colR = base.eval(fragCoord + norm * dispersion);
        half4 colG = base.eval(fragCoord + norm * (dispersion * 0.65));
        half4 colB = base.eval(fragCoord + norm * (dispersion * 0.3));

        half4 color = half4(colR.r, colG.g, colB.b, 1.0);

        // Soft velvet matte surface sheen from directional top-left light
        float3 lightDir = normalize(float3(-0.35, -0.45, 0.82));
        float3 surfaceNorm = normalize(float3(norm * 1.8, 1.0));
        float diffuse = max(0.0, dot(surfaceNorm, lightDir));
        float specular = pow(diffuse, 14.0) * 0.22;

        // Wave trough ambient absorption for physical depth
        float trough = clamp(1.0 - (nx + ny) * 0.2, 0.82, 1.08);

        color.rgb = color.rgb * trough + half3(specular);
        return color;
    }
    """
