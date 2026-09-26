package dev.geode.ui.opaline.kit

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import dev.geode.R
import dev.geode.ui.opaline.FieldDecoration
import dev.geode.ui.opaline.OpalineColors
import dev.geode.ui.opaline.OpalinePressable
import dev.geode.ui.opaline.OpalineRecipeLayout
import dev.geode.ui.opaline.RaiseFocusEvents
import dev.geode.ui.opaline.frameBox
import dev.geode.ui.opaline.opalinePart
import dev.geode.ui.opaline.partBox
import dev.geode.ui.opaline.recipeFrame
import dev.geode.ui.opaline.recipePart
import dev.geode.ui.opaline.rememberOpalineComposition
import dev.geode.ui.opaline.rememberOpalineDismiss
import dev.geode.ui.opaline.rememberOpalinePartEvents
import dev.geode.ui.opaline.sibling
import dev.geode.ui.opaline.touchScale
import kotlin.math.roundToInt

// UI011 Single-line input, UI012 Text area and UI014 Secure-entry field are OpalineTextField in
// the core kit. The recipes give the clear, picker, stepper and tag parts no content frame, so
// their glyphs sit on the parts themselves.

/**
 * UI013 Search field: body A05/shell, leading C20/nacre, clear A22/nacre and the query in the
 * `query` frame. Focus → lift body; activate clear → clear.
 */
@Composable
fun OpalineSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    enabled: Boolean = true,
) {
    val events = rememberOpalinePartEvents()
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val clearable = enabled && query.isNotEmpty()
    RaiseFocusEvents(interaction, events)
    OpalineRecipeLayout(
        "UI013",
        modifier,
        touch = "body",
        flex = "query",
        flexAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier.recipePart("body").opalinePart(
                "UI013",
                selected = focused,
                enabled = enabled,
                events = events,
            ),
        )
        Box(Modifier.recipePart("leading").opalinePart("UI013", "leading", events = events))
        Glyph(
            Icons.Filled.Close,
            stringResource(R.string.action_clear),
            Modifier
                .recipePart("clear")
                .opalinePart("UI013", "clear", enabled = clearable, events = events)
                .clickable(clearable, role = Role.Button) {
                    events.raise("activate")
                    onQueryChange("")
                },
        )
        EntryText(query, onQueryChange, "query", interaction, enabled, focused, placeholder)
    }
}

/**
 * UI015 Search with suggestions: body C03/shell, search A05/gel with the query in its frame and
 * one suggestion row A05/gel per `result` frame the recipe holds. Input → filter-results;
 * activate a row → select-result.
 */
