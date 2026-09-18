package dev.geode.ui.theme

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp

@Composable
fun StoneSurfaceArt(
    component: StoneComponent,
    state: StoneState,
    modifier: Modifier = Modifier,
    reducedMotion: Boolean = false,
) {
    val pack = LocalThemePack.current
    val art = pack.surface(component)
    val motion = pack.motion

    if (art == null || art.default == 0) {
        val corner = when (component) {
            StoneComponent.CHIP, StoneComponent.COMPACT_BUTTON, StoneComponent.PRIMARY_BUTTON, StoneComponent.SECONDARY_BUTTON -> 24.dp
            StoneComponent.ICON_BUTTON, StoneComponent.KNOB, StoneComponent.PROGRESS_RING, StoneComponent.TOGGLE, StoneComponent.SLIDER_THUMB -> 50.dp
            StoneComponent.BOTTOM_SHEET -> 32.dp
            else -> 20.dp
        }
        val shape = RoundedCornerShape(corner)
        val alpha = when (state) {
            StoneState.PRESSED -> 0.45f
            StoneState.FOCUSED, StoneState.SELECTED -> 0.38f
            StoneState.DISABLED -> 0.12f
            StoneState.DEFAULT -> 0.28f
        }
        val fillBrush = Brush.linearGradient(
            colors = listOf(
                Color.White.copy(alpha = alpha),
                pack.palette.primary.copy(alpha = alpha * 0.7f),
                pack.palette.accent.copy(alpha = alpha * 0.5f),
            )
        )
        val rimBrush = Brush.linearGradient(
            colors = listOf(
                Color.White.copy(alpha = 0.75f),
                pack.palette.glow.copy(alpha = 0.5f),
                Color.White.copy(alpha = 0.35f),
            )
        )
        Box(
            modifier = modifier
                .clip(shape)
                .background(fillBrush)
                .border(1.dp, rimBrush, shape)
        )
        return
    }

    @Composable
    fun fade(target: StoneState): Float {
        val visible = state == target
        val durationMs =
            when {
                reducedMotion -> motion.reduceMotionCrossfadeMs
                target == StoneState.PRESSED || state == StoneState.PRESSED -> motion.pressDurationMs
                target == StoneState.FOCUSED || state == StoneState.FOCUSED -> motion.focusDurationMs
                else -> motion.selectedDurationMs
            }
        val alpha by animateFloatAsState(
            targetValue = if (visible) 1f else 0f,
            animationSpec = tween(durationMs),
            label = "stone-state-$target",
        )
        return alpha
    }

    Box(modifier = modifier) {
        Image(
            painter = painterResource(art.default),
            contentDescription = null,
            contentScale = ContentScale.FillBounds,
            modifier = Modifier.matchParentSize(),
        )
        for (s in listOf(StoneState.FOCUSED, StoneState.SELECTED, StoneState.PRESSED, StoneState.DISABLED)) {
            val alpha = fade(s)
            if (alpha > 0.01f) {
                Image(
                    painter = painterResource(art.forState(s)),
                    contentDescription = null,
                    contentScale = ContentScale.FillBounds,
                    alpha = alpha,
                    modifier = Modifier.matchParentSize(),
                )
            }
        }
    }
}

@Composable
fun Modifier.stonePress(
    interaction: InteractionSource,
    reducedMotion: Boolean = false,
): Modifier {
    val motion = LocalThemePack.current.motion
    val pressed by interaction.collectIsPressedAsState()
    val view = LocalView.current
    LaunchedEffect(pressed) {
        if (pressed) view.performStoneHaptic(StoneHapticCue.TAP)
    }
    val scale by animateFloatAsState(
        targetValue = if (pressed && !reducedMotion) motion.pressScale else 1f,
        animationSpec =
            if (pressed) {
                tween(motion.pressDurationMs)
            } else {
                spring(dampingRatio = 0.78f, stiffness = 380f)
            },
        label = "stone-press",
    )
    return scale(scale)
}

@Composable
fun rememberStoneState(
    interaction: MutableInteractionSource,
    enabled: Boolean = true,
    selected: Boolean = false,
): StoneState {
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    return stoneStateOf(enabled = enabled, pressed = pressed, selected = selected, focused = focused)
}

@Composable
fun rememberStoneInteraction(): MutableInteractionSource = remember { MutableInteractionSource() }
