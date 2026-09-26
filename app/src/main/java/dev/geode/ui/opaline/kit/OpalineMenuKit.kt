package dev.geode.ui.opaline.kit

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import dev.geode.ui.opaline.OpalineColors
import dev.geode.ui.opaline.OpalinePartEvents
import dev.geode.ui.opaline.OpalinePressable
import dev.geode.ui.opaline.OpalineRecipeLayout
import dev.geode.ui.opaline.OpalineSceneHost
import dev.geode.ui.opaline.opalinePart
import dev.geode.ui.opaline.partBox
import dev.geode.ui.opaline.recipePart
import dev.geode.ui.opaline.rememberOpalineComposition
import dev.geode.ui.opaline.rememberOpalineDismiss
import dev.geode.ui.opaline.rememberOpalinePartEvents
import dev.geode.ui.opaline.rememberRecipeRail
import dev.geode.ui.opaline.sibling
import dev.geode.ui.opaline.touchScale

/** UI029 Compact popup menu: body C03/shell, row-* A05/gel in item-* frames; activate → select. */
@Composable
fun OpalineCompactPopupMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    items: List<String>,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val events = rememberOpalinePartEvents()
    MenuPopup(expanded, onDismissRequest, modifier, events) {
        MenuRows("UI029", items, onSelect, events = events)
    }
}

/** UI030 Navigation side panel: body C03/shell, row-* A05/gel in item-* frames, selected lifts. */
@Composable
fun OpalineNavigationSidePanel(
    items: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) = MenuRows("UI030", items, onSelect, modifier, selectedIndex)

/** UI031 Action sheet: body C03/shell, row-* A05/gel in item-* frames; activate → select. */
@Composable
fun OpalineActionSheet(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    title: String,
    items: List<String>,
    onSelect: (Int) -> Unit,
) {
    if (!expanded) return
    val events = rememberOpalinePartEvents()
    val close = rememberOpalineDismiss(events, onDismissRequest)
    Dialog(close, DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            Box(Modifier.matchParentSize().pointerInput(close) { detectTapGestures { close() } })
            PopupScene(events, Modifier.semantics { paneTitle = title }) {
                MenuRows("UI031", items, onSelect, events = events)
            }
        }
    }
}

/** UI032 Radial context menu: body D05/shell, action-* A01/gel; activate → select-radial. */
@Composable
fun OpalineRadialContextMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    actions: List<OpalineDestination>,
    onSelect: (Int) -> Unit,
) {
    if (!expanded) return
    val events = rememberOpalinePartEvents()
    val close = rememberOpalineDismiss(events, onDismissRequest)
    Popup(
        alignment = Alignment.Center,
        onDismissRequest = close,
        properties = PopupProperties(focusable = true),
    ) {
        PopupScene(events) {
            OpalineRecipeLayout("UI032", touch = "action-0") {
                Box(Modifier.recipePart("body").opalinePart("UI032", events = events))
                actions.forEachIndexed { index, action ->
                    PartPressable("UI032", "action-$index", { onSelect(index) }) {
                        Icon(action.icon, action.label, tint = OpalineColors.text)
                    }
                }
            }
        }
    }
}

/**
 * UI033 Nested submenu: body C03/shell with row-* A05/gel, submenu C03/shell with child-* A05/gel
 * for the open row; hover (or a tap) on a row → open-submenu, activate on a child → select.
 */
@Composable
fun OpalineNestedSubmenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    items: List<String>,
    children: List<List<String>>,
    onSelect: (Int, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val recipe = rememberOpalineComposition("UI033")
    val events = rememberOpalinePartEvents()
    var open by remember(expanded) { mutableIntStateOf(-1) }
    val shown = children.getOrNull(open).orEmpty()
    val gap = recipe.partBox("submenu").left - recipe.partBox("body").right
    val between = with(LocalDensity.current) { (gap * touchScale(recipe, "row-0")).toDp() }
    MenuPopup(expanded, onDismissRequest, modifier, events) {
        Row(horizontalArrangement = Arrangement.spacedBy(between)) {
            MenuStack("UI033", "row-", items.size, selectable = true, events = events) { index ->
                val row = rememberOpalinePartEvents()
                val hover = remember { MutableInteractionSource() }
                val hovered by hover.collectIsHoveredAsState()
                LaunchedEffect(hovered) {
                    if (hovered) {
                        row.raise("hover")
                        open = index
                    }
                }
                PartPressable(
                    "UI033",
                    recipe.sibling("row-", index),
                    { open = index },
                    Modifier.hoverable(hover),
                    selected = open == index,
                    event = "hover",
                    events = row,
                ) { Label(items[index]) }
            }
            if (shown.isNotEmpty()) {
                MenuStack("UI033", "child-", shown.size, "submenu", events = events) { index ->
                    val child = recipe.sibling("child-", index)
                    PartPressable("UI033", child, { onSelect(open, index) }) { Label(shown[index]) }
                }
            }
        }
    }
}

