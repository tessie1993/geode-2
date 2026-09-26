package dev.geode.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.geode.R
import dev.geode.ui.opaline.OpalineButton
import dev.geode.ui.opaline.creative.CreativeSegments
import dev.geode.ui.opaline.creative.CreativeSlider
import dev.geode.ui.opaline.creative.CreativeToggle
import dev.geode.viz.ArtTitleOptions
import dev.geode.viz.LyricOptions
import dev.geode.viz.LyricPosition
import dev.geode.viz.LyricSize
import dev.geode.viz.OverlayPosition
import dev.geode.viz.OverlaySize
import dev.geode.viz.WatermarkCorner
import dev.geode.viz.WatermarkOptions

private fun positionLabel(position: OverlayPosition) =
    when (position) {
        OverlayPosition.BOTTOM_LEFT -> R.string.overlay_position_bottom_left
        OverlayPosition.BOTTOM_CENTER -> R.string.overlay_position_bottom_center
        OverlayPosition.TOP_LEFT -> R.string.overlay_position_top_left
        OverlayPosition.TOP_RIGHT -> R.string.overlay_position_top_right
    }

private fun sizeLabel(size: OverlaySize) =
    when (size) {
        OverlaySize.SMALL -> R.string.overlay_size_small
        OverlaySize.MEDIUM -> R.string.overlay_size_medium
        OverlaySize.LARGE -> R.string.overlay_size_large
    }

private fun watermarkCornerLabel(corner: WatermarkCorner) =
    when (corner) {
        WatermarkCorner.TOP_LEFT -> R.string.overlay_watermark_corner_top_left
        WatermarkCorner.TOP_RIGHT -> R.string.overlay_watermark_corner_top_right
        WatermarkCorner.BOTTOM_LEFT -> R.string.overlay_watermark_corner_bottom_left
        WatermarkCorner.BOTTOM_RIGHT -> R.string.overlay_watermark_corner_bottom_right
    }

private fun lyricPositionLabel(position: LyricPosition) =
    when (position) {
        LyricPosition.TOP -> R.string.overlay_lyric_position_top
        LyricPosition.CENTER -> R.string.overlay_lyric_position_center
        LyricPosition.BOTTOM -> R.string.overlay_lyric_position_bottom
    }

private fun lyricSizeLabel(size: LyricSize) =
    when (size) {
        LyricSize.SMALL -> R.string.overlay_size_small
        LyricSize.MEDIUM -> R.string.overlay_size_medium
        LyricSize.LARGE -> R.string.overlay_size_large
    }

/**
 * Toggles and choices for the cover-art/title, synced-lyric, and watermark overlays drawn into
 * the visualizer and its exports: the route sheet (UI044), UI008 switches, UI009/UI035 choices,
 * UI001 actions and UI019 sliders.
 */
