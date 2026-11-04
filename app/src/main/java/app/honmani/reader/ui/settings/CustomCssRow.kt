package app.honmani.reader.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import app.honmani.reader.R
import app.honmani.reader.data.settings.CustomCss

/** A row for the reader's own CSS; a tap opens a multi-line field with Save, Clear and Cancel. */
@Composable
fun CustomCssRow(css: String, onChange: (String) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text(stringResource(R.string.reader_settings_custom_css)) },
        supportingContent = {
            Text(
                if (css.isBlank()) {
                    stringResource(R.string.reader_settings_custom_css_off)
                } else {
                    stringResource(R.string.reader_settings_custom_css_on, CustomCss.bytes(css))
                },
            )
        },
        modifier = Modifier.clickable { open = true },
    )
    if (open) {
        CustomCssDialog(css, onDismiss = { open = false }, onSave = { onChange(it); open = false })
    }
}

@Composable
private fun CustomCssDialog(css: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var draft by rememberSaveable { mutableStateOf(css) }
    val problem = remember(draft) { CustomCss.problem(draft) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.reader_settings_custom_css)) },
        text = {
            Column {
                Text(stringResource(R.string.reader_settings_custom_css_help), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    minLines = 6,
                    maxLines = 12,
                    isError = problem != null,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
                    supportingText = {
                        Text(
                            when (problem) {
                                CustomCss.Problem.TOO_LONG -> stringResource(R.string.reader_settings_custom_css_too_long, CustomCss.MAX_BYTES / 1024)
                                CustomCss.Problem.UNBALANCED -> stringResource(R.string.reader_settings_custom_css_unbalanced)
                                null -> stringResource(R.string.reader_settings_custom_css_count, CustomCss.bytes(draft), CustomCss.MAX_BYTES)
                            },
                        )
                    },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(draft.trim()) }, enabled = problem == null) { Text(stringResource(R.string.reader_settings_custom_css_save)) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { onSave("") }, enabled = css.isNotEmpty() || draft.isNotEmpty()) {
                    Text(stringResource(R.string.reader_settings_custom_css_clear))
                }
                TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
            }
        },
    )
}
