package dev.geode.ui.opaline

import kotlin.math.pow

/**
 * The material families of ui-system/Opaline-3D-Library/src/materials.js with their exact
 * MeshPhysicalMaterial parameters and opaline.js surface uniforms. A GLB names only the family
 * of each piece; its colours and optics come from here for whichever of the six themes is active,
 * exactly as `createMaterials(theme)` builds them. Colours are authored sRGB hex values, and the
 * defaults are MeshPhysicalMaterial's own.
 */
internal data class OpalineMaterial(
    val color: Int,
    val roughness: Float,
    val ior: Float = 1.5f,
    val transmission: Float = 0f,
    val thickness: Float = 0f,
    val attenuationColor: Int = 0xFFFFFF,
    /** three.js Infinity: no absorption along the refracted path. */
    val attenuationDistance: Float = Float.POSITIVE_INFINITY,
    val clearcoat: Float = 0f,
    val clearcoatRoughness: Float = 0f,
    val iridescence: Float = 0f,
    val iridescenceIor: Float = 1.3f,
    val iridescenceThickness: ClosedFloatingPointRange<Float> = 100f..400f,
    val dispersion: Float = 0f,
    val sheen: Float = 0f,
    val sheenColor: Int = 0x000000,
    val sheenRoughness: Float = 1f,
    val emissive: Int = 0x000000,
    val emissiveIntensity: Float = 1f,
    val metalness: Float = 0f,
    val doubleSided: Boolean = false,
    val cloud: Float = 0f,
    val flow: Float = 0f,
    val grain: Float = 0f,
    val film: Float = 0f,
)

