#include "viz/VisualSafety.hpp"

#include <algorithm>
#include <cmath>

namespace geode::viz {

const char* transitionStyleId(TransitionStyle style) {
    switch (style) {
        case TransitionStyle::Cut: return "cut";
        case TransitionStyle::Fade: return "fade";
        case TransitionStyle::Melt: return "melt";
        case TransitionStyle::Slide: return "slide";
        case TransitionStyle::Zoom: return "zoom";
    }
    return "fade";
}

BlendMode blendModeFromOrdinal(int i) {
    return (i >= 0 && i <= static_cast<int>(BlendMode::Darken)) ? static_cast<BlendMode>(i) : BlendMode::Screen;
}

namespace {
inline float safeClamp(float v, float lo, float hi) {
    return std::isfinite(v) ? std::clamp(v, lo, hi) : lo;
}
}  // namespace

namespace safety {

SceneParams apply(const SceneParams& p, bool reducedMotion) {
    SceneParams out = p;
    out.strobe = safeClamp(p.strobe, 0.0f, kMaxFlashDepth / kStrobeShaderDepth);
    out.flash = safeClamp(p.flash, 0.0f, kMaxFlashDepth / kFlashShaderDepth);
    out.glitch = safeClamp(p.glitch, 0.0f, kMaxFlashDepth);
    out.bloom = safeClamp(p.bloom, 0.0f, kMaxFlashDepth);
    out.brightness = safeClamp(p.brightness, 0.0f, 1.0f + kMaxFlashDepth);
    out.intensity = safeClamp(p.intensity, 0.0f, 1.0f + kMaxFlashDepth);
    out.contrast = safeClamp(p.contrast, 0.0f, 1.0f + kMaxFlashDepth);
    if (reducedMotion) {
        out.speed *= kReducedMotionScale;
        out.shake *= kReducedMotionScale;
        out.sway *= kReducedMotionScale;
        out.driftX *= kReducedMotionScale;
        out.driftY *= kReducedMotionScale;
        out.rotation *= kReducedMotionScale;
        out.turbulence *= kReducedMotionScale;
        out.pulse *= kReducedMotionScale;
        out.cycleSpeed *= kReducedMotionScale;
        out.endlessZoomSpeed *= kReducedMotionScale;
    }
    return out;
}

float limitLfoRate(float rateHz, LfoTarget target) {
    return isLuminanceTarget(target) ? std::min(rateHz, kWcagFlashesPerSecond) : rateHz;
}

float beatMinIntervalMs(float requestedMs) { return std::max(requestedMs, 1000.0f / kWcagFlashesPerSecond); }

TransitionStyle transitionStyle(TransitionStyle requested) {
    return requested == TransitionStyle::Cut ? TransitionStyle::Fade : requested;
}

std::string transitionId(const std::string& requested) {
    return requested == transitionStyleId(TransitionStyle::Cut) ? transitionStyleId(TransitionStyle::Fade) : requested;
}

float layerMix(float requested, BlendMode mode) {
    const float mix = std::clamp(requested, 0.0f, 1.0f);
    return (mode == BlendMode::Difference || mode == BlendMode::Add) ? std::min(mix, kMaxFlashDepth) : mix;
}

}  // namespace safety

}  // namespace geode::viz
