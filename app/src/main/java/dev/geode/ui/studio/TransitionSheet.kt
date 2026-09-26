package dev.geode.ui.studio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.geode.R
import dev.geode.editor.ClipTransition
import dev.geode.render.TransitionCatalog
import dev.geode.ui.opaline.OpalineChip
import dev.geode.ui.opaline.OpalineRow
import dev.geode.ui.opaline.creative.CreativeButton
import dev.geode.ui.opaline.creative.CreativeColors
import dev.geode.ui.opaline.creative.CreativeSheet
import dev.geode.ui.opaline.kit.OpalineFilterChipRow

/**
 * Picks the GL Transition a clip opens with, and how long it runs; "None" clears it. A UI044
 * sheet: durations as UI010 filter chips, transitions as UI057 rows (the current one lifted).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransitionSheet(
    current: ClipTransition?,
    onPick: (ClipTransition?) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val library = remember { TransitionCatalog.library(context) }
    var durationMs by remember { mutableStateOf(current?.boundedDurationMs ?: ClipTransition.DEFAULT_TRANSITION_MS) }
    CreativeSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.editor_transition),
                style = MaterialTheme.typography.titleMedium,
                color = CreativeColors.textPrimary,
            )
            OpalineFilterChipRow(Modifier.fillMaxWidth()) {
                ClipTransition.DURATION_CHOICES_MS.forEach { choice ->
                    OpalineChip(
                        onClick = { durationMs = choice },
                        label = {
                            Text(stringResource(R.string.editor_transition_seconds, choice / 1000f))
                        },
                        selected = choice == durationMs,
                    )
                }
            }
            CreativeButton(
                text = stringResource(R.string.editor_transition_none),
                modifier = Modifier.fillMaxWidth(),
                onClick = { onPick(null) },
            )
            LazyColumn {
                items(library, key = { it.name }) { def ->
                    val chosen = def.name == current?.id
                    OpalineRow(
                        title =
                            if (chosen) {
                                stringResource(R.string.editor_transition_current, def.name)
                            } else {
                                def.name
                            },
                        onClick = { onPick(ClipTransition(def.name, durationMs)) },
                        modifier = Modifier.padding(vertical = 2.dp),
                        selected = chosen,
                    )
                }
            }
        }
    }
}