@Composable
fun OpalineSearchSuggestions(
    query: String,
    onQueryChange: (String) -> Unit,
    suggestions: List<String>,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
) {
    val recipe = rememberOpalineComposition("UI015")
    val rows = recipe.contentFrames.filter { it.owner.startsWith("row-") }
    val events = rememberOpalinePartEvents()
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    RaiseFocusEvents(interaction, events)
    OpalineRecipeLayout(
        "UI015",
        modifier,
        touch = "search",
        flex = "query",
        flexAlignment = Alignment.CenterStart,
    ) {
        Box(Modifier.recipePart("body").opalinePart("UI015", events = events))
        Box(
            Modifier.recipePart("search").opalinePart(
                "UI015",
                "search",
                selected = focused,
                events = events,
            ),
        )
        suggestions.take(rows.size).forEachIndexed { index, suggestion ->
            val frame = rows[index]
            OpalinePressable(
                "UI015",
                frame.owner,
                frame.id,
                { onSelect(index) },
                Modifier.recipePart(frame.owner),
                alignment = Alignment.CenterStart,
            ) {
                Text(suggestion, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        EntryText(
            query,
            {
                events.raise("input")
                onQueryChange(it)
            },
            "query",
            interaction,
            true,
            focused,
            placeholder,
        )
    }
}

/**
 * UI016 Number stepper: body C02/shell with the value editable in its `value` frame, decrease and
 * increase A03/gel; activate → increment by each binding's step within [range]. [label] names the
 * adjustable value for accessibility. A value below [range] is unset: the frame shows no text,
 * and clearing the text reports `range.first - 1`.
 */
@Composable
fun OpalineNumberStepper(
    value: Int,
    onValueChange: (Int) -> Unit,
    label: String,
    range: IntRange,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val recipe = rememberOpalineComposition("UI016")
    val events = rememberOpalinePartEvents()
    val steps =
        recipe.behaviorBindings
            .filter { it.action == "increment" }
            .map { it.target to (it.parameters["step"] as Number).toInt() }
    OpalineRecipeLayout("UI016", modifier, touch = "decrease", flex = "value") {
        Box(Modifier.recipePart("body").opalinePart("UI016", enabled = enabled, events = events))
        for ((part, step) in steps) {
            val next = (value + step).coerceIn(range)
            val live = enabled && next != value
            Glyph(
                if (step < 0) Icons.Filled.Remove else Icons.Filled.Add,
                null,
                Modifier
                    .recipePart(part)
                    .opalinePart("UI016", part, enabled = live, events = events)
                    .clickable(live) {
                        events.raise("activate")
                        onValueChange(next)
                    }.clearAndSetSemantics {},
            )
        }
        BasicTextField(
            value = if (value in range) "$value" else "",
            onValueChange = { text ->
                val typed = text.filter(Char::isDigit).toIntOrNull()
                onValueChange(typed?.coerceAtMost(range.last) ?: (range.first - 1))
            },
            modifier =
                Modifier.recipeFrame("value").semantics {
                    contentDescription = label
                    progressBarRangeInfo =
                        ProgressBarRangeInfo(
                            value.toFloat(),
                            range.first.toFloat()..range.last.toFloat(),
                        )
                    setProgress {
                        onValueChange(it.roundToInt().coerceIn(range))
                        true
                    }
                },
            enabled = enabled,
            textStyle = entryStyle().copy(textAlign = TextAlign.Center),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            cursorBrush = SolidColor(OpalineColors.accent),
        )
    }
}

/**
 * UI017 Date field: body C02/shell with the date editable in its `value` frame and picker A04/gel;
 * activate the picker → open-date-picker ([onOpenPicker], named [pickerLabel]).
 */
@Composable
fun OpalineDateField(
    value: String,
    onValueChange: (String) -> Unit,
    onOpenPicker: () -> Unit,
    pickerLabel: String,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    enabled: Boolean = true,
) {
    val events = rememberOpalinePartEvents()
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    OpalineRecipeLayout(
        "UI017",
        modifier,
        touch = "body",
        flex = "value",
        flexAlignment = Alignment.CenterStart,
    ) {
        Box(Modifier.recipePart("body").opalinePart("UI017", enabled = enabled, events = events))
        Glyph(
            Icons.Filled.DateRange,
            pickerLabel,
            Modifier
                .recipePart("picker")
                .opalinePart("UI017", "picker", enabled = enabled, events = events)
                .clickable(enabled, role = Role.Button) {
                    events.raise("activate")
                    onOpenPicker()
                },
        )
        EntryText(value, onValueChange, "value", interaction, enabled, focused, placeholder)
    }
}

/**
 * UI018 Tag entry: body C01/shell, tags A05/gel (tag-*, wrapping at the recipe's pitch) above a
 * BasicTextField in the `input` frame. Done on the keyboard → commit → append-tag ([onAdd]);
 * tapping a tag → remove → detach-tag, which plays before [onRemove].
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OpalineTagEntry(
    tags: List<String>,
    onAdd: (String) -> Unit,
    onRemove: (Int) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
) {
    val recipe = rememberOpalineComposition("UI018")
    val events = rememberOpalinePartEvents()
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    var draft by rememberSaveable { mutableStateOf("") }
    val density = LocalDensity.current
    val scale = with(density) { touchScale(recipe, "tag-0") }
    val body = recipe.partBox("body")
    val first = recipe.partBox("tag-0")
    val input = recipe.frameBox("input")
    val dp: (Float) -> Dp = { metres -> with(density) { (metres * scale).toDp() } }
    val gap = dp(recipe.partBox("tag-0", 1).left - first.right)
    Box(modifier) {
        Box(Modifier.matchParentSize().opalinePart("UI018", events = events))
        Column(
            Modifier.padding(
                start = dp(first.left - body.left),
                top = dp(first.top - body.top),
                end = dp(first.left - body.left),
                bottom = dp(body.bottom - input.bottom),
            ),
            verticalArrangement = Arrangement.spacedBy(dp(input.top - first.bottom)),
        ) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(gap),
                verticalArrangement = Arrangement.spacedBy(gap),
            ) {
                tags.forEachIndexed { index, text ->
                    Tag(
                        text,
                        recipe.sibling("tag-", index),
                        Modifier.sizeIn(dp(first.width), dp(first.height)),
                    ) { onRemove(index) }
                }
            }
            BasicTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.fillMaxWidth(),
                textStyle = entryStyle(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions =
                    KeyboardActions(
                        onDone = {
                            if (draft.isNotBlank()) {
                                events.raise("commit")
                                onAdd(draft.trim())
                                draft = ""
                            }
                        },
                    ),
                singleLine = true,
                interactionSource = interaction,
                cursorBrush = SolidColor(OpalineColors.accent),
                decorationBox = { field ->
                    FieldDecoration(
                        draft.isEmpty(),
                        focused,
                        false,
                        null,
                        placeholder.takeIf { it.isNotEmpty() }?.let { { Text(it) } },
                        null,
                        null,
                        null,
                        field,
                    )
                },
            )
        }
    }
}

@Composable
private fun Tag(
    text: String,
    part: String,
    modifier: Modifier,
    onRemove: () -> Unit,
) {
    val events = rememberOpalinePartEvents()
    val remove = rememberOpalineDismiss(events, onRemove, "remove")
    Box(
        modifier
            .opalinePart("UI018", part, events = events)
            .clickable(role = Role.Button, onClick = remove),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = OpalineColors.pearl,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** An editable single line in frame [frame] with a placeholder while empty. */
@Composable
private fun EntryText(
    value: String,
    onValueChange: (String) -> Unit,
    frame: String,
    interaction: MutableInteractionSource,
    enabled: Boolean,
    focused: Boolean,
    placeholder: String,
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.recipeFrame(frame).fillMaxWidth(),
        enabled = enabled,
        textStyle = entryStyle(),
        singleLine = true,
        interactionSource = interaction,
        cursorBrush = SolidColor(OpalineColors.accent),
        decorationBox = { field ->
            FieldDecoration(
                value.isEmpty(),
                focused,
                false,
                null,
                placeholder.takeIf { it.isNotEmpty() }?.let { { Text(it) } },
                null,
                null,
                null,
                field,
            )
        },
    )
}

/** A glyph centred on a part the recipe gives no content frame. */
@Composable
private fun Glyph(
    icon: ImageVector,
    description: String?,
    modifier: Modifier,
) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Icon(icon, description, Modifier.fillMaxSize(), tint = OpalineColors.pearl)
    }
}

@Composable
private fun entryStyle() = MaterialTheme.typography.bodyLarge.copy(color = OpalineColors.text)
