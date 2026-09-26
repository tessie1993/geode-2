package dev.geode.ui

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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.geode.R
import dev.geode.data.BackgroundPrefsStore
import dev.geode.render.UnderlayBlend
import dev.geode.ui.opaline.OpalineButton
import dev.geode.ui.opaline.creative.CreativeSlider
import dev.geode.ui.opaline.creative.CreativeTabs
import kotlin.math.roundToInt

private val BLENDS =
    listOf(
        UnderlayBlend.SCREEN to R.string.background_blend_screen,
        UnderlayBlend.MULTIPLY to R.string.background_blend_multiply,
        UnderlayBlend.ADD to R.string.background_blend_add,
    )

/**
 * Picks the background image behind the scene, and its blend/amount/blur/dim: the route sheet
 * (UI044), UI001 actions, UI035 blend tabs and three UI019 sliders.
 */
@Composable
fun BackgroundSheet(onDismiss: () -> Unit) {
    val visualsViewModel: VisualsViewModel = geodeViewModel()
    val prefs by visualsViewModel.backgroundPrefs.collectAsStateWithLifecycle()
    val blur = BackgroundPrefsStore.BLUR_RANGE

    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) visualsViewModel.pickBackgroundImage(uri)
        }

    OpalineContextSheet(onDismiss = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.background_sheet_title),
                style = MaterialTheme.typography.titleMedium,
            )
            if (prefs.uri == null) {
                Text(
                    stringResource(R.string.background_none),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OpalineButton(
                    stringResource(R.string.background_pick),
                    { picker.launch(arrayOf("image/*")) },
                )
                if (prefs.uri != null) {
                    OpalineButton(
                        stringResource(R.string.background_clear),
                        visualsViewModel::clearBackgroundImage,
                    )
                }
            }
            Column {
                Text(
                    stringResource(R.string.background_blend),
                    style = MaterialTheme.typography.labelMedium,
                )
                CreativeTabs(
                    BLENDS.map { stringResource(it.second) },
                    BLENDS.indexOfFirst { it.first == prefs.blend },
                    { visualsViewModel.setBackgroundBlend(BLENDS[it].first) },
                )
            }
            Column {
                Text(
                    stringResource(R.string.background_amount, (prefs.amount * 100).roundToInt()),
                    style = MaterialTheme.typography.labelMedium,
                )
                CreativeSlider(
                    value = prefs.amount,
                    onValueChange = visualsViewModel::setBackgroundAmount,
                    valueRange = 0f..1f,
                )
            }
            Column {
                Text(
                    stringResource(R.string.background_blur, prefs.blurRadius),
                    style = MaterialTheme.typography.labelMedium,
                )
                CreativeSlider(
                    value = prefs.blurRadius.toFloat(),
                    onValueChange = { visualsViewModel.setBackgroundBlurRadius(it.roundToInt()) },
                    valueRange = blur.first.toFloat()..blur.last.toFloat(),
                )
            }
            Column {
                Text(
                    stringResource(R.string.background_dim, (prefs.dim * 100).roundToInt()),
                    style = MaterialTheme.typography.labelMedium,
                )
                CreativeSlider(
                    value = prefs.dim,
                    onValueChange = visualsViewModel::setBackgroundDim,
                    valueRange = 0f..1f,
                )
            }
        }
    }
}