/**
 * UI034 Bottom navigation dock: body D03/shell, destination-* A03/gel with icon-* frames;
 * activate → navigate, the selected destination lifted.
 */
@Composable
fun OpalineBottomDock(
    destinations: List<OpalineDestination>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val recipe = rememberOpalineComposition("UI034")
    val colors = MaterialTheme.colorScheme
    Box(modifier, contentAlignment = Alignment.Center) {
        MenuStack("UI034", "destination-", destinations.size, vertical = false, selectable = true) {
            val part = recipe.sibling("destination-", it)
            OpalinePressable(
                "UI034",
                part,
                recipe.contentFrames.first { frame -> frame.owner == part }.id,
                { onSelect(it) },
                selected = it == selectedIndex,
                checked = it == selectedIndex,
                role = Role.Tab,
                flexible = false,
            ) {
                Icon(
                    destinations[it].icon,
                    destinations[it].label,
                    Modifier.fillMaxSize(),
                    tint = if (it == selectedIndex) colors.primary else colors.onSurfaceVariant,
                )
            }
        }
    }
}

/** A dock or rail destination, or a radial action: its icon and the label announced for it. */
data class OpalineDestination(
    val icon: ImageVector,
    val label: String,
)

/**
 * UI036 Breadcrumb path: body D10/nacre, crumb-* A05/gel; activate → navigate-ancestor. The path
 * scrolls from its end, so the current crumb shows first.
 */
@Composable
fun OpalineBreadcrumbPath(
    crumbs: List<String>,
    onNavigate: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val recipe = rememberOpalineComposition("UI036")
    Box(modifier.horizontalScroll(rememberScrollState(), reverseScrolling = true)) {
        MenuStack("UI036", "crumb-", crumbs.size, vertical = false) {
            PartPressable("UI036", recipe.sibling("crumb-", it), { onNavigate(it) }) {
                Label(crumbs[it])
            }
        }
    }
}

/**
 * UI037 Pagination beads: body D10/shell, page-* A03/nacre, the current page on the recipe's glow
 * bead (page-2); activate → select-page. The body's short side meets the 48 dp floor and the
 * beads keep their recipe size and pitch against it; Compose's minimum touch target widens each
 * bead.
 */
@Composable
fun OpalinePaginationBeads(
    pageCount: Int,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    pageLabel: (Int) -> String,
    modifier: Modifier = Modifier,
) {
    val recipe = rememberOpalineComposition("UI037")
    val beads = recipe.parts.filter { it.id.startsWith("page-") }
    val glow = beads.first { it.material == "glow" }.id
    val rest = beads.first { it.material != "glow" }.id
    val metre = with(LocalDensity.current) { touchScale(recipe, "body").toDp() }
    val body = recipe.partBox("body")
    val first = recipe.partBox("page-0")
    val second = recipe.partBox("page-1")
    Box(modifier, contentAlignment = Alignment.Center) {
        Box(Modifier.opalinePart("UI037")) {
            Row(
                Modifier
                    .selectableGroup()
                    .padding(
                        horizontal = metre * (first.left - body.left),
                        vertical = metre * (first.top - body.top),
                    ),
                horizontalArrangement = Arrangement.spacedBy(metre * (second.left - first.right)),
            ) {
                repeat(pageCount) {
                    PartPressable(
                        "UI037",
                        if (it == selectedIndex) glow else rest,
                        { onSelect(it) },
                        Modifier.size(metre * first.width, metre * first.height),
                        selected = it == selectedIndex,
                    ) {
                        Box(Modifier.fillMaxSize().semantics { contentDescription = pageLabel(it) })
                    }
                }
            }
        }
    }
}

/**
 * UI038 Vertical navigation rail: body D07/shell, destination-* A03/gel; activate → navigate, the
 * selected destination lifted.
 */
