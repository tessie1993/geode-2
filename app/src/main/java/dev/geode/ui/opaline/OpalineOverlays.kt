package dev.geode.ui.opaline

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RangeSlider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import kotlin.math.max

internal val LocalOpalineActionSlot = staticCompositionLocalOf<OpalineActionSlot?> { null }

private val LocalOpalineMenu = staticCompositionLocalOf { "UI028" }

/**
 * Raises [event] (close → return-to-mount by default) on [events] and waits for its transition to
 * settle before [onDismiss] removes the node.
 */
@Composable
internal fun rememberOpalineDismiss(
    events: OpalinePartEvents,
    onDismiss: () -> Unit,
    event: String = "close",
): () -> Unit {
    val scope = rememberCoroutineScope()
    val latest by rememberUpdatedState(onDismiss)
    var closing by remember { mutableStateOf(false) }
    return {
        if (!closing) {
            closing = true
            scope.launch {
                events.raise(event)
                events.awaitSettled()
                closing = false
                latest()
            }
        }
    }
}

/** Raises the recipe's focus / blur bindings on [events] as [interaction] gains or loses focus. */
@Composable
internal fun RaiseFocusEvents(
    interaction: MutableInteractionSource,
    events: OpalinePartEvents,
) {
    LaunchedEffect(interaction, events) {
        interaction.interactions.collect {
            if (it is FocusInteraction.Focus) events.raise("focus")
            if (it is FocusInteraction.Unfocus) events.raise("blur")
        }
    }
}

/** Each separate Android window owns its scene: bounds never leak into the underlying page. */
@Composable
fun OpalineDialog(
    onDismissRequest: () -> Unit,
    properties: DialogProperties = DialogProperties(),
    content: @Composable () -> Unit,
) {
    Dialog(onDismissRequest, properties = properties) {
        OpalineSceneHost(
            Modifier.widthIn(max = 560.dp).clip(RoundedCornerShape(32.dp)),
            environment = false,
        ) { content() }
    }
}

/**
 * UI042 Dialog shell: body C01/shell with icon, title and text in the `content` frame; the
 * dismiss action on secondary A05/nacre, the confirm action on primary A05/gel. Open → lift;
 * a dismiss request plays close → return-to-mount first.
 */
@Composable
fun OpalineAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    properties: DialogProperties = DialogProperties(),
) {
    val events = rememberOpalinePartEvents()
    LaunchedEffect(events) { events.raise("open") }
    OpalineDialog(rememberOpalineDismiss(events, onDismissRequest), properties) {
        OpalineRecipeLayout(
            "UI042",
            modifier,
            touch = "primary",
            fitWidth = true,
            flex = "content",
            flexAlignment = Alignment.TopCenter,
        ) {
            Box(Modifier.recipePart("body").opalinePart("UI042", events = events))
            Column(
                Modifier.recipeFrame("content").fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                icon?.invoke()
                title?.invoke()
                Box(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                    text?.invoke()
                }
            }
            if (dismissButton != null) ActionSlot("UI042", "secondary", events, dismissButton)
            ActionSlot("UI042", "primary", events, confirmButton)
        }
    }
}

/** A UI042–UI044 action part holding [content]: the action composed in it becomes that part. */
@Composable
internal fun ActionSlot(
    composition: String,
    part: String,
    events: OpalinePartEvents,
    content: @Composable () -> Unit,
) {
    val slot =
        remember(composition, part, events) {
            OpalineActionSlot(composition, part, "$part-content", events)
        }
    Box(Modifier.recipePart(part)) {
        CompositionLocalProvider(LocalOpalineActionSlot provides slot, content = content)
    }
}

