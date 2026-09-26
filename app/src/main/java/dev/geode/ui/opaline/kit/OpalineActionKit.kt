package dev.geode.ui.opaline.kit

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import dev.geode.ui.opaline.OpalineColors
import dev.geode.ui.opaline.OpalineLabel
import dev.geode.ui.opaline.OpalinePressable
import dev.geode.ui.opaline.OpalineRecipeLayout
import dev.geode.ui.opaline.opalinePart
import dev.geode.ui.opaline.recipeFrame
import dev.geode.ui.opaline.recipePart
import dev.geode.ui.opaline.rememberOpalineComposition
import dev.geode.ui.opaline.rememberOpalinePartEvents
import dev.geode.ui.opaline.rememberRecipeRail
import dev.geode.ui.opaline.stops
import kotlin.math.abs

// UI001 = OpalineButton, UI003 = OpalineIconButton, UI006 = OpalineCheckbox, UI008 =
// OpalineToggle and UI010 chips = OpalineChip live in the core kit.

/** UI002 Secondary action lens: body A03/nacre, label in the `content` frame; activate → commit. */
@Composable
fun OpalineSecondaryAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    OpalinePressable("UI002", "body", "content", onClick, modifier, enabled) {
        OpalineLabel(text, icon)
    }
}

/**
 * UI004 Split action: body A23/gel with the label in `main` and the menu icon in `secondary`. A tap
 * goes to the nearer frame: activate → commit ([onClick]) or activate → open-menu ([onMenu]).
 */
@Composable
fun OpalineSplitAction(
    text: String,
    onClick: () -> Unit,
    menuIcon: ImageVector,
    menuDescription: String,
    onMenu: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val events = rememberOpalinePartEvents()
    val centres = remember { arrayOf(Offset.Zero, Offset.Zero) }
    val actions by rememberUpdatedState(listOf(onClick, onMenu))
    val press = { index: Int ->
        events.raise("activate")
        actions[index]()
    }
    OpalineRecipeLayout(
        "UI004",
        modifier.pointerInput(enabled) {
            if (enabled) detectTapGestures { press(centres.nearest(it)) }
        },
        touch = "body",
        flex = "main",
    ) {
        Box(Modifier.recipePart("body").opalinePart("UI004", enabled = enabled, events = events))
        Pearl {
            Box(
                Modifier.recipeFrame("main").centre(centres, 0).semantics(mergeDescendants = true) {
                    role = Role.Button
                    onClick {
                        press(0)
                        true
                    }
                },
            ) { OpalineLabel(text, null) }
        }
        Pearl {
            Icon(
                menuIcon,
                null,
                Modifier.recipeFrame("secondary").fillMaxSize().centre(centres, 1).semantics {
                    role = Role.Button
                    contentDescription = menuDescription
                    onClick {
                        press(1)
                        true
                    }
                },
            )
        }
    }
}

/**
 * UI005 Floating action cluster: body A01/gel with [icon] in its `content` frame, satellites
 * A03/gel (satellite-a…c). A tap raises activate → expand-radial; the satellites take their
 * actions while [expanded]. The recipe gives satellites no content frame, so each icon sits on its
 * own part.
 */
@Composable
fun OpalineActionCluster(
    icon: ImageVector,
    description: String,
    expanded: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    satellites: List<Pair<ImageVector, String>> = emptyList(),
    onSatellite: (Int) -> Unit = {},
) {
    val events = rememberOpalinePartEvents()
    val orbit = rememberOpalineComposition("UI005").parts.filter { it.id.startsWith("satellite-") }
    OpalineRecipeLayout("UI005", modifier, touch = orbit.first().id) {
        Box(
            Modifier
                .recipePart("body")
                .opalinePart("UI005", selected = expanded, events = events)
                .semantics { contentDescription = description }
                .clickable(role = Role.Button) {
                    events.raise("activate")
                    onClick()
                },
        )
        orbit.forEachIndexed { index, part ->
            val item = satellites.getOrNull(index)
            val active = expanded && item != null
            Box(
                Modifier
                    .recipePart(part.id)
                    .opalinePart(
                        "UI005",
                        part.id,
                        selected = active,
                        enabled = active,
                        events = events,
                    )
                    .clickable(active, role = Role.Button) { onSatellite(index) },
            ) {
                if (item != null) {
                    val (glyph, label) = item
                    Icon(glyph, label, Modifier.fillMaxSize(), tint = OpalineColors.pearl)
                }
            }
        }
        Icon(icon, null, Modifier.recipeFrame("content").fillMaxSize(), tint = OpalineColors.pearl)
    }
}

