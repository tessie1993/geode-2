package dev.geode.ui

import android.app.Activity
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.geode.R
import dev.geode.data.RuleField
import dev.geode.data.RuleOp
import dev.geode.data.SmartPlaylist
import dev.geode.data.SmartRule
import dev.geode.data.TagWriteOutcome
import dev.geode.nav.Destination
import dev.geode.nav.Navigator
import dev.geode.nav.Overlay
import dev.geode.ui.opaline.OpalineAlertDialog
import dev.geode.ui.opaline.OpalineButton
import dev.geode.ui.opaline.OpalineColors
import dev.geode.ui.opaline.OpalineEmptyState
import dev.geode.ui.opaline.OpalineIconButton
import dev.geode.ui.opaline.OpalinePage
import dev.geode.ui.opaline.OpalineRow
import dev.geode.ui.opaline.OpalineTextField
import dev.geode.ui.opaline.kit.OpalineAccordionStack
import dev.geode.ui.opaline.kit.OpalineImageFrame
import dev.geode.ui.opaline.kit.OpalineNumberStepper
import dev.geode.ui.opaline.kit.OpalineReorderRow
import dev.geode.ui.opaline.kit.OpalineSearchField
import kotlinx.coroutines.delay

@Composable
internal fun OpalineSearch(
    navigator: Navigator,
    player: PlayerViewModel,
) {
    val library: LibraryViewModel = geodeViewModel()
    val tracks = rememberOpalineTracks(library)
    val viz by player.vizState.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var debounced by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        library.refreshDeviceTracks()
        focus.requestFocus()
    }
    LaunchedEffect(query) {
        delay(160)
        debounced = query
    }
    val results = remember(tracks, debounced) { if (debounced.isBlank()) emptyList() else LibraryBrowse.search(tracks, debounced) }
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        OpalinePage(stringResource(R.string.action_search), stringResource(R.string.opaline_search_subtitle), onBack = {
            navigator.close(Overlay.Search)
        }) {
            OpalineSearchField(
                query,
                { query = it },
                Modifier.fillMaxWidth().focusRequester(focus),
                placeholder = stringResource(R.string.opaline_search_hint),
            )
            LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (query.isBlank()) {
                    item {
                        OpalineEmptyState(
                            stringResource(R.string.opaline_search_title),
                            stringResource(R.string.opaline_search_body),
                        )
                    }
                }
                items(results, key = { it.uri }) { track ->
                    OpalineRow(track.title, track.artist, onClick = {
                        player.playFrom(results.toQueue(), track.uri)
                        navigator.close(Overlay.Search)
                    }, leading = { TrackArtwork(track.uri, Modifier.size(42.dp)) })
                }
                if (debounced.isNotBlank()) {
                    val presets = viz.presets.filter { it.name.contains(debounced, true) }
                    items(presets, key = { "preset:${it.name}" }) { preset ->
                        OpalineRow(preset.name, stringResource(R.string.opaline_visual_preset), onClick = {
                            player.applyPreset(preset)
                            navigator.close(Overlay.Search)
                            navigator.open(Overlay.Visualizer)
                        })
                    }
                    if (results.isEmpty() &&
                        presets.isEmpty()
                    ) {
                        item {
                            OpalineEmptyState(
                                stringResource(R.string.library_no_results),
                                stringResource(R.string.opaline_search_retry),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun OpalineTrackRoute(
    destination: Destination.Shared,
    navigator: Navigator,
) {
    when (destination) {
        is Destination.Shared.TrackInfo -> OpalineTrackInfo(destination.uri, navigator)
        is Destination.Shared.AddToPlaylist ->
            OpalineMusicSheet(
                { navigator.back() },
            ) { OpalineAddToPlaylist(destination.uri, navigator) }
    }
}

@Composable
private fun OpalineAddToPlaylist(
    uri: String,
    navigator: Navigator,
) {
    val library: LibraryViewModel = geodeViewModel()
    val state by library.library.collectAsStateWithLifecycle()
    var name by rememberSaveable { mutableStateOf("") }
    OpalinePage(stringResource(R.string.opaline_add_playlist), onBack = { navigator.back() }) {
        OpalineTextField(name, {
            name = it
        }, singleLine = true, modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.opaline_playlist_name)) })
        OpalineButton(stringResource(R.string.action_create), {
            library.createMusicPlaylist(name.trim(), listOf(uri))
            navigator.back()
        }, enabled = name.isNotBlank() && state.playlists.none { it.name.equals(name.trim(), true) })
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(state.playlists, key = { it.name }) { playlist ->
                OpalineRow(playlist.name, stringResource(R.string.opaline_tracks_count, playlist.trackUris.size), onClick = {
                    library.addTrackToPlaylist(playlist.name, uri)
                    navigator.back()
                })
            }
        }
    }
}

@Composable
private fun OpalineTrackInfo(
    uri: String,
    navigator: Navigator,
) {
    val library: LibraryViewModel = geodeViewModel()
    val resources = LocalResources.current
    val context = LocalContext.current
    var track by remember(uri) { mutableStateOf<LibraryTrack?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var writing by remember { mutableStateOf(false) }
    LaunchedEffect(uri) { track = library.trackInfoFor(uri) }
    val retry =
        rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) writing = true
        }
    LaunchedEffect(writing) {
        val edit = track
        if (!writing || edit == null) return@LaunchedEffect
        val result = library.writeTrackInfo(uri, edit.title, edit.artist, edit.album, edit.genre, edit.year, edit.trackNo, edit.comment)
        writing = false
        if (result == TagWriteOutcome.NeedsConsent && Build.VERSION.SDK_INT >= 30 && uri.startsWith("content://media/")) {
            val request = MediaStore.createWriteRequest(context.contentResolver, listOf(Uri.parse(uri)))
            retry.launch(IntentSenderRequest.Builder(request.intentSender).build())
        } else {
            notice =
                resources.getString(if (result == TagWriteOutcome.Written) R.string.opaline_tags_written else R.string.opaline_tags_failed)
        }
    }
    OpalinePage(stringResource(R.string.opaline_track_info), onBack = { navigator.back() }) {
        val edit = track
        if (edit != null) {
            LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    OpalineImageFrame(Modifier.size(96.dp)) {
                        TrackArtwork(uri, Modifier.fillMaxSize())
                    }
                }
                item { OpalineEditField(stringResource(R.string.opaline_title), edit.title) { track = edit.copy(title = it) } }
                item { OpalineEditField(stringResource(R.string.opaline_artist), edit.artist) { track = edit.copy(artist = it) } }
                item { OpalineEditField(stringResource(R.string.opaline_album), edit.album) { track = edit.copy(album = it) } }
                item { OpalineEditField(stringResource(R.string.opaline_genre), edit.genre) { track = edit.copy(genre = it) } }
                item {
                    OpalineNumberField(stringResource(R.string.opaline_year), edit.year) {
                        track = edit.copy(year = it)
                    }
                }
                item {
                    OpalineNumberField(
                        stringResource(R.string.opaline_track_number),
                        edit.trackNo,
                    ) { track = edit.copy(trackNo = it) }
                }
                item { OpalineEditField(stringResource(R.string.opaline_comment), edit.comment) { track = edit.copy(comment = it) } }
                item { Text(uri, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                item {
                    OpalineButton(stringResource(R.string.opaline_save_library), {
                        library.saveTrackInfo(uri, edit.title, edit.artist, edit.album, edit.genre, edit.year, edit.trackNo, edit.comment)
                        notice = resources.getString(R.string.opaline_saved)
                    }, enabled = !writing)
                }
                item {
                    OpalineButton(stringResource(if (writing) R.string.opaline_writing else R.string.opaline_write_tags), {
                        writing = true
                    }, enabled = !writing)
                }
                notice?.let { item { Text(it) } }
            }
        }
    }
}

