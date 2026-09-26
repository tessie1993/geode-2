package dev.geode.ui.opaline.kit

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.zIndex
import dev.geode.ui.opaline.OpalineColors
import dev.geode.ui.opaline.OpalineComposition
import dev.geode.ui.opaline.OpalinePartEvents
import dev.geode.ui.opaline.OpalineRecipeLayout
import dev.geode.ui.opaline.opalinePart
import dev.geode.ui.opaline.recipeFrame
import dev.geode.ui.opaline.recipePart
import dev.geode.ui.opaline.rememberOpalineComposition
import dev.geode.ui.opaline.rememberOpalinePartEvents

/**
 * UI072 Immersive panel with shoreline: body C03/shell, shore J04/stone, frond J13/leaf, droplet
 * E09/water; show → lift, pointermove → parallax. [content] sizes the content frame.
 */
@Composable
fun OpalineShorelinePanel(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val recipe = rememberOpalineComposition("UI072")
    val events = rememberOpalinePartEvents()
    LaunchedEffect(events) { events.raise("show") }
    OpalineRecipeLayout(
        "UI072",
        modifier.observePointer(PointerEventType.Move) { events.raise("pointermove") },
        fitWidth = true,
        flex = "content",
    ) {
        OpalineSceneParts(recipe, events)
        Box(Modifier.sceneNode(recipe, "content"), content = content)
    }
}

/**
 * UI073 Liquid control panel: body C03/shell, switch-main B09/gel, switch-secondary B10/gel,
 * flow-primary E12/water showing [flow], flow-secondary B07/water showing [secondaryFlow].
 */
@Composable
fun OpalineLiquidControlPanel(
    title: String,
    mainLabel: String,
    mainChecked: Boolean,
    onMainChange: (Boolean) -> Unit,
    secondaryLabel: String,
    secondaryChecked: Boolean,
    onSecondaryChange: (Boolean) -> Unit,
    flow: Float,
    flowLabel: String,
    secondaryFlow: Float,
    secondaryFlowLabel: String,
    modifier: Modifier = Modifier,
) {
    val recipe = rememberOpalineComposition("UI073")
    val events = rememberOpalinePartEvents()
    OpalineRecipeLayout("UI073", modifier, fitWidth = true) {
        Box(Modifier.sceneNode(recipe, "body").opalinePart("UI073", events = events))
        Text(
            title,
            Modifier.sceneNode(recipe, "header").semantics { heading() },
            color = OpalineColors.text,
            style = MaterialTheme.typography.titleMedium,
        )
        LiquidSwitch(recipe, "switch-main", mainLabel, mainChecked, onMainChange, events)
        LiquidSwitch(
            recipe,
            "switch-secondary",
            secondaryLabel,
            secondaryChecked,
            onSecondaryChange,
            events,
        )
        Box(
            Modifier
                .sceneNode(recipe, "flow-primary")
                .opalineLiquidFlow(flow, flowLabel, events, null),
        )
        Box(
            Modifier
                .sceneNode(recipe, "flow-secondary")
                .opalinePart("UI073", "flow-secondary", value = secondaryFlow, events = events)
                .semantics {
                    contentDescription = secondaryFlowLabel
                    progressBarRangeInfo =
                        ProgressBarRangeInfo(secondaryFlow.coerceIn(0f, 1f), 0f..1f)
                },
        )
    }
}

/** UI073 flow-primary alone (E12/water): the transport's liquid progress channel. */
@Composable
fun OpalineLiquidProgressChannel(
    progress: Float,
    description: String,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val events = rememberOpalinePartEvents()
    OpalineRecipeLayout("UI073", modifier, fitWidth = true) {
        Box(
            Modifier
                .recipePart("flow-primary")
                .opalineLiquidFlow(progress, description, events, onSeek),
        )
    }
}

/**
 * UI074 Liquid emergence notification: body C05/blue, water E03, neck E11, ring E14, drop-left
 * and drop-right E10 (all water). Shown, it raises show (liquid-emerge); once that settles, the
 * recontact phase raises contact (water-ring), then the settle phase raises settled (G07
 * curved-emission-filaments). [content] sizes the content frame.
 */
