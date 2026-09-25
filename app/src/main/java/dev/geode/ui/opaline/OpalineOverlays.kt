package dev.geode.ui.opaline

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** Each separate Android window owns its scene: bounds never leak into the underlying page. */
@Composable
fun OpalineDialog(
    onDismissRequest: () -> Unit,
    properties: DialogProperties = DialogProperties(),
    content: @Composable () -> Unit,
) {
    Dialog(onDismissRequest, properties = properties) {
        OpalineSceneHost(Modifier.widthIn(max = 560.dp).clip(RoundedCornerShape(32.dp)), environment = false) { content() }
    }
}

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
    OpalineDialog(onDismissRequest, properties) {
        OpalinePanel(modifier) {
            icon?.invoke()
            title?.invoke()
            Box(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) { text?.invoke() }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                dismissButton?.invoke()
                confirmButton()
            }
        }
    }
}

/** C04 quiet editable shell. Caret, selection, IME and error semantics are native Compose. */
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
    OutlinedTextField(
        value,
        onValueChange,
        modifier.opalinePart("C04", enabled = enabled).padding(5.dp),
        enabled = enabled,
        readOnly = readOnly,
        textStyle = textStyle,
        label = label,
        placeholder = placeholder,
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        supportingText = supportingText,
        isError = isError,
        visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        singleLine = singleLine,
        maxLines = maxLines,
        minLines = minLines,
        shape = shape,
        colors =
            OutlinedTextFieldDefaults.colors(
                focusedContainerColor = OpalineColors.deep.copy(alpha = .62f),
                unfocusedContainerColor = OpalineColors.deep.copy(alpha = .50f),
                focusedBorderColor = OpalineColors.accent.copy(alpha = .8f),
                unfocusedBorderColor = Color.Transparent,
            ),
    )
}

@Composable
fun OpalineDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    androidx.compose.material3.DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        shape = RoundedCornerShape(28.dp),
        containerColor = Color.Transparent,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        OpalineSceneHost(Modifier.widthIn(min = 220.dp, max = 340.dp), environment = false) {
            Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp), content = content)
        }
    }
}

@Composable
fun OpalineDropdownMenuItem(
    text: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
) {
    androidx.compose.material3.DropdownMenuItem(
        text = text,
        onClick = onClick,
        modifier = modifier.opalinePart("A05", enabled = enabled),
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        enabled = enabled,
    )
}

/** Slot variants keep existing editor actions on the same native component system. */
@Composable
fun OpalineAction(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    androidx.compose.material3.TextButton(
        onClick = onClick,
        modifier = modifier.opalinePart("A05", enabled = enabled),
        enabled = enabled,
        content = content,
    )
}

@Composable
fun OpalineIconAction(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    androidx.compose.material3.IconButton(onClick, modifier.opalinePart("A03", enabled = enabled), enabled, content = content)
}

@Composable
fun OpalineCheckbox(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    androidx.compose.material3.Checkbox(
        checked,
        onCheckedChange,
        modifier.opalinePart("A03", selected = checked, enabled = enabled),
        enabled = enabled,
        colors =
            androidx.compose.material3.CheckboxDefaults.colors(
                checkedColor = Color.Transparent,
                uncheckedColor = OpalineColors.rim,
                checkmarkColor = OpalineColors.pearl,
            ),
    )
}

@Composable
fun OpalineRangeSlider(
    value: ClosedFloatingPointRange<Float>,
    onValueChange: (ClosedFloatingPointRange<Float>) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
) {
    val span = valueRange.endInclusive - valueRange.start
    val start = value.start.takeIf { it.isFinite() }?.coerceIn(valueRange) ?: valueRange.start
    val end = value.endInclusive.takeIf { it.isFinite() }?.coerceIn(start, valueRange.endInclusive) ?: start
    val ready = opalineReady()
    androidx.compose.material3.RangeSlider(
        value = start..end,
        onValueChange = onValueChange,
        modifier =
            modifier.opalinePart(
                "B04",
                value = if (span > 0) (start - valueRange.start) / span else 0f,
                secondaryValue = if (span > 0) (end - valueRange.start) / span else 0f,
                enabled = enabled,
            ),
        enabled = enabled,
        valueRange = valueRange,
        colors =
            androidx.compose.material3.SliderDefaults.colors(
                thumbColor = if (ready) Color.Transparent else OpalineColors.pearl,
                activeTrackColor = if (ready) Color.Transparent else OpalineColors.accent,
                inactiveTrackColor = if (ready) Color.Transparent else OpalineColors.surface,
            ),
    )
}
