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
    OutlinedTextField(value, onValueChange, modifier.opalinePart("C04", enabled = enabled).padding(5.dp),
        enabled = enabled, readOnly = readOnly, textStyle = textStyle, label = label, placeholder = placeholder,
        leadingIcon = leadingIcon, trailingIcon = trailingIcon, supportingText = supportingText, isError = isError,
        visualTransformation = visualTransformation, keyboardOptions = keyboardOptions, keyboardActions = keyboardActions,
        singleLine = singleLine, maxLines = maxLines, minLines = minLines, shape = shape,
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = OpalineColors.deep.copy(alpha = .62f),
            unfocusedContainerColor = OpalineColors.deep.copy(alpha = .50f),
            focusedBorderColor = OpalineColors.accent.copy(alpha = .8f),
            unfocusedBorderColor = Color.Transparent,
        ))
}
