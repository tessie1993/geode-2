package dev.geode.ui

import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.LibraryAdd
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.geode.R
import dev.geode.data.TemplateImport
import dev.geode.data.TemplateWrite
import dev.geode.data.VideoTemplate
import dev.geode.render.VisualizerView
import dev.geode.ui.opaline.OpalineAction
import dev.geode.ui.opaline.OpalineIconAction
import dev.geode.ui.opaline.OpalineRow
import dev.geode.ui.opaline.OpalineTextField
import dev.geode.ui.opaline.creative.CreativeButton

/**
 * Save-load-share for video templates, opened next to the preset library it mirrors
 * (see [PresetsTreeTab]'s entry point). Reuses [PresetLink]'s wire format through
 * TemplateFormat, so nothing here re-implements parsing or the bounded-inflate guard.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TemplatesSheet(
    viewModel: PlayerViewModel,
    visualizerView: VisualizerView,
    onDismiss: () -> Unit,
) {
    val visualsViewModel: VisualsViewModel = geodeViewModel()
    val viz by viewModel.vizState.collectAsStateWithLifecycle()
    val library by visualsViewModel.templates.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // Resolved here rather than inside the click lambda: lint (LocalContextGetResourceValueCall)
    // rejects Context.getString through LocalContext because it is not configuration-aware.
    val clipboardEmptyNote = stringResource(R.string.template_clipboard_empty)
    var note by remember { mutableStateOf<String?>(null) }
    var saveName by remember { mutableStateOf("") }
    var deleting by remember { mutableStateOf<VideoTemplate?>(null) }

    val filePicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                visualsViewModel.importTemplateFile(uri) { outcome -> note = messageFor(outcome) }
            }
        }

    if (deleting == null) {
        OpalineContextSheet(onDismiss = onDismiss) {
            LazyColumn(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    Text(stringResource(R.string.template_sheet_title), style = MaterialTheme.typography.titleMedium)
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CreativeButton(compact = true, filled = false, onClick = {
                            val pasted = clipboardText(context)
                            if (pasted.isNullOrBlank()) {
                                note = clipboardEmptyNote
                            } else {
                                visualsViewModel.importTemplateText(pasted) { outcome -> note = messageFor(outcome) }
                            }
                        }) { Text(stringResource(R.string.template_paste_link)) }
                        CreativeButton(compact = true, filled = false, onClick = {
                            filePicker.launch(arrayOf("*/*"))
                        }) { Text(stringResource(R.string.template_open_file)) }
                    }
                }
                note?.let { n ->
                    item {
                        Text(
                            n,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                item {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OpalineTextField(
                            value = saveName,
                            onValueChange = { saveName = it },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text(stringResource(R.string.template_save_placeholder)) },
                            singleLine = true,
                        )
                        CreativeButton(onClick = {
                            val trimmed = saveName.trim()
                            if (trimmed.isNotEmpty()) {
                                val shader = visualizerView.visualizerRenderer.customShaderFor(viz.sceneId)
                                visualsViewModel.saveCurrentAsTemplate(trimmed, shader) { result ->
                                    note =
                                        when (result) {
                                            TemplateWrite.Written -> "Saved \"$trimmed\"."
                                            is TemplateWrite.Failed -> result.why
                                        }
                                }
                                saveName = ""
                            }
                        }) { Text(stringResource(R.string.action_save)) }
                    }
                }
                if (library.isNotEmpty()) {
                    item {
                        Text(
                            stringResource(R.string.template_library_heading),
                            style = MaterialTheme.typography.titleSmall,
                            color = accentTextColor(),
                        )
                    }
                }
                items(library, key = { "t_${it.id.value}" }) { t ->
                    TemplateRow(
                        template = t,
                        applyLabel = stringResource(R.string.template_apply),
                        shareLabel = stringResource(R.string.template_share),
                        trailingLabel = stringResource(R.string.template_delete),
                        onApply = { visualsViewModel.applyTemplate(t) },
                        onShare = { shareTemplate(context, visualsViewModel, t) },
                        onTrailing = { deleting = t },
                        trailingIsDestructive = true,
                    )
                }
                item {
                    Text(
                        stringResource(R.string.template_starters_heading),
                        style = MaterialTheme.typography.titleSmall,
                        color = accentTextColor(),
                    )
                }
                items(visualsViewModel.templateStarters, key = { "s_${it.id.value}" }) { t ->
                    TemplateRow(
                        template = t,
                        applyLabel = stringResource(R.string.template_apply),
                        shareLabel = null,
                        trailingLabel = stringResource(R.string.template_adopt),
                        onApply = { visualsViewModel.applyTemplate(t) },
                        onShare = null,
                        onTrailing = {
                            visualsViewModel.adoptTemplate(t) { outcome -> note = messageFor(outcome) }
                        },
                        trailingIsDestructive = false,
                    )
                }
            }
        }
    }
    deleting?.let { t ->
        OpalineContextSheet(onDismiss = { deleting = null }) {
            Text(stringResource(R.string.template_delete_title, t.name), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.template_delete_body))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CreativeButton(onClick = {
                    visualsViewModel.deleteTemplate(t.id)
                    deleting = null
                }) { Text(stringResource(R.string.template_delete)) }
                OpalineAction(onClick = { deleting = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        }
    }
}

/** UI057 row: the template's name, then its UI003 apply, share and delete / use actions. */
@Composable
private fun TemplateRow(
    template: VideoTemplate,
    applyLabel: String,
    shareLabel: String?,
    trailingLabel: String,
    onApply: () -> Unit,
    onShare: (() -> Unit)?,
    onTrailing: () -> Unit,
    trailingIsDestructive: Boolean,
) {
    OpalineRow(
        title = template.name,
        trailing = {
            OpalineIconAction(onClick = onApply) {
                Icon(Icons.Outlined.PlayArrow, applyLabel, tint = MaterialTheme.colorScheme.primary)
            }
            if (shareLabel != null && onShare != null) {
                OpalineIconAction(onClick = onShare) {
                    Icon(Icons.Outlined.Share, shareLabel)
                }
            }
            OpalineIconAction(onClick = onTrailing) {
                val error = MaterialTheme.colorScheme.error
                if (trailingIsDestructive) {
                    Icon(Icons.Outlined.Delete, trailingLabel, tint = error)
                } else {
                    Icon(Icons.Outlined.LibraryAdd, trailingLabel)
                }
            }
        },
    )
}

private fun messageFor(outcome: TemplateImport): String =
    when (outcome) {
        is TemplateImport.Added -> "Imported \"${outcome.template.name}\"."
        is TemplateImport.Replaced -> "Updated \"${outcome.template.name}\"."
        is TemplateImport.Unreadable -> outcome.why
        is TemplateImport.WriteFailed -> outcome.why
    }

private fun shareTemplate(
    context: Context,
    viewModel: VisualsViewModel,
    template: VideoTemplate,
) {
    val link = viewModel.templateShareLink(template) ?: return
    val send =
        Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Geode template: ${template.name}")
            putExtra(Intent.EXTRA_TEXT, link)
        }
    runCatching { context.startActivity(Intent.createChooser(send, context.getString(R.string.template_share))) }
}