/** UI007 Radio well: body C20/shell, selection A03/gel while [selected]; → select-exclusive. */
@Composable
fun OpalineRadio(
    selected: Boolean,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val events = rememberOpalinePartEvents()
    val select =
        onClick?.let { action ->
            Modifier.selectable(selected, enabled = enabled, role = Role.RadioButton) {
                events.raise("activate")
                action()
            }
        } ?: Modifier
    OpalineRecipeLayout("UI007", modifier.then(select), touch = "body") {
        Box(Modifier.recipePart("body").opalinePart("UI007", enabled = enabled, events = events))
        if (selected) {
            Box(
                Modifier.recipePart("selection").opalinePart(
                    "UI007",
                    "selection",
                    selected = true,
                    enabled = enabled,
                    events = events,
                ),
            )
        }
    }
}

/**
 * UI009 Three-way selector: body B12/gel at the binding's stops, one option per `choice` frame,
 * filling the width. A tap picks the nearest frame; a drag follows motion.js
 * MotionController.drag for slider kinds (value += dx · 3.6 over the host view's width) and snaps
 * to the nearest stop, as setValue rounds B12 to halves.
 */
@Composable
fun OpalineThreeWaySelector(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val recipe = rememberOpalineComposition("UI009")
    val stops = recipe.stops()
    val events = rememberOpalinePartEvents()
    val view = LocalView.current
    val centres = remember { Array(recipe.contentFrames.size) { Offset.Zero } }
    val current by rememberUpdatedState(selected)
    val pick by rememberUpdatedState(onSelect)
    val choose = { index: Int -> if (index != current) pick(index) }
    OpalineRecipeLayout(
        "UI009",
        modifier
            .fillMaxWidth()
            .selectableGroup()
            .pointerInput(enabled) {
                if (enabled) detectTapGestures { choose(centres.nearest(it)) }
            }.pointerInput(enabled) {
                if (enabled) {
                    var value = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { value = stops[current] },
                    ) { change, dx ->
                        change.consume()
                        value = (value + dx / view.width.coerceAtLeast(1) * 3.6f).coerceIn(0f, 1f)
                        choose(stops.indices.minBy { abs(stops[it] - value) })
                    }
                }
            },
        touch = "body",
    ) {
        Box(
            Modifier.recipePart("body").opalinePart(
                "UI009",
                value = stops[selected],
                enabled = enabled,
                events = events,
            ),
        )
        recipe.contentFrames.forEachIndexed { index, frame ->
            val on = index == selected
            Text(
                options[index],
                Modifier.recipeFrame(frame.id).centre(centres, index).semantics {
                    role = Role.RadioButton
                    this.selected = on
                    onClick {
                        choose(index)
                        true
                    }
                },
                color = OpalineColors.pearl,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

/** UI010 Filter chip row: body D02/shell holding [OpalineChip]s at the recipe's chip pitch. */
@Composable
fun OpalineFilterChipRow(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    val (padding, gap) = rememberRecipeRail("UI010", "body", "chip-0")
    Box(modifier) {
        Box(Modifier.matchParentSize().opalinePart("UI010"))
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(padding),
            horizontalArrangement = Arrangement.spacedBy(gap),
            content = content,
        )
    }
}

@Composable
private fun Pearl(content: @Composable () -> Unit) =
    CompositionLocalProvider(
        LocalContentColor provides OpalineColors.pearl,
        LocalTextStyle provides MaterialTheme.typography.labelLarge,
        content = content,
    )

/** Records a frame child's centre so a tap goes to the nearest frame (plan D7). */
private fun Modifier.centre(
    centres: Array<Offset>,
    index: Int,
): Modifier =
    onPlaced {
        centres[index] = it.positionInParent() + Offset(it.size.width / 2f, it.size.height / 2f)
    }

private fun Array<Offset>.nearest(at: Offset): Int = indices.minBy { (this[it] - at).getDistanceSquared() }