/**
 * UI011 Single-line input (C02/shell, D14/glow focus rail), UI012 Text area when multi-line (C01/
 * shell, B23/nacre resize grip) or UI014 Secure-entry field for a password transformation (C02/
 * shell, A03/nacre visibility toggle). A BasicTextField in the `input` frame owns caret,
 * selection and IME; focus/blur raise the recipe's illuminate/settle bindings. [shape] is kept for
 * source compatibility: the recipe's body is the shape.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun OpalineTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    textStyle: TextStyle = MaterialTheme.typography.bodyLarge,
    label: (@Composable () -> Unit)? = null,
    placeholder: (@Composable () -> Unit)? = null,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    supportingText: (@Composable () -> Unit)? = null,
    isError: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    minLines: Int = 1,
    shape: Shape = RoundedCornerShape(24.dp),
) {
    val secure = visualTransformation is PasswordVisualTransformation
    val recipe =
        when {
            secure -> "UI014"
            singleLine -> "UI011"
            else -> "UI012"
        }
    val area = recipe == "UI012"
    val spec = rememberOpalineComposition(recipe)
    val lit =
        remember(spec) { spec.behaviorBindings.filter { it.event == "focus" }.map { it.target } }
    val events = rememberOpalinePartEvents()
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    var revealed by remember { mutableStateOf(false) }
    var measured by remember { mutableStateOf(IntSize.Zero) }
    var height by remember { mutableFloatStateOf(0f) }
    val density = LocalDensity.current
    RaiseFocusEvents(interaction, events)
    OpalineRecipeLayout(
        recipe,
        modifier
            .onSizeChanged { measured = it }
            .then(if (height > 0f) Modifier.height(with(density) { height.toDp() }) else Modifier),
        touch = if (area) null else "body",
        fitWidth = area,
        flex = "input",
        flexAlignment = if (area) Alignment.TopStart else Alignment.CenterStart,
    ) {
        Box(
            Modifier.recipePart("body").opalinePart(
                recipe,
                selected = focused && "body" in lit,
                enabled = enabled,
                events = events,
            ),
        )
        when (recipe) {
            "UI011" ->
                Box(
                    Modifier.recipePart("focus-rail").opalinePart(
                        recipe,
                        "focus-rail",
                        selected = focused && "focus-rail" in lit,
                        enabled = enabled,
                        events = events,
                    ),
                )

            "UI012" ->
                Box(
                    Modifier
                        .recipePart("grip")
                        .opalinePart(recipe, "grip", enabled = enabled, events = events)
                        .pointerInput(spec) {
                            // UI012 drag → resize grip, never below the binding's minimum height.
                            val minimum =
                                ((spec.binding("resize")["minimum"] as List<*>)[1] as Number)
                                    .toFloat() / spec.part("body").dimensions.x
                            detectDragGestures(
                                onDragStart = {
                                    if (height == 0f) height = measured.height.toFloat()
                                },
                            ) { change, drag ->
                                change.consume()
                                height = max(height + drag.y, measured.width * minimum)
                            }
                        },
                )

            else ->
                Box(
                    Modifier
                        .recipePart("visibility")
                        .opalinePart(
                            recipe,
                            "visibility",
                            selected = revealed,
                            enabled = enabled,
                            events = events,
                        ).toggleable(revealed, enabled = enabled, role = Role.Switch) {
                            revealed = it
                            events.raise("activate")
                        },
                ) {
                    Icon(
                        if (revealed) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        null,
                        Modifier.fillMaxSize(),
                        tint = OpalineColors.pearl,
                    )
                }
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.recipeFrame("input").fillMaxWidth(),
            enabled = enabled,
            readOnly = readOnly,
            textStyle = textStyle.copy(color = textStyle.color.takeOrElse { OpalineColors.text }),
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            singleLine = singleLine,
            maxLines = maxLines,
            minLines = minLines,
            visualTransformation =
                if (secure && revealed) VisualTransformation.None else visualTransformation,
            interactionSource = interaction,
            cursorBrush = SolidColor(OpalineColors.accent),
            decorationBox = { field ->
                FieldDecoration(
                    value.isEmpty(),
                    focused,
                    isError,
                    label,
                    placeholder,
                    leadingIcon,
                    trailingIcon,
                    supportingText,
                    field,
                )
            },
        )
    }
}

/** Label above, placeholder under the caret, icons beside and supporting text below the field. */
@Composable
internal fun FieldDecoration(
    empty: Boolean,
    focused: Boolean,
    isError: Boolean,
    label: (@Composable () -> Unit)?,
    placeholder: (@Composable () -> Unit)?,
    leadingIcon: (@Composable () -> Unit)?,
    trailingIcon: (@Composable () -> Unit)?,
    supportingText: (@Composable () -> Unit)?,
    field: @Composable () -> Unit,
) {
    val tone =
        when {
            isError -> OpalineColors.error
            focused -> OpalineColors.accent
            else -> OpalineColors.muted
        }
    val typography = MaterialTheme.typography
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        label?.let { Styled(typography.labelMedium, tone, it) }
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            leadingIcon?.invoke()
            Box(Modifier.weight(1f)) {
                if (empty && placeholder != null) {
                    Styled(typography.bodyLarge, OpalineColors.muted, placeholder)
                }
                field()
            }
            trailingIcon?.invoke()
        }
        supportingText?.let {
            Styled(typography.bodySmall, if (isError) tone else OpalineColors.muted, it)
        }
    }
}