@Composable
internal fun LayersSheet(
    options: ArtTitleOptions,
    onOptionsChange: (ArtTitleOptions) -> Unit,
    watermarkOptions: WatermarkOptions,
    onWatermarkOptionsChange: (WatermarkOptions) -> Unit,
    onPickWatermarkImage: (Uri) -> Unit,
    onClearWatermarkImage: () -> Unit,
    lyricOptions: LyricOptions,
    onLyricOptionsChange: (LyricOptions) -> Unit,
    onDismiss: () -> Unit,
) {
    OpalineContextSheet(onDismiss = onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.overlay_sheet_title), style = MaterialTheme.typography.titleMedium)

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.overlay_enable))
                CreativeToggle(checked = options.enabled, onCheckedChange = { onOptionsChange(options.copy(enabled = it)) })
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.overlay_show_artwork))
                CreativeToggle(
                    checked = options.showArtwork,
                    onCheckedChange = { onOptionsChange(options.copy(showArtwork = it)) },
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.overlay_show_text))
                CreativeToggle(checked = options.showText, onCheckedChange = { onOptionsChange(options.copy(showText = it)) })
            }

            Text(stringResource(R.string.overlay_position_label), style = MaterialTheme.typography.labelLarge)
            CreativeSegments(
                OverlayPosition.entries.map { stringResource(positionLabel(it)) },
                options.position.ordinal,
                { onOptionsChange(options.copy(position = OverlayPosition.entries[it])) },
            )

            Text(stringResource(R.string.overlay_size_label), style = MaterialTheme.typography.labelLarge)
            CreativeSegments(
                OverlaySize.entries.map { stringResource(sizeLabel(it)) },
                options.size.ordinal,
                { onOptionsChange(options.copy(size = OverlaySize.entries[it])) },
            )

            Text(stringResource(R.string.overlay_lyric_sheet_title), style = MaterialTheme.typography.titleMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.overlay_lyric_enable))
                CreativeToggle(
                    checked = lyricOptions.enabled,
                    onCheckedChange = { onLyricOptionsChange(lyricOptions.copy(enabled = it)) },
                )
            }

            Text(stringResource(R.string.overlay_position_label), style = MaterialTheme.typography.labelLarge)
            CreativeSegments(
                LyricPosition.entries.map { stringResource(lyricPositionLabel(it)) },
                lyricOptions.position.ordinal,
                { onLyricOptionsChange(lyricOptions.copy(position = LyricPosition.entries[it])) },
            )

            Text(stringResource(R.string.overlay_size_label), style = MaterialTheme.typography.labelLarge)
            CreativeSegments(
                LyricSize.entries.map { stringResource(lyricSizeLabel(it)) },
                lyricOptions.size.ordinal,
                { onLyricOptionsChange(lyricOptions.copy(size = LyricSize.entries[it])) },
            )

            WatermarkSection(watermarkOptions, onWatermarkOptionsChange, onPickWatermarkImage, onClearWatermarkImage)
        }
    }
}

/** Logo/watermark row group: pick or clear an image, then its corner, size and opacity. */
@Composable
private fun WatermarkSection(
    options: WatermarkOptions,
    onOptionsChange: (WatermarkOptions) -> Unit,
    onPickImage: (Uri) -> Unit,
    onClearImage: () -> Unit,
) {
    val context = LocalContext.current
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                runCatching {
                    context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                onPickImage(uri)
            }
        }

    Text(stringResource(R.string.overlay_watermark_section), style = MaterialTheme.typography.titleMedium)

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OpalineButton(
            stringResource(R.string.overlay_watermark_pick),
            { picker.launch(arrayOf("image/*")) },
        )
        if (options.uri != null) {
            OpalineButton(stringResource(R.string.overlay_watermark_clear), onClearImage)
        }
    }
    if (options.uri == null) {
        Text(
            stringResource(R.string.overlay_watermark_none),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(stringResource(R.string.overlay_watermark_enable))
        CreativeToggle(
            checked = options.enabled,
            enabled = options.uri != null,
            onCheckedChange = { onOptionsChange(options.copy(enabled = it)) },
        )
    }

    Text(
        stringResource(R.string.overlay_watermark_corner_label),
        style = MaterialTheme.typography.labelLarge,
    )
    CreativeSegments(
        WatermarkCorner.entries.map { stringResource(watermarkCornerLabel(it)) },
        options.corner.ordinal,
        { onOptionsChange(options.copy(corner = WatermarkCorner.entries[it])) },
    )

    Column {
        Text(
            stringResource(R.string.overlay_watermark_size_label, (options.sizeFraction * 100).toInt()),
            style = MaterialTheme.typography.labelLarge,
        )
        CreativeSlider(
            value = options.sizeFraction,
            onValueChange = { onOptionsChange(options.copy(sizeFraction = it)) },
            valueRange = WatermarkOptions.MIN_SIZE_FRACTION..WatermarkOptions.MAX_SIZE_FRACTION,
        )
    }

    Column {
        Text(
            stringResource(R.string.overlay_watermark_opacity_label, (options.opacity * 100).toInt()),
            style = MaterialTheme.typography.labelLarge,
        )
        CreativeSlider(
            value = options.opacity,
            onValueChange = { onOptionsChange(options.copy(opacity = it)) },
            valueRange = WatermarkOptions.MIN_OPACITY..WatermarkOptions.MAX_OPACITY,
        )
    }
}
