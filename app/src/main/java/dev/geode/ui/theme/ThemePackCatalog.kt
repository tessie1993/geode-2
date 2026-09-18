package dev.geode.ui.theme

import androidx.compose.ui.graphics.Color
import dev.geode.R

object ThemePackCatalog {
    private val opalineMaterial =
        StoneMaterial(
            tile = R.drawable.tp_opaline_material_tile,
            glowOverlay = R.drawable.tp_opaline_glow_overlay,
            refractionOverlay = R.drawable.tp_opaline_refraction_overlay,
            ambientPortrait = R.drawable.tp_opaline_ambient_portrait,
            ambientLandscape = R.drawable.tp_opaline_ambient_landscape,
            ambientSquare = R.drawable.tp_opaline_ambient_square,
            backgroundOpacity = 0.55f,
            surfaceOpacity = 0.35f,
            disabledOpacity = 0.15f,
        )

    private val fluidMotion =
        StoneMotion(
            pressDurationMs = 80,
            pressScale = 0.96f,
            innerGlowGain = 1.6f,
            releaseDurationMs = 320,
            focusDurationMs = 180,
            edgeLightGain = 1.35f,
            selectedDurationMs = 220,
            reduceMotionCrossfadeMs = 100,
        )

    val opalineWater =
        ThemePack(
            slug = "opaline-water",
            name = "Opaline Water",
            stone = "opaline",
            isLight = false,
            palette =
                StonePalette(
                    background = Color(0xFF9AA7C3),
                    backgroundDeep = Color(0xFF7A87A3),
                    surface = Color(0x33FAFBFF),
                    surfaceHigh = Color(0x55FAFBFF),
                    primary = Color(0xFFD8C5E8),
                    secondary = Color(0xFFCEE6D4),
                    accent = Color(0xFF8DDEF0),
                    glow = Color(0xFFE8C8D8),
                    onBackground = Color(0xFFFAFBFF),
                    onSurface = Color(0xFFFAFBFF),
                    muted = Color(0xB3FAFBFF),
                    outline = Color(0x4DFAFBFF),
                    danger = Color(0xFFFF8B94),
                ),
            motion = fluidMotion,
            material = opalineMaterial,
            sounds = StoneSounds(),
            surfaces = emptyMap(),
        )

    val lapisLazuli =
        ThemePack(
            slug = "lapis-lazuli",
            name = "Lapis Lazuli",
            stone = "lapis lazuli",
            isLight = false,
            palette =
                StonePalette(
                    background = Color(0xFF10265C),
                    backgroundDeep = Color(0xFF050A20),
                    surface = Color(0x336D98FF),
                    surfaceHigh = Color(0x556D98FF),
                    primary = Color(0xFF6D98FF),
                    secondary = Color(0xFFD1B36A),
                    accent = Color(0xFF9BB9FF),
                    glow = Color(0xFF80A7FF),
                    onBackground = Color(0xFFF7F8FF),
                    onSurface = Color(0xFFF7F8FF),
                    muted = Color(0xFFBEC7E2),
                    outline = Color(0xFF516491),
                    danger = Color(0xFFFF817D),
                ),
            motion = fluidMotion,
            material = opalineMaterial,
            sounds = StoneSounds(),
            surfaces = emptyMap(),
        )

    val sugilite =
        ThemePack(
            slug = "sugilite",
            name = "Sugilite",
            stone = "sugilite",
            isLight = false,
            palette =
                StonePalette(
                    background = Color(0xFF2C1338),
                    backgroundDeep = Color(0xFF15081C),
                    surface = Color(0x33C471ED),
                    surfaceHigh = Color(0x55C471ED),
                    primary = Color(0xFFD688FF),
                    secondary = Color(0xFFF48FB1),
                    accent = Color(0xFFE1BEE7),
                    glow = Color(0xFFCE93D8),
                    onBackground = Color(0xFFFAF0FF),
                    onSurface = Color(0xFFFAF0FF),
                    muted = Color(0xFFD1C4E9),
                    outline = Color(0xFF6A1B9A),
                    danger = Color(0xFFFF8A80),
                ),
            motion = fluidMotion,
            material = opalineMaterial,
            sounds = StoneSounds(),
            surfaces = emptyMap(),
        )