@Composable
private fun OpalineEditField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
) {
    OpalineTextField(
        value,
        onValueChange,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
    )
}

/** UI016 Number stepper under its [label]; 0 (below the range) is the unset tag, shown blank. */
@Composable
private fun OpalineNumberField(
    label: String,
    value: Int,
    onValueChange: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = OpalineColors.muted)
        OpalineNumberStepper(value, onValueChange, label, 1..Int.MAX_VALUE, Modifier.fillMaxWidth())
    }
}

@Composable
internal fun OpalinePlaylistDetail(
    id: String,
    library: LibraryViewModel,
    navigator: Navigator,
) {
    val state by library.library.collectAsStateWithLifecycle()
    val playlist = state.playlists.firstOrNull { it.name == id }
    var renaming by rememberSaveable { mutableStateOf(false) }
    var deleting by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable(id) { mutableStateOf(id) }
    val tracks = rememberOpalineTracks(library)
    OpalinePage(id, onBack = { navigator.back() }, actions = {
        OpalineIconButton(Icons.Default.Edit, stringResource(R.string.action_rename), { renaming = true })
        OpalineIconButton(Icons.Default.DeleteOutline, stringResource(R.string.action_delete), { deleting = true })
    }) {
        if (playlist == null) {
            OpalineEmptyState(stringResource(R.string.playlist_none_yet), stringResource(R.string.opaline_playlist_hint))
        } else {
            OpalineButton(stringResource(R.string.library_play_all), {
                library.playPlaylist(id)
            }, icon = Icons.Default.PlayArrow, enabled = playlist.trackUris.isNotEmpty())
            // Stable per-occurrence keys, so a dragged row keeps its gesture as it moves.
            val keys =
                remember(playlist.trackUris) {
                    val seen = mutableMapOf<String, Int>()
                    playlist.trackUris.map { uri ->
                        val n = seen[uri] ?: 0
                        seen[uri] = n + 1
                        "$uri#$n"
                    }
                }
            LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(playlist.trackUris, key = { index, _ -> keys[index] }) { index, uri ->
                    val track = tracks.firstOrNull { it.uri == uri }
                    val artist = track?.artist.orEmpty()
                    OpalineReorderRow({ rows ->
                        val to = (index + rows).coerceIn(0, playlist.trackUris.lastIndex)
                        if (to != index) library.moveMusicPlaylistTrack(id, index, to)
                    }) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable(role = Role.Button) { library.playPlaylist(id, index) },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    track?.title ?: stringResource(R.string.title_untitled),
                                    color = OpalineColors.text,
                                    style = MaterialTheme.typography.titleSmall,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (artist.isNotBlank()) {
                                    Text(
                                        artist,
                                        color = OpalineColors.muted,
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            OpalineIconButton(Icons.Default.ArrowUpward, stringResource(R.string.action_up), {
                                library.moveMusicPlaylistTrack(
                                    id,
                                    index,
                                    index - 1,
                                )
                            }, enabled = index > 0)
                            OpalineIconButton(Icons.Default.ArrowDownward, stringResource(R.string.action_down), {
                                library.moveMusicPlaylistTrack(
                                    id,
                                    index,
                                    index + 1,
                                )
                            }, enabled = index < playlist.trackUris.lastIndex)
                            OpalineIconButton(
                                Icons.Default.Close,
                                stringResource(R.string.action_remove_from_playlist),
                                { library.removeTrackFromPlaylist(id, uri) },
                            )
                        }
                    }
                }
            }
        }
    }
    if (renaming) {
        OpalineAlertDialog(onDismissRequest = { renaming = false }, title = { Text(stringResource(R.string.action_rename)) }, text = {
            OpalineEditField(stringResource(R.string.opaline_playlist_name), name) { name = it }
        }, confirmButton = {
            OpalineButton(stringResource(R.string.action_save), {
                library.renameMusicPlaylist(id, name.trim())
                navigator.replace(Destination.Library.Playlist(name.trim()))
                renaming = false
            }, enabled = name.isNotBlank() && state.playlists.none { it.name != id && it.name.equals(name.trim(), true) })
        }, dismissButton = {
            OpalineButton(stringResource(R.string.action_cancel), {
                renaming =
                    false
            })
        })
    }
    if (deleting) {
        OpalineAlertDialog(onDismissRequest = {
            deleting = false
        }, title = {
            Text(stringResource(R.string.playlist_delete_title))
        }, text = { Text(stringResource(R.string.playlist_delete_body, id)) }, confirmButton = {
            OpalineButton(stringResource(R.string.action_delete), {
                library.deleteMusicPlaylist(id)
                deleting = false
                navigator.back()
            })
        }, dismissButton = { OpalineButton(stringResource(R.string.action_cancel), { deleting = false }) })
    }
}

