package app.honmani.reader.ui.library

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.honmani.reader.R

/** Asks for the title of the copied [text] before it is added as a book; the title is optional. */
@Composable
fun PasteTextDialog(
    text: String,
    suggestedTitle: String,
    onAdd: (title: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var title by remember { mutableStateOf("") }
    val preview = remember(text) { text.trim().take(PREVIEW_CHARS).map { if (it.isWhitespace()) ' ' else it }.joinToString("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.paste_text_title)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.paste_text_summary, "%,d".format(text.length), preview),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(stringResource(R.string.paste_text_title_label)) },
                    placeholder = { Text(suggestedTitle) },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onAdd(title.trim()) }) { Text(stringResource(R.string.paste_text_add)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}

private const val PREVIEW_CHARS = 30
