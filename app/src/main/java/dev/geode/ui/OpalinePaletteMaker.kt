package dev.geode.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.geode.R
import dev.geode.data.CustomPalette
import dev.geode.data.PaletteStore
import dev.geode.render.scene.SceneParams
import dev.geode.ui.opaline.OpalineAction
import dev.geode.ui.opaline.OpalineChip
import dev.geode.ui.opaline.OpalineTextField
import dev.geode.ui.opaline.kit.OpalineColourPicker
import dev.geode.ui.opaline.kit.OpalineFilterChipRow

internal fun paletteChipIndex(
    p: SceneParams,
    saved: List<CustomPalette>,
    second: Boolean,
): Int {
    val custom = if (second) p.usesCustomPalette2 else p.usesCustomPalette
    if (!custom) return if (second) p.palette2 else p.palette
    val id = if (second) p.customPalette2Id else p.customPaletteId
    val index = saved.indexOfFirst { it.id == id }
    return if (index < 0) -1 else SceneParams.PALETTES.size + index
}

internal fun paletteChipSelected(
    p: SceneParams,
    saved: List<CustomPalette>,
    index: Int,
    second: Boolean,
): SceneParams {
    val builtInCount = SceneParams.PALETTES.size
    return if (index < builtInCount) {
        PaletteStore.clear(if (second) p.copy(palette2 = index) else p.copy(palette = index), second)
    } else {
        PaletteStore.applyPalette(p, saved[index - builtInCount], second)
    }
}

/** UI010 Filter chip row: one chip per built-in and saved palette, the slot's own chip lifted. */
@Composable
internal fun PaletteSlotSelector(
    p: SceneParams,
    onChange: (SceneParams) -> Unit,
    palettes: SavedPalettes,
    second: Boolean = false,
) {
    val saved = palettes.items
    val labels = SceneParams.PALETTES.map { it.first } + saved.map { it.name }
    val selected = paletteChipIndex(p, saved, second)
    OpalineFilterChipRow(Modifier.fillMaxWidth()) {
        labels.forEachIndexed { index, label ->
            OpalineChip(
                selected = index == selected,
                onClick = { onChange(paletteChipSelected(p, saved, index, second)) },
                label = { Text(label, style = MaterialTheme.typography.labelSmall) },
            )
        }
    }
    GradientPreview(
        baseHue = if (second) p.palette2Base else p.paletteBase,
        hueSpan = if (second) p.palette2Range else p.paletteRange,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
internal fun GradientPreview(
    baseHue: Float,
    hueSpan: Float,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .height(18.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Brush.horizontalGradient(gradientColors(baseHue, hueSpan))),
    )
}

/** The gradient maker: UI063 colour picker, hue ring = base hue, plane across = hue span. */
@Composable
internal fun PaletteMakerCard(
    p: SceneParams,
    onChange: (SceneParams) -> Unit,
    palettes: SavedPalettes,
) {
    var baseHue by remember { mutableFloatStateOf(p.paletteBase) }
    var hueSpan by remember { mutableFloatStateOf(p.paletteRange) }
    var name by rememberSaveable { mutableStateOf("") }
    Column {
        Text(
            "Build a gradient: the base hue is where it starts, the span is " +
                "how far around the colour wheel it sweeps (0 = one flat " +
                "colour). Apply auditions it on the scene; Save adds it to the " +
                "palette row above and to every preset you save afterwards.",
            style = MaterialTheme.typography.labelSmall,
        )
        GradientPreview(baseHue, hueSpan, modifier = Modifier.fillMaxWidth())
        Text(
            stringResource(R.string.palette_base_hue, "%.2f".format(baseHue)),
            style = MaterialTheme.typography.labelSmall,
        )
        Text(
            stringResource(R.string.palette_hue_span, "%.2f".format(hueSpan)),
            style = MaterialTheme.typography.labelSmall,
        )
        OpalineColourPicker(
            baseHue,
            hueSpan,
            1f,
            { hue, span, _ ->
                baseHue = hue
                hueSpan = span
            },
            Modifier.fillMaxWidth(),
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OpalineAction(onClick = { onChange(PaletteStore.applyGradient(p, baseHue, hueSpan)) }) {
                Text(stringResource(R.string.palette_apply_gradient))
            }
            OpalineAction(onClick = {
                baseHue = p.paletteBase
                hueSpan = p.paletteRange
            }) {
                Text(stringResource(R.string.palette_from_current))
            }
        }
        OpalineTextField(
            value = name,
            onValueChange = { name = it },
            singleLine = true,
            label = { Text(stringResource(R.string.palette_name_label)) },
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        )
        OpalineAction(
            onClick = {
                val stored = palettes.save(PaletteStore.create(name, baseHue, hueSpan))
                name = ""
                onChange(PaletteStore.applyPalette(p, stored))
            },
            enabled = name.isNotBlank(),
        ) {
            Text(stringResource(R.string.palette_save))
        }
        Text(stringResource(R.string.palette_saved_heading), style = MaterialTheme.typography.labelSmall)
        if (palettes.items.isEmpty()) {
            Text(stringResource(R.string.palette_none_yet), style = MaterialTheme.typography.labelSmall)
        }
        palettes.items.forEach { palette ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(palette.name, style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                GradientPreview(palette.baseHue, palette.hueSpan, modifier = Modifier.width(64.dp))
                OpalineAction(onClick = {
                    baseHue = palette.baseHue
                    hueSpan = palette.hueSpan
                    name = palette.name
                }) {
                    Text(stringResource(R.string.palette_edit), style = MaterialTheme.typography.labelSmall)
                }
                OpalineAction(onClick = {
                    palettes.delete(palette.id)
                    onChange(PaletteStore.forgetDeleted(p, palette.id))
                }) {
                    Text(stringResource(R.string.palette_delete), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

private fun gradientColors(
    baseHue: Float,
    hueSpan: Float,
): List<Color> =
    (0 until PaletteStore.PREVIEW_STOPS).map { i ->
        val (r, g, b) = PaletteStore.hueRgb(PaletteStore.sampleHue(baseHue, hueSpan, i))
        Color(red = r, green = g, blue = b)
    }