@Composable
internal fun OpalineSmartPlaylist(
    id: String?,
    library: LibraryViewModel,
    navigator: Navigator,
) {
    val state by library.library.collectAsStateWithLifecycle()
    val original = state.smartPlaylists.firstOrNull { it.name == id }
    var draft by remember(id, original) {
        mutableStateOf(
            original ?: SmartPlaylist("", listOf(SmartRule(RuleField.ARTIST, RuleOp.CONTAINS, ""))),
        )
    }
    var deleting by remember { mutableStateOf(false) }
    var collapsed by remember { mutableStateOf(emptySet<Int>()) }
    val valid = draft.name.isNotBlank() && state.smartPlaylists.none { it.name != id && it.name.equals(draft.name.trim(), true) }
    OpalinePage(stringResource(if (id == null) R.string.smart_new else R.string.smart_edit), onBack = { navigator.back() }) {
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { OpalineEditField(stringResource(R.string.opaline_playlist_name), draft.name) { draft = draft.copy(name = it) } }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OpalineButton(
                        stringResource(R.string.smart_match_all),
                        { draft = draft.copy(matchAll = true) },
                        selected = draft.matchAll,
                    )
                    OpalineButton(
                        stringResource(R.string.smart_match_any),
                        { draft = draft.copy(matchAll = false) },
                        selected = !draft.matchAll,
                    )
                }
            }
            item {
                OpalineAccordionStack(
                    draft.rules.map {
                        "${ruleLabel(it.field.name)} ${ruleLabel(it.op.name)} ${it.value}".trim()
                    },
                    { it !in collapsed },
                    { collapsed = if (it in collapsed) collapsed - it else collapsed + it },
                ) { index ->
                    OpalineSmartRule(
                        draft.rules[index],
                        { changed ->
                            val rules = draft.rules.toMutableList().also { it[index] = changed }
                            draft = draft.copy(rules = rules)
                        },
                        {
                            val rules = draft.rules.filterIndexed { i, _ -> i != index }
                            draft = draft.copy(rules = rules)
                            // Rules carry no id: shift the collapsed indices past the removed one.
                            collapsed =
                                collapsed
                                    .filter { it != index }
                                    .map { if (it > index) it - 1 else it }
                                    .toSet()
                        },
                    )
                }
            }
            item {
                OpalineButton(stringResource(R.string.smart_add_rule), {
                    draft =
                        draft.copy(rules = draft.rules + SmartRule(RuleField.ARTIST, RuleOp.CONTAINS, ""))
                }, icon = Icons.Default.Add)
            }
            item {
                OpalineEditField(
                    stringResource(R.string.smart_limit),
                    draft.limit
                        .takeIf { it > 0 }
                        ?.toString()
                        .orEmpty(),
                ) {
                    draft =
                        draft.copy(limit = it.filter(Char::isDigit).toIntOrNull() ?: 0)
                }
            }
            item {
                OpalineButton(stringResource(R.string.action_save), {
                    if (id != null && id != draft.name.trim()) library.deleteSmartPlaylist(id)
                    library.saveSmartPlaylist(draft.copy(name = draft.name.trim()))
                    navigator.back()
                }, enabled = valid)
            }
            if (id != null) item { OpalineButton(stringResource(R.string.action_delete), { deleting = true }) }
        }
    }
    if (deleting) {
        OpalineAlertDialog(onDismissRequest = {
            deleting = false
        }, title = { Text(stringResource(R.string.action_delete)) }, text = { Text(draft.name) }, confirmButton = {
            OpalineButton(stringResource(R.string.action_delete), {
                id?.let(library::deleteSmartPlaylist)
                navigator.back()
            })
        }, dismissButton = { OpalineButton(stringResource(R.string.action_cancel), { deleting = false }) })
    }
}

@Composable
private fun OpalineSmartRule(
    rule: SmartRule,
    onChange: (SmartRule) -> Unit,
    onRemove: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            RuleField.entries.forEach { field ->
                OpalineButton(ruleLabel(field.name), {
                    onChange(rule.copy(field = field, op = if (field.isText) RuleOp.CONTAINS else RuleOp.IS))
                }, selected = rule.field == field)
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            RuleOp.entries
                .filter {
                    if (rule.field.isText) {
                        it.forText
                    } else if (rule.field.isFlag) {
                        it == RuleOp.IS || it == RuleOp.IS_NOT
                    } else {
                        it.forNumber
                    }
                }.forEach { op ->
                    OpalineButton(
                        ruleLabel(op.name),
                        { onChange(rule.copy(op = op)) },
                        selected = rule.op == op,
                    )
                }
        }
        if (!rule.field.isFlag) {
            OpalineEditField(
                stringResource(R.string.opaline_rule_value),
                rule.value,
            ) { onChange(rule.copy(value = it)) }
        }
        OpalineButton(stringResource(R.string.action_remove), onRemove)
    }
}

private fun ruleLabel(name: String): String = name.lowercase().replace('_', ' ')