/** `THEMES` in src/materials.js. */
internal enum class OpalineMaterialTheme(
    val gel: Int,
    val blue: Int,
    val deep: Int,
    val accent: Int,
    val water: Int,
    val stone: Int,
    val leaf: Int,
    val background: Int,
) {
    TIDAL(0x99CEDD, 0x448DC1, 0x285F80, 0xA8FFF1, 0x5BAAAA, 0x526D78, 0x467C66, 0x122B3C),
    OPAL(0xF1E5F3, 0x9DAEDE, 0xC99FC6, 0xFFF0C9, 0x95BDC9, 0xA8A8B4, 0x77978C, 0x252C44),
    MOSS(0xCEE7CB, 0x77B8A3, 0x426C55, 0xD9F8A7, 0x739E8A, 0x5E7265, 0x7FA665, 0x162F28),
    OBSIDIAN(0x788296, 0x657CA8, 0x27384C, 0xA3DCF0, 0x405C70, 0x303A47, 0x476477, 0x0B111D),
    AURORA(0xD0CEF5, 0x8DA5EF, 0x8371BD, 0xA0FFE0, 0x668BA9, 0x616C86, 0x829C99, 0x171E39),
    AMBER(0xF0D7AF, 0xD4AB7C, 0xB77744, 0xFFE4A3, 0x92B5A4, 0x877668, 0x8B976A, 0x302C2A),
    ;

    /** `createMaterials(theme)[family]`; the Opal theme uses the pearl gel and blue variants. */
    fun material(family: String): OpalineMaterial {
        val pearl = this == OPAL
        return when (family) {
            "blue" ->
                OpalineMaterial(
                    color = blue,
                    roughness = if (pearl) .17f else .12f,
                    ior = 1.4f,
                    transmission = if (pearl) .65f else .78f,
                    thickness = .85f,
                    attenuationColor = blue,
                    attenuationDistance = 1.1f,
                    clearcoat = .9f,
                    clearcoatRoughness = .07f,
                    iridescence = .12f,
                    dispersion = .11f,
                    cloud = if (pearl) .36f else .14f,
                    flow = .23f,
                    grain = .018f,
                )
            "water" ->
                OpalineMaterial(
                    color = 0xE1F8FA,
                    roughness = .045f,
                    ior = 1.333f,
                    transmission = .97f,
                    thickness = 1.4f,
                    attenuationColor = water,
                    attenuationDistance = 4.5f,
                    clearcoat = .35f,
                    clearcoatRoughness = .035f,
                    dispersion = .045f,
                )
            "shell" ->
                OpalineMaterial(
                    color = 0xF2FBFF,
                    roughness = .065f,
                    ior = 1.46f,
                    transmission = 1f,
                    thickness = .085f,
                    attenuationColor = gel,
                    attenuationDistance = 8f,
                    clearcoat = 1f,
                    clearcoatRoughness = .045f,
                    dispersion = .13f,
                    grain = .01f,
                )
            "pigment" ->
                OpalineMaterial(
                    color = blue,
                    roughness = .2f,
                    ior = 1.37f,
                    transmission = .38f,
                    thickness = .55f,
                    attenuationColor = deep,
                    attenuationDistance = .8f,
                    clearcoat = .6f,
                    clearcoatRoughness = .13f,
                    cloud = .78f,
                    flow = 1.1f,
                    grain = .022f,
                )
            "film" ->
                OpalineMaterial(
                    color = 0xF8FDFF,
                    roughness = .035f,
                    ior = 1.333f,
                    transmission = 1f,
                    thickness = .0008f,
                    iridescence = 1f,
                    iridescenceIor = 1.333f,
                    iridescenceThickness = 130f..590f,
                    doubleSided = true,
                    clearcoat = 1f,
                    clearcoatRoughness = .025f,
                    cloud = .015f,
                    flow = .2f,
                    film = 1f,
                )
            "nacre" ->
                OpalineMaterial(
                    color = gel,
                    roughness = .24f,
                    ior = 1.52f,
                    transmission = .27f,
                    thickness = .45f,
                    attenuationColor = gel,
                    attenuationDistance = 1.1f,
                    iridescence = .72f,
                    iridescenceIor = 1.36f,
                    iridescenceThickness = 170f..450f,
                    clearcoat = .8f,
                    clearcoatRoughness = .12f,
                    cloud = .27f,
                    flow = .07f,
                    grain = .042f,
                    film = .4f,
                )
            "stone" ->
                OpalineMaterial(
                    color = stone,
                    roughness = .69f,
                    clearcoat = .74f,
                    clearcoatRoughness = .14f,
                    cloud = .25f,
                    grain = .25f,
                )
            "leaf" ->
                OpalineMaterial(
                    color = leaf,
                    roughness = .52f,
                    ior = 1.35f,
                    transmission = .2f,
                    thickness = .015f,
                    attenuationColor = leaf,
                    attenuationDistance = .1f,
                    doubleSided = true,
                    sheen = .15f,
                    sheenColor = gel,
                    sheenRoughness = .8f,
                    clearcoat = .15f,
                    clearcoatRoughness = .3f,
                    cloud = .23f,
                    grain = .12f,
                )
            "glow" ->
                OpalineMaterial(
                    color = accent,
                    roughness = .2f,
                    ior = 1.37f,
                    transmission = .35f,
                    thickness = .15f,
                    emissive = accent,
                    emissiveIntensity = 2.4f,
                    clearcoat = 1f,
                    clearcoatRoughness = .08f,
                    cloud = .18f,
                    flow = .5f,
                )
            else ->
                OpalineMaterial(
                    color = gel,
                    roughness = if (pearl) .24f else .15f,
                    ior = 1.39f,
                    transmission = if (pearl) .54f else .74f,
                    thickness = .65f,
                    attenuationColor = gel,
                    attenuationDistance = 1.25f,
                    clearcoat = .8f,
                    clearcoatRoughness = if (pearl) .13f else .085f,
                    iridescence = .16f,
                    iridescenceIor = 1.29f,
                    iridescenceThickness = 210f..390f,
                    dispersion = .10f,
                    cloud = if (pearl) .42f else .18f,
                    flow = .34f,
                    grain = .025f,
                )
        }
    }

    companion object {
        /** The `createMaterials(theme)` keys: the families a GLB piece or a selector may name. */
        val FAMILIES =
            setOf(
                "gel",
                "blue",
                "water",
                "shell",
                "pigment",
                "film",
                "nacre",
                "stone",
                "leaf",
                "glow",
            )

        fun of(palette: OpalinePalette): OpalineMaterialTheme = valueOf(palette.name)
    }
}

/** sRGB hex to linear RGB, as three.js `Color.set(hex)` stores it under colour management. */
internal fun linearRgb(color: Int): FloatArray =
    FloatArray(3) { channel ->
        val value = (color shr (16 - channel * 8) and 0xFF) / 255f
        if (value <= 0.04045f) {
            value * 0.0773993808f
        } else {
            (value * 0.9478672986f + 0.0521327014f).pow(2.4f)
        }
    }