@Composable
fun OpalineNavigationRail(
    destinations: List<OpalineDestination>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val recipe = rememberOpalineComposition("UI038")
    val colors = MaterialTheme.colorScheme
    Box(modifier, contentAlignment = Alignment.TopCenter) {
        MenuStack("UI038", "destination-", destinations.size, selectable = true) {
            PartPressable(
                "UI038",
                recipe.sibling("destination-", it),
                { onSelect(it) },
                selected = it == selectedIndex,
            ) {
                Icon(
                    destinations[it].icon,
                    destinations[it].label,
                    tint = if (it == selectedIndex) colors.primary else colors.onSurfaceVariant,
                )
            }
        }
    }
}

/** UI029–UI031 rows: A05/gel pressables in their item-* frames, each with its own events. */
@Composable
private fun MenuRows(
    recipe: String,
    items: List<String>,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    selectedIndex: Int? = null,
    events: OpalinePartEvents? = null,
) {
    val composition = rememberOpalineComposition(recipe)
    Box(modifier, contentAlignment = Alignment.Center) {
        MenuStack(recipe, "row-", items.size, selectable = selectedIndex != null, events = events) {
            val part = composition.sibling("row-", it)
            OpalinePressable(
                recipe,
                part,
                composition.contentFrames.first { frame -> frame.owner == part }.id,
                { onSelect(it) },
                Modifier.fillMaxWidth(),
                selected = it == selectedIndex,
                checked = if (selectedIndex == null) null else it == selectedIndex,
                role = if (selectedIndex == null) Role.Button else Role.Tab,
                alignment = Alignment.CenterStart,
            ) { Label(items[it]) }
        }
    }
}

/**
 * The recipe's [container] part wrapping [count] items at the recipe's padding and pitch
 * (rememberRecipeRail), so the container grows or shrinks with the item count (plan Part B).
 */
@Composable
private fun MenuStack(
    recipe: String,
    prefix: String,
    count: Int,
    container: String = "body",
    vertical: Boolean = true,
    selectable: Boolean = false,
    events: OpalinePartEvents? = null,
    item: @Composable (Int) -> Unit,
) {
    val (padding, gap) = rememberRecipeRail(recipe, container, "${prefix}0")
    val inner =
        Modifier
            .padding(padding)
            .then(if (selectable) Modifier.selectableGroup() else Modifier)
    Box(Modifier.opalinePart(recipe, container, events = events)) {
        if (vertical) {
            Column(
                inner.width(IntrinsicSize.Max),
                verticalArrangement = Arrangement.spacedBy(gap),
            ) { repeat(count) { item(it) } }
        } else {
            Row(inner, horizontalArrangement = Arrangement.spacedBy(gap)) {
                repeat(count) { item(it) }
            }
        }
    }
}

/**
 * A pressable recipe part without a content frame (UI032, UI033, UI036–UI038): [content] is
 * centred on the part; a tap raises [event] on the item's own [events], then [onClick].
 */
@Composable
private fun PartPressable(
    recipe: String,
    part: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean? = null,
    event: String = "activate",
    events: OpalinePartEvents = rememberOpalinePartEvents(),
    content: @Composable BoxScope.() -> Unit,
) {
    val view = LocalView.current
    val press = {
        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        events.raise(event)
        onClick()
    }
    val action =
        if (selected == null) {
            Modifier.clickable(interactionSource = null, indication = null, onClick = press)
        } else {
            Modifier.selectable(
                selected,
                interactionSource = null,
                indication = null,
                role = Role.Tab,
                onClick = press,
            )
        }
    OpalineRecipeLayout(recipe, modifier.recipePart(part).then(action), touch = part) {
        Box(
            Modifier
                .recipePart(part)
                .opalinePart(recipe, part, selected = selected == true, events = events),
            contentAlignment = Alignment.Center,
            content = content,
        )
    }
}

@Composable
private fun Label(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = OpalineColors.text,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** A popup menu window with its own scene, as OpalineOverlays.kt hosts OpalineDropdownMenu. */
@Composable
private fun MenuPopup(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier,
    events: OpalinePartEvents,
    content: @Composable () -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = rememberOpalineDismiss(events, onDismissRequest),
        modifier = modifier,
        shape = RectangleShape,
        containerColor = Color.Transparent,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) { PopupScene(events, content = content) }
}

/** A popup's own scene; the instance raises "open" when it appears. */
@Composable
private fun PopupScene(
    events: OpalinePartEvents,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    OpalineSceneHost(modifier, environment = false, transparent = true) {
        LaunchedEffect(events) { events.raise("open") }
        content()
    }
}