    val amethyst =
        ThemePack(
            slug = "amethyst",
            name = "Amethyst",
            stone = "amethyst",
            isLight = false,
            palette =
                StonePalette(
                    background = Color(0xFF221133),
                    backgroundDeep = Color(0xFF0E0616),
                    surface = Color(0x33B388FF),
                    surfaceHigh = Color(0x55B388FF),
                    primary = Color(0xFFCDBDF0),
                    secondary = Color(0xFFE8C8D8),
                    accent = Color(0xFFD1C4E9),
                    glow = Color(0xFFD8C5E8),
                    onBackground = Color(0xFFF5EEFF),
                    onSurface = Color(0xFFF5EEFF),
                    muted = Color(0xFFB39DDB),
                    outline = Color(0xFF512DA8),
                    danger = Color(0xFFFF817D),
                ),
            motion = fluidMotion,
            material = opalineMaterial,
            sounds = StoneSounds(),
            surfaces = emptyMap(),
        )

    val clearQuartz =
        ThemePack(
            slug = "clear-quartz",
            name = "Clear Quartz",
            stone = "quartz",
            isLight = false,
            palette =
                StonePalette(
                    background = Color(0xFF2A2D34),
                    backgroundDeep = Color(0xFF14161B),
                    surface = Color(0x33FAFBFF),
                    surfaceHigh = Color(0x55FAFBFF),
                    primary = Color(0xFFE0E6ED),
                    secondary = Color(0xFFB0BEC5),
                    accent = Color(0xFFECEFF1),
                    glow = Color(0xFFFFFFFF),
                    onBackground = Color(0xFFFAFBFF),
                    onSurface = Color(0xFFFAFBFF),
                    muted = Color(0xFFCFD8DC),
                    outline = Color(0xFF78909C),
                    danger = Color(0xFFFF8A80),
                ),
            motion = fluidMotion,
            material = opalineMaterial,
            sounds = StoneSounds(),
            surfaces = emptyMap(),
        )

    val azurite =
        ThemePack(
            slug = "azurite",
            name = "Azurite",
            stone = "azurite",
            isLight = false,
            palette =
                StonePalette(
                    background = Color(0xFF0D253A),
                    backgroundDeep = Color(0xFF05101A),
                    surface = Color(0x3300B4D8),
                    surfaceHigh = Color(0x5500B4D8),
                    primary = Color(0xFF48CAE4),
                    secondary = Color(0xFF90E0EF),
                    accent = Color(0xFFADE8F4),
                    glow = Color(0xFF8DDEF0),
                    onBackground = Color(0xFFF0F9FF),
                    onSurface = Color(0xFFF0F9FF),
                    muted = Color(0xFF94D2BD),
                    outline = Color(0xFF0077B6),
                    danger = Color(0xFFFF6B6B),
                ),
            motion = fluidMotion,
            material = opalineMaterial,
            sounds = StoneSounds(),
            surfaces = emptyMap(),
        )

    val firestone =
        ThemePack(
            slug = "firestone",
            name = "Firestone",
            stone = "firestone",
            isLight = false,
            palette =
                StonePalette(
                    background = Color(0xFF33140A),
                    backgroundDeep = Color(0xFF190703),
                    surface = Color(0x33FF6B35),
                    surfaceHigh = Color(0x55FF6B35),
                    primary = Color(0xFFFF8C42),
                    secondary = Color(0xFFFFA07A),
                    accent = Color(0xFFFFD166),
                    glow = Color(0xFFFF9E00),
                    onBackground = Color(0xFFFFF5F0),
                    onSurface = Color(0xFFFFF5F0),
                    muted = Color(0xFFF4A261),
                    outline = Color(0xFFD64000),
                    danger = Color(0xFFFF4D4D),
                ),
            motion = fluidMotion,
            material = opalineMaterial,
            sounds = StoneSounds(),
            surfaces = emptyMap(),
        )

