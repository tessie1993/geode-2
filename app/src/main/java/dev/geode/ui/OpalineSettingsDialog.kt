package dev.geode.ui

import android.content.Intent
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.geode.R
import dev.geode.data.EXPORT_FPS_OPTIONS
import dev.geode.data.ExportDefaults
import dev.geode.data.ExportPrefsStore
import dev.geode.data.GeodePrefsFiles
import dev.geode.data.exportCodecLabel
import dev.geode.data.exportQualityLabel
import dev.geode.export.ExportAspect
import dev.geode.export.ExportCodec
import dev.geode.export.ExportPresets
import dev.geode.export.ExportQuality
import dev.geode.export.ExportRange
import dev.geode.export.ExportRatio
import dev.geode.export.LoudnessTarget
import dev.geode.ui.opaline.OpalineRangeSlider
import dev.geode.ui.opaline.creative.CreativeButton
import dev.geode.ui.opaline.creative.CreativeColors
import dev.geode.ui.opaline.creative.CreativeProgress
import dev.geode.ui.opaline.creative.CreativeSegments
import dev.geode.ui.opaline.creative.CreativeTabs

@Composable
fun SettingsDialog(
    export: ExportUiState,
    hasMedia: Boolean,
    takes: List<String>,
    selectedTake: String?,
    onSelectTake: (String?) -> Unit,
    bpm: Float,
    trackDurationMs: Long,
    onStart: (ExportAspect, Int, Boolean, ExportRange?, ExportCodec) -> Unit,
    onStartToDestination: (ExportAspect, Int, Boolean, ExportRange?, ExportCodec) -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
    stillPhase: StillPhase = StillPhase.Idle,
    onSaveFrame: (ExportAspect) -> Unit = {},
) {
    val context = LocalContext.current
    val exportPrefs = remember { ExportPrefsStore(GeodePrefsFiles(context).general) }
    val defaults = remember { exportPrefs.load() }
    var quality by rememberSaveable { mutableStateOf(defaults.quality) }
    var ratio by rememberSaveable { mutableStateOf(defaults.ratio) }
    var fps by rememberSaveable { mutableStateOf(defaults.fps) }
    var codec by rememberSaveable { mutableStateOf(defaults.codec) }
    // LoudnessTarget is a sealed interface, not a Saveable type on its own, so the persisted
    // choice is carried as its id and resolved back through LoudnessTarget.byId.
    var loudnessTargetId by rememberSaveable { mutableStateOf(defaults.loudnessTargetId) }
    val loudnessTarget = LoudnessTarget.byId(loudnessTargetId)
    var loopSafe by remember {
        mutableStateOf(
            defaults.loopSafe &&
                dev.geode.analysis.BarTrim
                    .barDurationUs(bpm) != null,
        )
    }
    var segment by rememberSaveable { mutableStateOf(false) }
    var rangeStart by remember { mutableFloatStateOf(0f) }
    var rangeEnd by remember { mutableFloatStateOf(1f) }
    val range =
        if (!segment) {
            null
        } else {
            ExportRange.of(
                startMs = (rangeStart * trackDurationMs).toLong(),
                endMs = (rangeEnd * trackDurationMs).toLong(),
                trackDurationMs = trackDurationMs,
            )
        }

    fun persistDefaults() = exportPrefs.save(ExportDefaults(quality, fps, ratio, loopSafe, codec, loudnessTargetId))
    val chooserTitle = stringResource(R.string.export_upload_share_to)

    Column(Modifier.fillMaxWidth()) {
        Text(
            stringResource(R.string.export_title),
            style = MaterialTheme.typography.titleLarge,
            color = CreativeColors.textPrimary,
        )
        Column(
            Modifier
                .padding(top = 16.dp)
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            when (val phase = export.phase) {
                is ExportPhase.Running -> {
                    val run by dev.geode.export.ExportRun.state
                        .collectAsStateWithLifecycle()
                    Text(
                        listOfNotNull(
                            stringResource(R.string.export_rendering_offline),
                            run.secondsRemaining?.let {
                                dev.geode.export.RenderEta
                                    .describe(it)
                            },
                        ).joinToString(" · "),
                    )
                    CreativeProgress(progress = phase.progress, modifier = Modifier.fillMaxWidth())
                    Text(
                        stringResource(R.string.export_leave_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                is ExportPhase.Done -> ExportDoneStatus(export, phase, chooserTitle)
                is ExportPhase.Failed -> {
                    Text(
                        stringResource(R.string.export_failed, phase.message),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                ExportPhase.Idle, ExportPhase.Loading -> {
                    SettingsLabel(stringResource(R.string.export_platform_preset))
                    CreativeSegments(
                        options = ExportPresets.ALL.map { it.name },
                        selected = ExportPresets.indexMatching(quality, ratio, fps, loopSafe),
                        onSelect = {
                            val preset = ExportPresets.ALL[it]
                            quality = preset.quality
                            ratio = preset.ratio
                            fps = preset.fps
                            loopSafe = preset.loopSafe
                            persistDefaults()
                        },
                    )
                    Text(
                        presetCaption(
                            ExportDefaults(quality, fps, ratio, loopSafe, codec, loudnessTargetId),
                            stringResource(
                                R.string.export_spec,
                                ratio.label,
                                exportQualityLabel(quality),
                                fps,
                            ),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    SettingsLabel(stringResource(R.string.export_quality))
                    CreativeSegments(
                        options = ExportQuality.entries.map { exportQualityLabel(it) },
                        selected = ExportQuality.entries.indexOf(quality),
                        onSelect = {
                            quality = ExportQuality.entries[it]
                            persistDefaults()
                        },
                    )
                    SettingsLabel(stringResource(R.string.export_frame_rate))
                    CreativeSegments(
                        options = fpsLabels(),
                        selected = EXPORT_FPS_OPTIONS.indexOf(fps),
                        onSelect = {
                            fps = EXPORT_FPS_OPTIONS[it]
                            persistDefaults()
                        },
                    )
                    SettingsLabel(stringResource(R.string.export_codec))
                    CreativeSegments(
                        options = ExportCodec.entries.map { exportCodecLabel(it) },
                        selected = ExportCodec.entries.indexOf(codec),
                        onSelect = {
                            codec = ExportCodec.entries[it]
                            persistDefaults()
                        },
                    )
                    SettingsLabel(stringResource(R.string.export_loudness_target))
                    CreativeSegments(
                        options = LoudnessTarget.ALL.map { it.label },
                        selected = LoudnessTarget.ALL.indexOf(loudnessTarget),
                        onSelect = {
                            loudnessTargetId = LoudnessTarget.ALL[it].id
                            persistDefaults()
                        },
                    )
                    Text(
                        stringResource(R.string.export_loudness_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    SettingsLabel(stringResource(R.string.export_aspect_ratio))
                    CreativeSegments(
                        options = ExportRatio.entries.map { it.label },
                        selected = ExportRatio.entries.indexOf(ratio),
                        onSelect = {
                            ratio = ExportRatio.entries[it]
                            persistDefaults()
                        },
                    )
                    if (trackDurationMs > 0) {
                        SettingsLabel(stringResource(R.string.export_length))
                        CreativeSegments(
                            options =
                                listOf(
                                    stringResource(R.string.export_whole_track),
                                    stringResource(R.string.export_segment),
                                ),
                            selected = if (segment) 1 else 0,
                            onSelect = { segment = it == 1 },
                        )
                        if (segment) {
                            OpalineRangeSlider(
                                value = rangeStart..rangeEnd,
                                onValueChange = { r ->
                                    rangeStart = r.start
                                    rangeEnd = r.endInclusive
                                },
                                modifier = Modifier.padding(top = 4.dp),
                            )
                            Text(
                                if (range == null) {
                                    stringResource(
                                        R.string.export_segment_hint,
                                        (ExportRange.MIN_DURATION_MS / 1000).toInt(),
                                    )
                                } else {
                                    stringResource(
                                        R.string.export_segment_summary,
                                        formatClock(range.startMs),
                                        formatClock(range.endMs),
                                        formatClock(range.durationMs),
                                    )
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    val barUs =
                        dev.geode.analysis.BarTrim
                            .barDurationUs(bpm)
                    SettingsLabel(stringResource(R.string.export_looping))
                    // Loop-safe needs a tempo: without one only the full length can be picked.
                    CreativeTabs(
                        titles =
                            listOf(
                                stringResource(R.string.export_full_length),
                                stringResource(R.string.export_loop_safe),
                            ),
                        selected = if (loopSafe) 1 else 0,
                        onSelect = {
                            loopSafe = it == 1
                            persistDefaults()
                        },
                        enabled = { it == 0 || barUs != null },
                    )
                    Text(
                        if (barUs != null) {
                            stringResource(
                                R.string.export_loop_safe_bar_hint,
                                "%.0f".format(bpm),
                                "%.1f".format(barUs / 1_000_000f),
                            )
                        } else {
                            stringResource(R.string.export_loop_safe_needs_tempo)
                        },
                        style = MaterialTheme.typography.labelSmall,
                    )
                    if (takes.isNotEmpty()) {
                        SettingsLabel(stringResource(R.string.export_group_performance))
                        CreativeSegments(
                            options = listOf(stringResource(R.string.export_live_settings)) + takes,
                            selected = selectedTake?.let { takes.indexOf(it) + 1 } ?: 0,
                            onSelect = { onSelectTake(takes.getOrNull(it - 1)) },
                        )
                        if (selectedTake != null) {
                            Text(
                                stringResource(R.string.export_take_explainer),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                    if (quality == ExportQuality.UHD4K) {
                        Text(
                            stringResource(R.string.export_4k_fallback),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    val aspect = ExportAspect.of(quality, ratio)
                    CreativeButton(
                        text =
                            stringResource(
                                R.string.export_render_button,
                                quality.shortSide,
                                ratio.label,
                                fps,
                            ),
                        onClick = { onStart(aspect, fps, loopSafe, range, codec) },
                        enabled = hasMedia,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    CreativeButton(
                        text = stringResource(R.string.export_render_to_folder),
                        onClick = { onStartToDestination(aspect, fps, loopSafe, range, codec) },
                        enabled = hasMedia,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    CreativeButton(
                        text = stringResource(R.string.export_still_button),
                        onClick = { onSaveFrame(aspect) },
                        enabled = hasMedia && !stillPhase.isBusy,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    StillPhaseStatus(stillPhase, chooserTitle)
                }
            }
        }
        Row(
            Modifier.padding(top = 20.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            if (export.phase.isRunning) {
                CreativeButton(text = stringResource(R.string.export_cancel), onClick = onCancel)
            } else {
                CreativeButton(text = stringResource(R.string.action_close), onClick = onDismiss)
            }
        }
    }
}

@Composable
private fun SettingsLabel(text: String) = Text(text, style = MaterialTheme.typography.labelMedium)

@Composable
private fun ExportDoneStatus(
    export: ExportUiState,
    phase: ExportPhase.Done,
    chooserTitle: String,
) {
    val context = LocalContext.current
    Text(
        stringResource(
            if (export.customDestination) {
                R.string.export_saved_folder
            } else {
                R.string.export_saved_library
            },
        ),
    )
    export.loudnessAdvice?.let { advice ->
        Text(advice.headline, style = MaterialTheme.typography.labelMedium)
        Text(
            advice.detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    // No track title reaches this dialog, so the rendered file's own
    // name (e.g. "geode_1234567890.mp4") stands in for EXTRA_TITLE/SUBJECT.
    val resultName = phase.resultUri.lastPathSegment?.substringAfterLast('/')
    CreativeButton(
        text = stringResource(R.string.export_upload_drive),
        onClick = {
            context.shareVideo(phase.resultUri, chooserTitle, resultName, resultName)
        },
    )
}

@Composable
private fun StillPhaseStatus(
    stillPhase: StillPhase,
    chooserTitle: String,
) {
    val context = LocalContext.current
    when (stillPhase) {
        StillPhase.Running ->
            Text(
                stringResource(R.string.export_still_saving),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        is StillPhase.Done -> {
            Text(
                stringResource(R.string.export_still_saved),
                style = MaterialTheme.typography.bodySmall,
            )
            CreativeButton(
                text = stringResource(R.string.export_upload_drive),
                onClick = {
                    val share =
                        Intent(Intent.ACTION_SEND).apply {
                            type = "image/png"
                            putExtra(Intent.EXTRA_STREAM, stillPhase.uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                    context.startActivity(Intent.createChooser(share, chooserTitle))
                },
            )
        }
        is StillPhase.Failed ->
            Text(
                stringResource(R.string.export_failed, stillPhase.message),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        StillPhase.Idle -> Unit
    }
}
