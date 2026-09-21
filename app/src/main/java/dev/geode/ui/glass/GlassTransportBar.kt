package dev.geode.ui.glass

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.geode.R

/**
 * The floating iridescent pearl playback cluster directly matching the approved mockup
 * in liquid_player_lib_1789776612307.jpg:
 * - Flanking opalescent pearl accent beads that bob on the water
 * - Volumetric spherical 3D previous/next glass pearl buttons (48dp)
 * - Large central luminous iridescent play/pause bubble (68dp) with radial specular gleam
 * - Fluid water coupling, spring press physics, and haptic feedback
 */
@Composable
fun GlassTransportBar(
    playing: Boolean,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
    onLibrary: (() -> Unit)? = null,
    onOpenQueue: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Flanking left accent bead or library button
        if (onLibrary != null) {
            GlassPearlSphereButton(
                icon = GlassIcons.MusicNote,
                contentDescription = stringResource(R.string.action_open_library),
                onClick = onLibrary,
                size = 36.dp,
                tint = GlassPalette.lavender,
            )
        } else {
            GlassPearlAccentBead(size = 30.dp, tint = GlassPalette.lavender)
        }

        // Previous track 3D pearl sphere
        GlassPearlSphereButton(
            icon = GlassIcons.Previous,
            contentDescription = stringResource(R.string.action_previous),
            onClick = onPrevious,
            size = 48.dp,
            tint = GlassPalette.cyan,
        )

        // Hero Play/Pause luminous 3D iridescent bubble
        GlassPearlSphereButton(
            icon = if (playing) GlassIcons.Pause else GlassIcons.Play,
            contentDescription = stringResource(if (playing) R.string.action_pause else R.string.action_play),
            onClick = onPlayPause,
            size = 68.dp,
            tint = GlassPalette.mint,
            glow = if (playing) 0.65f else 0.35f,
        )

        // Next track 3D pearl sphere
        GlassPearlSphereButton(
            icon = GlassIcons.Next,
            contentDescription = stringResource(R.string.action_next),
            onClick = onNext,
            size = 48.dp,
            tint = GlassPalette.cyan,
        )

        // Flanking right accent bead or profile button
        // Named for what it does. This parameter used to be called `onProfile` and was
        // labelled "Profile", while PlayerScreen wired it to onOpenQueuePanel - so a
        // screen-reader user was told they were opening a profile and got the play queue.
        // A confidently wrong label is worse than none: nothing suggests checking.
        if (onOpenQueue != null) {
            GlassPearlSphereButton(
                icon = GlassIcons.Profile,
                contentDescription = stringResource(R.string.action_open_queue),
                onClick = onOpenQueue,
                size = 36.dp,
                tint = GlassPalette.coral,
            )
        } else {
            GlassPearlAccentBead(size = 30.dp, tint = GlassPalette.coral)
        }
    }
}