@Composable
fun OpalineLiquidNotification(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val recipe = rememberOpalineComposition("UI074")
    val events = rememberOpalinePartEvents()
    LaunchedEffect(events) {
        events.raise("show")
        events.awaitSettled()
        events.raise("contact")
        events.awaitSettled()
        events.raise("settled")
    }
    OpalineRecipeLayout("UI074", modifier, fitWidth = true, flex = "content") {
        OpalineSceneParts(recipe, events, skip = setOf("body"))
        Box(
            Modifier
                .sceneNode(recipe, "body")
                .opalinePart("UI074", events = events)
                .clickable(onClick = onClick),
        )
        Box(
            Modifier
                .sceneNode(recipe, "content")
                .semantics { liveRegion = LiveRegionMode.Polite },
            content = content,
        )
    }
}

/**
 * UI075 Seed-and-leaf control dock: body N02/shell, seed-left and seed-middle N01/gel, lens-right
 * N03/water, tendril N04/shell, spore N06/glow. [icons] and [labels] fill its three icon frames
 * in recipe order (seed-left, seed-middle, lens-right); activate → select-compartment, and any
 * touch raises contact → neighbour-impulse.
 */
@Composable
fun OpalineSeedDock(
    icons: List<ImageVector>,
    labels: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val recipe = rememberOpalineComposition("UI075")
    val events = rememberOpalinePartEvents()
    val frames = recipe.contentFrames
    OpalineRecipeLayout(
        "UI075",
        modifier
            .selectableGroup()
            .observePointer(PointerEventType.Press) { events.raise("contact") },
        fitWidth = true,
    ) {
        OpalineSceneParts(recipe, events, skip = frames.map { it.owner }.toSet())
        frames.forEachIndexed { i, frame ->
            val compartment = rememberOpalinePartEvents()
            Box(
                Modifier
                    .sceneNode(recipe, frame.owner)
                    .opalinePart(
                        "UI075",
                        frame.owner,
                        selected = i == selectedIndex,
                        events = compartment,
                    ).selectable(i == selectedIndex, role = Role.Tab) {
                        compartment.raise("activate")
                        onSelect(i)
                    }.semantics { contentDescription = labels[i] },
            )
            Box(Modifier.sceneNode(recipe, frame.id)) {
                Icon(icons[i], null, Modifier.fillMaxSize(), tint = OpalineColors.text)
            }
        }
    }
}

/**
 * UI076 Water-root membrane panel: body N07/shell, frond N05/shell, spore-a and spore-b N06/glow,
 * dew N03/water; pointermove → shared-wind, focus inside the content → local-excitation.
 * [content] sizes the content frame.
 */
@Composable
fun OpalineWaterRootPanel(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val recipe = rememberOpalineComposition("UI076")
    val events = rememberOpalinePartEvents()
    OpalineRecipeLayout(
        "UI076",
        modifier.observePointer(PointerEventType.Move) { events.raise("pointermove") },
        fitWidth = true,
        flex = "content",
    ) {
        OpalineSceneParts(recipe, events)
        Box(
            Modifier
                .sceneNode(recipe, "content")
                .onFocusChanged { if (it.hasFocus) events.raise("focus") },
            content = content,
        )
    }
}

/** One UI073 switch with its `-label` frame; the switch the recipe binds to drag stretches. */
@Composable
private fun LiquidSwitch(
    recipe: OpalineComposition,
    part: String,
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    events: OpalinePartEvents,
) {
    val change by rememberUpdatedState(onChange)
    val stretch = recipe.behaviorBindings.any { it.event == "drag" && it.target == part }
    Box(
        Modifier
            .sceneNode(recipe, part)
            .opalinePart(
                recipe.id,
                part,
                value = if (checked) 1f else 0f,
                selected = checked,
                events = events,
            ).toggleable(checked, role = Role.Switch, onValueChange = onChange)
            .semantics { contentDescription = label }
            .pointerInput(stretch) {
                var travel = 0f
                detectHorizontalDragGestures(
                    onDragStart = {
                        travel = 0f
                        if (stretch) events.raise("drag")
                    },
                    onDragEnd = { if (travel != 0f) change(travel > 0f) },
                ) { _, delta -> travel += delta }
            },
    )
    Text(
        label,
        Modifier.sceneNode(recipe, "$part-label").clearAndSetSemantics {},
        color = OpalineColors.text,
        style = MaterialTheme.typography.bodyMedium,
    )
}