    val kyanite =
        ThemePack(
            slug = "kyanite",
            name = "Kyanite",
            stone = "kyanite",
            isLight = false,
            palette =
                StonePalette(
                    background = Color(0xFF0F1F38),
                    backgroundDeep = Color(0xFF060D19),
                    surface = Color(0x334A90E2),
                    surfaceHigh = Color(0x554A90E2),
                    primary = Color(0xFF5C9CE6),
                    secondary = Color(0xFF82B1FF),
                    accent = Color(0xFFB388FF),
                    glow = Color(0xFF448AFF),
                    onBackground = Color(0xFFF0F6FF),
                    onSurface = Color(0xFFF0F6FF),
                    muted = Color(0xFF90CAF9),
                    outline = Color(0xFF1976D2),
                    danger = Color(0xFFFF5252),
                ),
            motion = fluidMotion,
            material = opalineMaterial,
            sounds = StoneSounds(),
            surfaces = emptyMap(),
        )

    val malachite =
        ThemePack(
            slug = "malachite",
            name = "Malachite",
            stone = "malachite",
            isLight = false,
            palette =
                StonePalette(
                    background = Color(0xFF0B291A),
                    backgroundDeep = Color(0xFF04140C),
                    surface = Color(0x332EC4B6),
                    surfaceHigh = Color(0x552EC4B6),
                    primary = Color(0xFF52B788),
                    secondary = Color(0xFF74C69D),
                    accent = Color(0xFFB7E4C7),
                    glow = Color(0xFFCEE6D4),
                    onBackground = Color(0xFFF0FFF4),
                    onSurface = Color(0xFFF0FFF4),
                    muted = Color(0xFF95D5B2),
                    outline = Color(0xFF1B4332),
                    danger = Color(0xFFFF6B6B),
                ),
            motion = fluidMotion,
            material = opalineMaterial,
            sounds = StoneSounds(),
            surfaces = emptyMap(),
        )

    val mookaite =
        ThemePack(
            slug = "mookaite",
            name = "Mookaite",
            stone = "mookaite",
            isLight = false,
            palette =
                StonePalette(
                    background = Color(0xFF2E1C14),
                    backgroundDeep = Color(0xFF170C08),
                    surface = Color(0x33E07A5F),
                    surfaceHigh = Color(0x55E07A5F),
                    primary = Color(0xFFDDA15E),
                    secondary = Color(0xFFBC6C25),
                    accent = Color(0xFFF4A261),
                    glow = Color(0xFFE7E5BA),
                    onBackground = Color(0xFFFFF8F0),
                    onSurface = Color(0xFFFFF8F0),
                    muted = Color(0xFFD4A373),
                    outline = Color(0xFF6F4E37),
                    danger = Color(0xFFFF5252),
                ),
            motion = fluidMotion,
            material = opalineMaterial,
            sounds = StoneSounds(),
            surfaces = emptyMap(),
        )

    val onyx =
        ThemePack(
            slug = "onyx",
            name = "Onyx",
            stone = "onyx",
            isLight = false,
            palette =
                StonePalette(
                    background = Color(0xFF121214),
                    backgroundDeep = Color(0xFF080809),
                    surface = Color(0x333F3F46),
                    surfaceHigh = Color(0x553F3F46),
                    primary = Color(0xFFA1A1AA),
                    secondary = Color(0xFF71717A),
                    accent = Color(0xFFE4E4E7),
                    glow = Color(0xFFD4D4D8),
                    onBackground = Color(0xFFFAFAFA),
                    onSurface = Color(0xFFFAFAFA),
                    muted = Color(0xFFA1A1AA),
                    outline = Color(0xFF52525B),
                    danger = Color(0xFFEF4444),
                ),
            motion = fluidMotion,
            material = opalineMaterial,
            sounds = StoneSounds(),
            surfaces = emptyMap(),
        )

    val all: List<ThemePack> =
        listOf(
            opalineWater,
            lapisLazuli,
            sugilite,
            amethyst,
            clearQuartz,
            azurite,
            firestone,
            kyanite,
            malachite,
            mookaite,
            onyx,
        )

    fun bySlug(slug: String?): ThemePack = all.firstOrNull { it.slug == slug } ?: all.first()
}
