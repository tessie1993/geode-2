package dev.geode.ui

import android.Manifest
import android.app.Activity
import android.media.projection.MediaProjectionManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.geode.R
import dev.geode.audio.PlaybackCaptureService
import dev.geode.nav.Destination
import dev.geode.nav.Navigator
import dev.geode.nav.Overlay
import dev.geode.nav.Section
import dev.geode.ui.opaline.OpalineButton
import dev.geode.ui.opaline.OpalineEmptyState
import dev.geode.ui.opaline.OpalineIconButton
import dev.geode.ui.opaline.OpalinePage
import dev.geode.ui.opaline.OpalinePanel
import dev.geode.ui.opaline.OpalineRow
import dev.geode.ui.opaline.OpalineSceneHost
import dev.geode.ui.opaline.OpalineTextField
import dev.geode.ui.opaline.kit.OpalineBubbleHero
import dev.geode.ui.opaline.kit.OpalineDropHero
import dev.geode.ui.opaline.kit.OpalineGlassHero
import dev.geode.ui.opaline.kit.OpalineLiquidProgressChannel
import dev.geode.ui.opaline.kit.OpalineReorderRow
import dev.geode.ui.opaline.kit.OpalineSearchField
import dev.geode.ui.opaline.kit.OpalineStyle
import dev.geode.ui.opaline.kit.OpalineToast
import dev.geode.ui.opaline.kit.OpalineTransport

@Composable
internal fun OpalinePlayerRoute(
    destination: Destination.Player,
    navigator: Navigator,
    player: PlayerViewModel,
) {
    when (destination) {
        Destination.Player.NowPlaying -> OpalineNowPlaying(player, navigator)
        Destination.Player.Queue ->
            OpalineMusicSheet({ navigator.back() }) { OpalineQueue(player, navigator) }
        Destination.Player.SleepTimer ->
            OpalineMusicSheet({ navigator.back() }) { OpalineSleepTimer(player, navigator) }
        Destination.Player.Lyrics -> OpalineLyrics(player, navigator)
    }
}

@Composable
internal fun OpalineMusicSheet(
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    // The shell owns the scrim, bounds, and backdrop dismissal for sheet routes.
    OpalineContextSheet(onDismiss) { Box(Modifier.fillMaxWidth().height(580.dp)) { content() } }
}

/** UI044 body of every route sheet, in the same Compose window as the shell's scrim. */
@Composable
internal fun OpalineContextSheet(
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    OpalineSceneHost(Modifier.fillMaxWidth(), environment = false, transparent = true) {
        OpalinePanel(Modifier.fillMaxWidth(), recipe = "UI044") {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                OpalineIconButton(
                    Icons.Default.Close,
                    stringResource(R.string.action_close),
                    onDismiss,
                )
            }
            content()
        }
    }
}