/**
 * UI073 flow-primary (E12/water) as a live channel: [progress] advances its meniscus
 * (valuechange → advancing-meniscus) and a press rings the water (press → origin-rings). With
 * [onSeek], a tap seeks and a horizontal drag previews, committing once on release.
 */
internal fun Modifier.opalineLiquidFlow(
    progress: Float,
    description: String,
    events: OpalinePartEvents,
    onSeek: ((Float) -> Unit)?,
): Modifier =
    composed {
        var preview by remember { mutableStateOf<Float?>(null) }
        val seek by rememberUpdatedState(onSeek)
        val value = preview ?: progress
        LaunchedEffect(value) { events.raise("valuechange") }
        opalinePart("UI073", "flow-primary", value = value, events = events)
            .observePointer(PointerEventType.Press) { events.raise("press") }
            .semantics {
                contentDescription = description
                progressBarRangeInfo = ProgressBarRangeInfo(value.coerceIn(0f, 1f), 0f..1f)
                if (onSeek != null) {
                    setProgress { target ->
                        onSeek(target.coerceIn(0f, 1f))
                        true
                    }
                }
            }.then(
                if (onSeek == null) {
                    Modifier
                } else {
                    Modifier
                        .pointerInput(Unit) {
                            detectTapGestures { seek?.invoke((it.x / size.width).coerceIn(0f, 1f)) }
                        }.pointerInput(Unit) {
                            detectHorizontalDragGestures(
                                onDragEnd = {
                                    preview?.let { seek?.invoke(it) }
                                    preview = null
                                },
                                onDragCancel = { preview = null },
                            ) { change, _ ->
                                preview = (change.position.x / size.width).coerceIn(0f, 1f)
                            }
                        }
                },
            )
    }

/**
 * Tags part or content frame [id] for [OpalineRecipeLayout] with zIndex from its recipe z. A
 * [decoration] drops a full depth span, beneath every other node, so it never takes a pointer
 * from one (COMPONENT-ANATOMY.md, input ownership).
 */
internal fun Modifier.sceneNode(
    recipe: OpalineComposition,
    id: String,
    decoration: Boolean = false,
): Modifier {
    val part = recipe.parts.find { it.id == id }
    val depths = recipe.parts.map { it.position.z } + recipe.contentFrames.map { it.position.z }
    val z = part?.position?.z ?: recipe.frame(id).position.z
    val tag = if (part != null) recipePart(id) else recipeFrame(id)
    return tag.zIndex(if (decoration) z - (depths.max() - depths.min()) else z)
}

/** Every part of [recipe] except [skip]; all but `body` are decorations. */
@Composable
internal fun OpalineSceneParts(
    recipe: OpalineComposition,
    events: OpalinePartEvents,
    skip: Set<String> = emptySet(),
) {
    for (part in recipe.parts) {
        if (part.id !in skip) {
            Box(
                Modifier
                    .sceneNode(recipe, part.id, decoration = part.id != "body")
                    .opalinePart(recipe.id, part.id, events = events),
            )
        }
    }
}

/** Runs [action] for every pointer event of [type] over this node, observed, never consumed. */
internal fun Modifier.observePointer(
    type: PointerEventType,
    action: () -> Unit,
): Modifier =
    pointerInput(type) {
        awaitPointerEventScope {
            while (true) {
                if (awaitPointerEvent(PointerEventPass.Initial).type == type) action()
            }
        }
    }