@Composable
private fun Styled(
    style: TextStyle,
    color: Color,
    content: @Composable () -> Unit,
) = CompositionLocalProvider(
    LocalTextStyle provides style,
    LocalContentColor provides color,
    content = content,
)

/**
 * UI028 Dropdown menu: body C03/shell, rows A05/gel; [recipe] = "UI027" is the context menu. The
 * popup is its own window and hosts its own scene; rows beyond the recipe's count reuse row-0.
 */
@Composable
fun OpalineDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    recipe: String = "UI028",
    content: @Composable ColumnScope.() -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        shape = RoundedCornerShape(28.dp),
        containerColor = Color.Transparent,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        val (padding, gap) = rememberRecipeRail(recipe, "body", "row-0")
        OpalineSceneHost(environment = false) {
            Box {
                Box(Modifier.matchParentSize().opalinePart(recipe))
                CompositionLocalProvider(LocalOpalineMenu provides recipe) {
                    Column(
                        Modifier.widthIn(min = 220.dp, max = 340.dp).padding(padding),
                        verticalArrangement = Arrangement.spacedBy(gap),
                        content = content,
                    )
                }
            }
        }
    }
}

/** A row of the enclosing UI028/UI027 menu: A05/gel, content in its `item` frame; → select. */
@Composable
fun OpalineDropdownMenuItem(
    text: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
) {
    OpalinePressable(
        LocalOpalineMenu.current,
        "row-0",
        "item-0",
        onClick,
        modifier.fillMaxWidth(),
        enabled,
        alignment = Alignment.CenterStart,
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            leadingIcon?.invoke()
            Box(Modifier.weight(1f)) { text() }
            trailingIcon?.invoke()
        }
    }
}

/**
 * Text action: UI042's secondary A05/nacre capsule; inside a dialog's confirm slot it is the
 * primary A05/gel capsule.
 */
@Composable
fun OpalineAction(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    OpalinePressable("UI042", "secondary", "secondary-content", onClick, modifier, enabled) {
        Row(verticalAlignment = Alignment.CenterVertically, content = content)
    }
}

/** UI003 Circular action (A22/gel) holding the caller's icon in its `content` frame. */
@Composable
fun OpalineIconAction(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    OpalinePressable(
        "UI003",
        "body",
        "content",
        onClick,
        modifier,
        enabled,
        shape = CircleShape,
        flexible = false,
        content = content,
    )
}

/** UI006 Checkbox well: body C13/shell, selection A04/gel while checked; → toggle-selection. */
@Composable
fun OpalineCheckbox(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val events = rememberOpalinePartEvents()
    val toggle =
        onCheckedChange?.let { change ->
            Modifier.toggleable(checked, enabled = enabled, role = Role.Checkbox) {
                events.raise("activate")
                change(it)
            }
        } ?: Modifier
    OpalineRecipeLayout("UI006", modifier.then(toggle), touch = "body") {
        Box(Modifier.recipePart("body").opalinePart("UI006", enabled = enabled, events = events))
        if (checked) {
            Box(
                Modifier.recipePart("selection").opalinePart(
                    "UI006",
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
 * UI021 Range slider: body B04/gel, value ← start, secondaryValue ← end. The Material slider
 * keeps direct manipulation; thumbs never cross and keep motion.js MotionController.setValue's
 * B04 gap of .14 of the range.
 */
@Composable
fun OpalineRangeSlider(
    value: ClosedFloatingPointRange<Float>,
    onValueChange: (ClosedFloatingPointRange<Float>) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    onValueChangeFinished: (() -> Unit)? = null,
) {
    val span = valueRange.endInclusive - valueRange.start
    val start = value.start.takeIf { it.isFinite() }?.coerceIn(valueRange) ?: valueRange.start
    val end =
        value.endInclusive
            .takeIf { it.isFinite() }
            ?.coerceIn(start, valueRange.endInclusive) ?: start
    val gap = .14f * span
    RangeSlider(
        value = start..end,
        onValueChange = { next ->
            onValueChange(
                if (next.start != start) {
                    next.start.coerceAtMost(end - gap).coerceAtLeast(valueRange.start)..end
                } else {
                    start..
                        next.endInclusive
                            .coerceAtLeast(start + gap)
                            .coerceAtMost(valueRange.endInclusive)
                },
            )
        },
        modifier =
            modifier.opalinePart(
                "UI021",
                value = if (span > 0) (start - valueRange.start) / span else 0f,
                secondaryValue = if (span > 0) (end - valueRange.start) / span else 0f,
                enabled = enabled,
            ),
        enabled = enabled,
        valueRange = valueRange,
        onValueChangeFinished = onValueChangeFinished,
        colors = opalineSliderColors(),
    )
}