@Composable
private fun OpalineNowPlaying(
    player: PlayerViewModel,
    navigator: Navigator,
) {
    val settings: SettingsViewModel = geodeViewModel()
    val gui by settings.guiPrefs.collectAsStateWithLifecycle()
    val state by player.uiState.collectAsStateWithLifecycle()
    val queue by player.queue.collectAsStateWithLifecycle()
    val favourites by player.favourites.collectAsStateWithLifecycle()
    val external by player.externalAudio.collectAsStateWithLifecycle()
    val mic by player.micState.collectAsStateWithLifecycle()
    val notice by player.playbackNotice.collectAsStateWithLifecycle()
    val waveform by player.waveform.collectAsStateWithLifecycle()
    val loop by player.abLoop.collectAsStateWithLifecycle()
    val auto by player.autoMode.collectAsStateWithLifecycle()
    val style =
        OpalineStyle.entries.firstOrNull { it.name == gui.opalineStyle } ?: OpalineStyle.GLASS
    val uri = queue.tracks.getOrNull(queue.index)?.uri
    val progress =
        if (state.durationMs > 0) {
            (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f)
        } else {
            0f
        }
    val seekDescription =
        stringResource(
            R.string.seek_description,
            formatClock(state.positionMs),
            formatClock(state.durationMs),
        )
    val seek: (Float) -> Unit = { if (state.hasMedia && state.durationMs > 0) player.seekTo(it) }
    val openVisualizer: () -> Unit = { navigator.open(Overlay.Visualizer) }
    val title =
        when {
            external.active ->
                external.nowPlaying?.title ?: stringResource(R.string.source_other_apps)
            mic.active -> stringResource(R.string.source_live_input)
            else -> state.title ?: stringResource(R.string.opaline_ready_title)
        }
    val artist =
        when {
            external.active -> external.nowPlaying?.artist.orEmpty()
            mic.active -> stringResource(R.string.opaline_room_subtitle)
            else -> state.artist ?: stringResource(R.string.opaline_ready_body)
        }
    val live = state.isPlaying || mic.active || external.active
    val artwork: @Composable () -> Unit = {
        TrackArtwork(if (external.active || mic.active) null else uri, Modifier.fillMaxSize())
    }
    OpalinePage(
        title = stringResource(R.string.opaline_listen),
        subtitle = stringResource(R.string.opaline_listen_subtitle),
    ) {
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            item {
                when (style) {
                    OpalineStyle.GLASS ->
                        OpalineGlassHero(
                            title,
                            artist,
                            live,
                            openVisualizer,
                            Modifier.fillMaxWidth(),
                            artwork,
                        )
                    OpalineStyle.BUBBLE ->
                        OpalineBubbleHero(
                            title,
                            artist,
                            live,
                            openVisualizer,
                            Modifier.fillMaxWidth(),
                            artwork,
                        )
                    OpalineStyle.DROP ->
                        OpalineDropHero(
                            title,
                            artist,
                            live,
                            progress,
                            seekDescription,
                            seek,
                            openVisualizer,
                            Modifier.fillMaxWidth(),
                            artwork,
                        )
                }
            }
            notice?.let { message ->
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // The library defines no toast duration: the notice stays until dismissed.
                        OpalineToast(
                            message,
                            Long.MAX_VALUE,
                            player::clearPlaybackNotice,
                            Modifier.weight(1f),
                        )
                        OpalineIconButton(
                            Icons.Default.Close,
                            stringResource(R.string.action_dismiss),
                            player::clearPlaybackNotice,
                        )
                    }
                }
            }
            item {
                OpalinePanel {
                    OpalineWaveform(waveform, progress)
                    // The Drop hero carries the channel itself.
                    if (style != OpalineStyle.DROP) {
                        OpalineLiquidProgressChannel(progress, seekDescription, seek)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(
                            formatClock(state.positionMs),
                            style = MaterialTheme.typography.labelSmall,
                        )
                        Text(
                            formatClock(state.durationMs),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    OpalineTransport(
                        playing = state.isPlaying,
                        onPlayPause = player::togglePlayPause,
                        onPrevious = player::previous,
                        onNext = player::next,
                        shuffle = state.shuffle,
                        onShuffle = player::toggleShuffle,
                        repeatMode = state.repeatMode,
                        onRepeat = player::cycleRepeatMode,
                        abLabel =
                            stringResource(
                                when {
                                    loop == null -> R.string.ab_loop_idle
                                    loop?.endMs == null -> R.string.ab_loop_set_b
                                    else -> R.string.ab_loop_looping
                                },
                            ),
                        abActive = loop != null,
                        onAbLoop = player::cycleAbLoop,
                        favourite = uri in favourites,
                        onFavourite = { if (uri != null) player.toggleFavourite() },
                        enabled = state.hasMedia,
                    )
                    OpalineButton(
                        stringResource(
                            when (auto) {
                                1 -> R.string.auto_random
                                2 -> R.string.auto_smart
                                3 -> R.string.auto_sections
                                else -> R.string.auto_off
                            },
                        ),
                        player::cycleAutoMode,
                        icon = Icons.Default.GraphicEq,
                    )
                }
            }
            item {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OpalineButton(
                        stringResource(R.string.opaline_visualize),
                        openVisualizer,
                        icon = Icons.Default.AutoAwesome,
                    )
                    OpalineButton(
                        stringResource(R.string.queue),
                        { navigator.go(Destination.Player.Queue) },
                        icon = Icons.Default.QueueMusic,
                    )
                    OpalineButton(
                        stringResource(R.string.opaline_lyrics),
                        { navigator.go(Destination.Player.Lyrics) },
                        icon = Icons.Default.Lyrics,
                    )
                    OpalineButton(
                        stringResource(R.string.opaline_sleep),
                        { navigator.go(Destination.Player.SleepTimer) },
                        icon = Icons.Default.Bedtime,
                    )
                }
            }
            if (!state.hasMedia) {
                item {
                    OpalineEmptyState(
                        stringResource(R.string.state_nothing_playing),
                        stringResource(R.string.subtitle_nothing_playing),
                    ) {
                        OpalineButton(
                            stringResource(R.string.action_open_library),
                            { navigator.show(Section.LIBRARY) },
                            icon = Icons.Default.LibraryMusic,
                        )
                        OpalineImportActions()
                    }
                }
            }
            item { OpalineInputControls(player) }
            if (queue.tracks.size > queue.index + 1) {
                item {
                    Text(
                        stringResource(R.string.queue_up_next),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                itemsIndexed(queue.tracks.drop(queue.index + 1).take(3)) { index, track ->
                    OpalineRow(
                        track.title.ifBlank { stringResource(R.string.title_untitled) },
                        track.artist,
                        onClick = { player.playQueueIndex(queue.index + index + 1) },
                        leading = { TrackArtwork(track.uri, Modifier.size(42.dp)) },
                    )
                }
            }
            item { Spacer(Modifier.height(20.dp)) }
        }
    }
}

