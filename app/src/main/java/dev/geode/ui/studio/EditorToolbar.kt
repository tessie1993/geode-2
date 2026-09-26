package dev.geode.ui.studio

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.geode.R
import dev.geode.editor.EditError
import dev.geode.editor.LaneKind
import dev.geode.editor.TapInSession
import dev.geode.ui.opaline.OpalineAlertDialog
import dev.geode.ui.opaline.OpalineChip
import dev.geode.ui.opaline.OpalineIconButton
import dev.geode.ui.opaline.creative.CreativeButton
import dev.geode.ui.opaline.creative.CreativeColors
import dev.geode.ui.opaline.creative.CreativeTextField
import dev.geode.ui.opaline.kit.OpalineFilterChipRow

/** Back, undo/redo and zoom as UI003 circular actions, the playhead, and export (UI001). */
@Composable
fun EditorHeader(
    canUndo: Boolean,
    canRedo: Boolean,
    playheadMs: Long,
    exporting: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onZoom: (Float) -> Unit,
    onExport: () -> Unit,
    onClose: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        OpalineIconButton(
            Icons.AutoMirrored.Filled.ArrowBack,
            stringResource(R.string.action_back),
            onClose,
        )
        Text(
            stringResource(R.string.editor_playhead, clockLabel(playheadMs)),
            style = MaterialTheme.typography.labelMedium,
            color = CreativeColors.textSecondary,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        OpalineIconButton(
            Icons.AutoMirrored.Filled.Undo,
            stringResource(R.string.editor_undo),
            onUndo,
            enabled = canUndo,
        )
        OpalineIconButton(
            Icons.AutoMirrored.Filled.Redo,
            stringResource(R.string.editor_redo),
            onRedo,
            enabled = canRedo,
        )
        OpalineIconButton(
            Icons.Filled.ZoomOut,
            stringResource(R.string.editor_zoom_out),
            { onZoom(1f / ZOOM_STEP) },
        )
        OpalineIconButton(
            Icons.Filled.ZoomIn,
            stringResource(R.string.editor_zoom_in),
            { onZoom(ZOOM_STEP) },
        )
        CreativeButton(
            text = stringResource(R.string.editor_export),
            enabled = !exporting,
            tint = CreativeColors.mint,
            onClick = onExport,
        )
    }
}

/** Lane creation, markers, auto-cut and captions as UI010 chips; in tap-in mode Tap is UI001. */
@Composable
fun EditorToolbar(
    tapSession: TapInSession?,
    onAddLane: (LaneKind) -> Unit,
    onAddMarker: () -> Unit,
    onTapStart: () -> Unit,
    onTap: () -> Unit,
    onTapUndo: () -> Unit,
    onTapDone: () -> Unit,
    onTapCancel: () -> Unit,
    onAutoCut: () -> Unit,
    hasLyrics: Boolean,
    onLyricCaptions: () -> Unit,
    onImportSrt: () -> Unit,
    onExportSrt: () -> Unit,
    onExportChapters: () -> Unit,
) {
    OpalineFilterChipRow(Modifier.fillMaxWidth()) {
        if (tapSession == null) {
            LANE_KINDS.forEach { (kind, label) ->
                ToolChip(stringResource(R.string.editor_add_lane, stringResource(label))) {
                    onAddLane(kind)
                }
            }
            ToolChip(stringResource(R.string.editor_add_marker), onClick = onAddMarker)
            ToolChip(stringResource(R.string.editor_tap_in), onClick = onTapStart)
            ToolChip(stringResource(R.string.editor_auto_cut), onClick = onAutoCut)
            if (hasLyrics) {
                ToolChip(stringResource(R.string.editor_lyric_captions), onClick = onLyricCaptions)
            }
            ToolChip(stringResource(R.string.editor_import_srt), onClick = onImportSrt)
            ToolChip(stringResource(R.string.editor_export_srt), onClick = onExportSrt)
            ToolChip(stringResource(R.string.editor_export_chapters), onClick = onExportChapters)
        } else {
            CreativeButton(
                text = stringResource(R.string.editor_tap),
                tint = CreativeColors.mint,
                onClick = onTap,
            )
            Text(
                stringResource(R.string.editor_tap_count, tapSession.count),
                style = MaterialTheme.typography.labelMedium,
                color = CreativeColors.textSecondary,
                modifier = Modifier.align(Alignment.CenterVertically),
            )
            ToolChip(
                stringResource(R.string.editor_tap_undo),
                enabled = tapSession.count > 0,
                onClick = onTapUndo,
            )
            ToolChip(stringResource(R.string.editor_tap_done), onClick = onTapDone)
            ToolChip(stringResource(R.string.action_cancel), onClick = onTapCancel)
        }
    }
}

