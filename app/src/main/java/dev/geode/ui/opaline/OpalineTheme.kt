package dev.geode.ui.opaline

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.geode.ui.GuiPrefs

val LocalOpalineBackgroundDim = staticCompositionLocalOf { 0f }
val LocalOpalineMotionAmount = staticCompositionLocalOf { 1f }
val LocalOpalineReducedMotion = staticCompositionLocalOf { false }

@Composable
fun OpalineTheme(
    gui: GuiPrefs,
    content: @Composable () -> Unit,
) {
    val scale = gui.textScale.coerceIn(0.85f, 1.3f)

    fun type(
        size: Int,
        weight: FontWeight = FontWeight.Normal,
    ) = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = (size * scale).sp,
        lineHeight = (size * scale * 1.35f).sp,
        fontWeight = weight,
    )
    val palette = OpalinePalette.entries.firstOrNull { it.name == gui.opalinePalette } ?: OpalinePalette.TIDAL
    CompositionLocalProvider(
        LocalOpalineReducedMotion provides (gui.reducedMotion || gui.liquidMotion <= 0f),
        LocalOpalinePalette provides palette,
        LocalOpalineBackgroundDim provides gui.backgroundDim,
        LocalOpalineMotionAmount provides gui.liquidMotion,
    ) {
        MaterialTheme(
            colorScheme =
                darkColorScheme(
                    primary = Color(0xFF000000L or palette.accent.toLong()),
                    onPrimary = OpalineColors.ink,
                    secondary = OpalineColors.lavender,
                    onSecondary = OpalineColors.ink,
                    tertiary = OpalineColors.amber,
                    onTertiary = OpalineColors.ink,
                    background = Color(0xFF000000L or palette.background.toLong()),
                    onBackground = OpalineColors.text,
                    surface = OpalineColors.surface,
                    onSurface = OpalineColors.text,
                    surfaceVariant = OpalineColors.deep,
                    onSurfaceVariant = OpalineColors.muted,
                    surfaceContainer = OpalineColors.surface,
                    surfaceContainerHigh = OpalineColors.background,
                    primaryContainer = Color(0xFF000000L or palette.gel.toLong()),
                    onPrimaryContainer = OpalineColors.ink,
                    secondaryContainer = OpalineColors.surface,
                    onSecondaryContainer = OpalineColors.text,
                    outline = OpalineColors.rim.copy(alpha = 0.5f),
                    error = OpalineColors.error,
                    onError = OpalineColors.ink,
                ),
            shapes =
                Shapes(
                    extraSmall = RoundedCornerShape(10.dp),
                    small = RoundedCornerShape(16.dp),
                    medium = RoundedCornerShape(22.dp),
                    large = RoundedCornerShape(28.dp),
                    extraLarge = RoundedCornerShape(36.dp),
                ),
            typography =
                Typography(
                    displayLarge = type(44, FontWeight.Light),
                    displayMedium = type(36, FontWeight.Light),
                    displaySmall = type(30, FontWeight.Medium),
                    headlineLarge = type(30, FontWeight.Medium),
                    headlineMedium = type(26, FontWeight.Medium),
                    headlineSmall = type(22, FontWeight.Medium),
                    titleLarge = type(21, FontWeight.Medium),
                    titleMedium = type(17, FontWeight.Medium),
                    titleSmall = type(15, FontWeight.Medium),
                    bodyLarge = type(16),
                    bodyMedium = type(14),
                    bodySmall = type(12),
                    labelLarge = type(14, FontWeight.Medium),
                    labelMedium = type(12, FontWeight.Medium),
                    labelSmall = type(11, FontWeight.Medium),
                ),
            content = content,
        )
    }
}
