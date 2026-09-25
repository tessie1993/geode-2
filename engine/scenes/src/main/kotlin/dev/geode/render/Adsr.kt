package dev.geode.render

enum class EnvBand(
    val label: String,
) {
    BASS("Bass"),
    MID("Mid"),
    TREBLE("Treble"),
    RMS("Level"),
    BRIGHTNESS("Brightness"),
    WIDTH("Stereo width"),
}

data class AdsrConfig(
    val enabled: Boolean = false,
    val targets: List<LfoTarget> = emptyList(),
    val attack: Float = 0.05f,
    val decay: Float = 0.25f,
    val sustain: Float = 0.5f,
    val release: Float = 0.35f,
    val amount: Float = 0.5f,
    val band: EnvBand = EnvBand.BASS,
    val gateThreshold: Float = 0.25f,
    val sustainTrack: Boolean = false,
    val retrigger: Boolean = true,
)

// Modulation is applied natively every frame (see core/viz/RendererFrame.cpp, which ticks its own
// LfoEngine/AdsrEngine against the configs pushed by geode_viz_set_lfo/geode_viz_set_adsr), so this
// class only needs to hold the config the UI edits and NativeViz.setAdsrConfigs forwards; it does not
// run the envelope/apply logic itself.
class AdsrEngine {
    @Volatile
    var configs: List<AdsrConfig> = List(COUNT) { AdsrConfig() }

    companion object {
        const val COUNT = 2
    }
}