/**
 * What can be done to the selected clip, marker or key: split, delete, ripple delete and
 * duplicate as UI003 circular actions, the rest as UI010 chips.
 */
@Composable
fun SelectionToolbar(
    clipSelected: Boolean,
    clipEnabled: Boolean,
    markerSelected: Boolean,
    keySelected: Boolean,
    canTransition: Boolean,
    onTransition: () -> Unit,
    onSplit: () -> Unit,
    onDelete: () -> Unit,
    onRippleDelete: () -> Unit,
    onDuplicate: () -> Unit,
    onToggleEnabled: () -> Unit,
    onDeleteMarker: () -> Unit,
    onDeleteKey: () -> Unit,
    onAnimateProgramme: () -> Unit,
    onAnimateClip: () -> Unit,
) {
    OpalineFilterChipRow(Modifier.fillMaxWidth()) {
        ToolChip(stringResource(R.string.curve_animate_scene), onClick = onAnimateProgramme)
        if (clipSelected) {
            ToolChip(stringResource(R.string.curve_animate_clip), onClick = onAnimateClip)
            if (canTransition) {
                ToolChip(
                    stringResource(R.string.editor_transition_ellipsis),
                    onClick = onTransition,
                )
            }
            OpalineIconButton(
                Icons.Filled.ContentCut,
                stringResource(R.string.editor_split),
                onSplit,
            )
            OpalineIconButton(
                Icons.Filled.Delete,
                stringResource(R.string.editor_delete),
                onDelete,
            )
            OpalineIconButton(
                Icons.Filled.DeleteSweep,
                stringResource(R.string.editor_ripple_delete),
                onRippleDelete,
            )
            OpalineIconButton(
                Icons.Filled.ContentCopy,
                stringResource(R.string.editor_duplicate),
                onDuplicate,
            )
            val toggle = if (clipEnabled) R.string.editor_disable else R.string.editor_enable
            ToolChip(stringResource(toggle), onClick = onToggleEnabled)
        }
        if (markerSelected) {
            ToolChip(stringResource(R.string.editor_delete_marker), onClick = onDeleteMarker)
        }
        if (keySelected) {
            ToolChip(stringResource(R.string.editor_delete_key), onClick = onDeleteKey)
        }
    }
}

/** UI010 action chip holding a text label. */
@Composable
private fun ToolChip(
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    OpalineChip(onClick = onClick, label = { Text(label) }, enabled = enabled)
}

/** UI042 dialog with a UI011 field for a new text clip. */
@Composable
fun TextClipDialog(
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    OpalineAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(R.string.editor_text_title),
                style = MaterialTheme.typography.titleLarge,
                color = CreativeColors.textPrimary,
            )
        },
        text = {
            CreativeTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            CreativeButton(
                text = stringResource(R.string.action_save),
                enabled = text.isNotBlank(),
                onClick = { onConfirm(text.trim()) },
            )
        },
        dismissButton = {
            CreativeButton(text = stringResource(R.string.action_cancel), onClick = onDismiss)
        },
    )
}

@Composable
fun editErrorMessage(error: EditError): String =
    when (error) {
        is EditError.LaneNotFound, is EditError.ClipNotFound -> stringResource(R.string.editor_err_not_found)
        is EditError.LaneLocked -> stringResource(R.string.editor_err_locked)
        is EditError.WrongLaneKind -> stringResource(R.string.editor_err_wrong_lane)
        is EditError.Overlaps -> stringResource(R.string.editor_err_overlaps)
        is EditError.NeedsSplit -> stringResource(R.string.editor_err_needs_split)
        EditError.TooShort -> stringResource(R.string.editor_err_too_short)
        EditError.OutsideClip -> stringResource(R.string.editor_err_outside_clip)
    }

fun laneKindLabel(kind: LaneKind): Int =
    when (kind) {
        LaneKind.Visual -> R.string.editor_lane_visual
        LaneKind.Media -> R.string.editor_lane_media
        LaneKind.Text -> R.string.editor_lane_text
        LaneKind.Overlay -> R.string.editor_lane_overlay
        LaneKind.Audio -> R.string.editor_lane_audio
    }

private val LANE_KINDS: List<Pair<LaneKind, Int>> =
    listOf(LaneKind.Visual, LaneKind.Media, LaneKind.Text, LaneKind.Overlay, LaneKind.Audio).map { it to laneKindLabel(it) }

private const val ZOOM_STEP = 1.5f