@Composable
private fun OpalineWaveform(
    waveform: FloatArray?,
    progress: Float,
) {
    val accent = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
    Canvas(Modifier.fillMaxWidth().height(34.dp)) {
        val bars = 72
        repeat(bars) { i ->
            val amplitude =
                waveform
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { it[i * it.size / bars].coerceIn(0.06f, 1f) }
                    ?: 0.1f
            val x = (i + 0.5f) * size.width / bars
            drawLine(
                if (i.toFloat() / bars <= progress) accent else muted,
                Offset(x, size.height * (1f - amplitude) / 2),
                Offset(x, size.height * (1f + amplitude) / 2),
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }
    }
}

@Composable
internal fun OpalineInputControls(player: PlayerViewModel) {
    val mic by player.micState.collectAsStateWithLifecycle()
    val external by player.externalAudio.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val micPermission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            if (it) player.setMicEnabled(true)
        }
    val projection =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            val data = it.data
            if (it.resultCode == Activity.RESULT_OK && data != null) {
                PlaybackCaptureService.start(context, it.resultCode, data)
            } else {
                player.noteExternalAudioConsentDenied()
            }
        }
    val permissions =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
        ) { granted ->
            if (granted[Manifest.permission.RECORD_AUDIO] == true) {
                player.noteExternalAudioConsentPending()
                projection.launch(
                    context
                        .getSystemService(MediaProjectionManager::class.java)
                        .createScreenCaptureIntent(),
                )
            } else {
                player.noteExternalAudioConsentDenied()
            }
        }
    OpalinePanel {
        Text(
            stringResource(R.string.opaline_audio_source),
            style = MaterialTheme.typography.titleSmall,
        )
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OpalineButton(
                stringResource(R.string.source_live_input),
                {
                    if (mic.active) {
                        player.setMicEnabled(false)
                    } else if (player.hasMicPermission()) {
                        player.setMicEnabled(true)
                    } else {
                        micPermission.launch(Manifest.permission.RECORD_AUDIO)
                    }
                },
                icon = Icons.Default.Mic,
                selected = mic.active,
            )
            if (external.supported) {
                OpalineButton(
                    stringResource(R.string.source_other_apps),
                    {
                        if (external.active) {
                            player.stopExternalAudio()
                        } else {
                            permissions.launch(
                                if (Build.VERSION.SDK_INT >= 33) {
                                    arrayOf(
                                        Manifest.permission.RECORD_AUDIO,
                                        Manifest.permission.POST_NOTIFICATIONS,
                                    )
                                } else {
                                    arrayOf(Manifest.permission.RECORD_AUDIO)
                                },
                            )
                        }
                    },
                    icon = Icons.Default.Cast,
                    selected = external.active,
                )
            }
        }
        if (external.refusedByApp) {
            Text(
                stringResource(R.string.subtitle_capture_refused, external.refusingApp.orEmpty()),
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun OpalineQueue(
    player: PlayerViewModel,
    navigator: Navigator,
) {
    val queue by player.queue.collectAsStateWithLifecycle()
    val library: LibraryViewModel = geodeViewModel()
    val libraryState by library.library.collectAsStateWithLifecycle()
    var save by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf("") }
    // Stable per-entry keys (uri + occurrence) so a dragged UI070 row stays under the finger.
    val keys =
        remember(queue.tracks) {
            val seen = HashMap<String, Int>()
            queue.tracks.map { track ->
                val occurrence = seen[track.uri] ?: 0
                seen[track.uri] = occurrence + 1
                "${track.uri}#$occurrence"
            }
        }
    val shown =
        queue.tracks.withIndex().filter { (_, track) ->
            track.title.contains(filter, ignoreCase = true) ||
                track.artist.contains(filter, ignoreCase = true)
        }
    OpalinePage(
        stringResource(R.string.queue),
        onBack = { navigator.back() },
        actions = {
            OpalineIconButton(
                Icons.Default.Add,
                stringResource(R.string.opaline_save_playlist),
                { save = !save },
                enabled = queue.tracks.isNotEmpty(),
            )
        },
    ) {
        if (save) {
            OpalineTextField(
                name,
                { name = it },
                label = { Text(stringResource(R.string.opaline_playlist_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OpalineButton(
                stringResource(R.string.action_save),
                {
                    library.createMusicPlaylist(name.trim(), queue.tracks.map { it.uri })
                    save = false
                },
                enabled =
                    name.isNotBlank() &&
                        libraryState.playlists.none { it.name.equals(name.trim(), true) },
            )
        }
        OpalineSearchField(
            filter,
            { filter = it },
            Modifier.fillMaxWidth(),
            placeholder = stringResource(R.string.search_hint),
        )
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (queue.tracks.isEmpty()) {
                item {
                    OpalineEmptyState(
                        stringResource(R.string.opaline_queue_empty),
                        stringResource(R.string.opaline_queue_empty_body),
                    )
                }
            }
            items(shown, key = { keys[it.index] }) { (index, track) ->
                OpalineReorderRow(
                    onMove = { rows ->
                        player.moveQueueItem(
                            index,
                            (index + rows).coerceIn(0, queue.tracks.lastIndex),
                        )
                    },
                ) {
                    OpalineQueueRow(player, track, index, queue.index, queue.tracks.lastIndex)
                }
            }
        }
    }
}

/** UI057 queue row; up/down stay as the accessible alternative to dragging the UI070 grip. */
@Composable
private fun OpalineQueueRow(
    player: PlayerViewModel,
    track: QueueTrack,
    index: Int,
    current: Int,
    last: Int,
) {
    OpalineRow(
        track.title.ifBlank { stringResource(R.string.title_untitled) },
        track.artist,
        onClick = { player.playQueueIndex(index) },
        leading = { TrackArtwork(track.uri, Modifier.size(38.dp)) },
        trailing = {
            Row {
                OpalineIconButton(
                    Icons.Default.ArrowUpward,
                    stringResource(R.string.action_up),
                    { player.moveQueueItem(index, index - 1) },
                    enabled = index > 0,
                )
                OpalineIconButton(
                    Icons.Default.ArrowDownward,
                    stringResource(R.string.action_down),
                    { player.moveQueueItem(index, index + 1) },
                    enabled = index < last,
                )
                OpalineIconButton(
                    Icons.Default.Close,
                    stringResource(R.string.action_remove),
                    { player.removeQueueItem(index) },
                )
            }
        },
        selected = index == current,
    )
}

@Composable
private fun OpalineSleepTimer(
    player: PlayerViewModel,
    navigator: Navigator,
) {
    val remaining by player.sleepTimerRemainingMs.collectAsStateWithLifecycle()
    OpalinePage(stringResource(R.string.opaline_sleep), onBack = { navigator.back() }) {
        Text(
            remaining?.let(::formatClock) ?: stringResource(R.string.opaline_sleep_off),
            style = MaterialTheme.typography.displaySmall,
        )
        listOf(15, 30, 45, 60, 90).forEach { minutes ->
            OpalineRow(
                stringResource(R.string.opaline_minutes, minutes),
                onClick = {
                    player.startSleepTimer(minutes)
                    navigator.back()
                },
            )
        }
        OpalineButton(
            stringResource(R.string.action_cancel),
            {
                player.cancelSleepTimer()
                navigator.back()
            },
            enabled = remaining != null,
        )
    }
}

@Composable
private fun OpalineLyrics(
    player: PlayerViewModel,
    navigator: Navigator,
) {
    val lyrics by player.lyrics.collectAsStateWithLifecycle()
    val state by player.uiState.collectAsStateWithLifecycle()
    val current = lyrics?.indexAt(state.positionMs) ?: -1
    val listState = rememberLazyListState()
    LaunchedEffect(current) {
        if (current >= 0 && !listState.isScrollInProgress) listState.animateScrollToItem(current)
    }
    OpalinePage(
        stringResource(R.string.opaline_lyrics),
        state.title.orEmpty(),
        onBack = { navigator.back() },
    ) {
        val sheet = lyrics
        if (sheet == null) {
            OpalineEmptyState(
                stringResource(R.string.opaline_lyrics_empty),
                stringResource(R.string.opaline_lyrics_empty_body),
            )
        } else {
            OpalinePanel {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    itemsIndexed(sheet.lines) { index, line ->
                        Text(
                            line.text,
                            style =
                                if (index == current) {
                                    MaterialTheme.typography.headlineMedium
                                } else {
                                    MaterialTheme.typography.titleLarge
                                },
                            color =
                                if (index == current) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = sheet.synced) {
                                        player.seekToMs(line.timeMs)
                                    }.padding(vertical = 10.dp),
                        )
                    }
                }
            }
        }
    }
}

internal fun formatClock(ms: Long): String {
    val seconds = (ms / 1000).coerceAtLeast(0)
    return if (seconds >= 3600) {
        "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
    } else {
        "%d:%02d".format(seconds / 60, seconds % 60)
    }
}
