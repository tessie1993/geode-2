package dev.geode.ui.glass

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View

enum class GlassHapticCue(
    val constant: Int,
) {
    TAP(HapticFeedbackConstants.KEYBOARD_TAP),
    TOGGLE(HapticFeedbackConstants.VIRTUAL_KEY),
    SLIDER_TICK(HapticFeedbackConstants.CLOCK_TICK),
    LONG_PRESS(HapticFeedbackConstants.LONG_PRESS),
    CONFIRM(HapticFeedbackConstants.CONFIRM),
    REJECT(HapticFeedbackConstants.REJECT),
}

fun View.performGlassHaptic(cue: GlassHapticCue) {
    if (!isHapticFeedbackEnabled) return
    val constant =
        when (cue) {
            GlassHapticCue.CONFIRM ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    HapticFeedbackConstants.CONFIRM
                } else {
                    HapticFeedbackConstants.KEYBOARD_TAP
                }
            GlassHapticCue.REJECT ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    HapticFeedbackConstants.REJECT
                } else {
                    HapticFeedbackConstants.LONG_PRESS
                }
            else -> cue.constant
        }
    performHapticFeedback(constant, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING)
}
