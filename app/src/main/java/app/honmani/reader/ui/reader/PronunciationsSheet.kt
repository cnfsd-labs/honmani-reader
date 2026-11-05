package app.honmani.reader.ui.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.honmani.reader.R
import app.honmani.reader.data.library.PRONUNCIATION_READING_MAX
import app.honmani.reader.data.library.PRONUNCIATION_TEXT_MAX
import app.honmani.reader.data.library.PronunciationEntity
import kotlinx.coroutines.launch

/**
 * Read aloud pronunciations of this book: "read this text as that" pairs applied before the voice
 * speaks. A row edits, the bin removes with an Undo; the plus adds one; below, words whose furigana
 * the reader corrected are offered with a tap.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PronunciationsSheet(
    items: List<PronunciationEntity>,
    /** Corrected furigana of the Words list as (text, reading), minus what is already here. */
    suggestions: List<Pair<String, String>>,
    onSave: (text: String, reading: String) -> Unit,
    onDelete: (PronunciationEntity) -> Unit,
    onUndoDelete: (PronunciationEntity) -> Unit,
    onDismiss: () -> Unit,
    /** Back (not a tap outside): returns to the reader menu; null closes like a tap outside. */
    onBack: (() -> Unit)? = null,
) {
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val removed = stringResource(R.string.pronunciation_removed)
    val undo = stringResource(R.string.action_undo)
    // The pair being edited; ("" to "") is a new one.
    var editing by remember { mutableStateOf<Pair<String, String>?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss, properties = menuReturnSheetProperties(onBack)) {
        MenuReturnBack(onBack)
        Box(modifier = Modifier.navigationBarsPadding()) {
            LazyColumn(modifier = Modifier.padding(bottom = 48.dp)) {
                item {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.pronunciation_title), style = MaterialTheme.typography.titleMedium) },
                        supportingContent = { Text(stringResource(R.string.pronunciation_explain)) },
                        trailingContent = {
                            IconButton(onClick = { editing = "" to "" }) {
                                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.pronunciation_add))
                            }
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                }
                items(items, key = { it.id }) { item ->
                    ListItem(
                        headlineContent = { Text("${item.text} → ${item.reading}", maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        trailingContent = {
                            IconButton(onClick = {
                                onDelete(item)
                                scope.launch {
                                    snackbar.currentSnackbarData?.dismiss()
                                    val result = snackbar.showSnackbar(removed, undo, duration = SnackbarDuration.Long)
                                    if (result == SnackbarResult.ActionPerformed) onUndoDelete(item)
                                }
                            }) {
                                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.pronunciation_delete))
                            }
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.clickable { editing = item.text to item.reading },
                    )
                }
                if (suggestions.isNotEmpty()) {
                    item {
                        HorizontalDivider()
                        Text(
                            stringResource(R.string.pronunciation_suggestions),
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp),
                        )
                    }
                    items(suggestions, key = { it.first }) { (text, reading) ->
                        ListItem(
                            headlineContent = { Text("$text → $reading", maxLines = 2, overflow = TextOverflow.Ellipsis) },
                            trailingContent = {
                                TextButton(onClick = { onSave(text, reading) }) { Text(stringResource(R.string.action_add)) }
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        )
                    }
                }
            }
            SnackbarHost(snackbar, modifier = Modifier.align(Alignment.BottomCenter))
        }
    }
    editing?.let { (text, reading) ->
        PronunciationDialog(
            initialText = text,
            initialReading = reading,
            onSave = { t, r ->
                onSave(t, r)
                editing = null
            },
            onDismiss = { editing = null },
        )
    }
}

@Composable
private fun PronunciationDialog(initialText: String, initialReading: String, onSave: (String, String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initialText) }
    var reading by remember { mutableStateOf(initialReading) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.pronunciation_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.take(PRONUNCIATION_TEXT_MAX) },
                    label = { Text(stringResource(R.string.pronunciation_text_label)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = reading,
                    onValueChange = { reading = it.take(PRONUNCIATION_READING_MAX) },
                    label = { Text(stringResource(R.string.pronunciation_reading_label)) },
                    singleLine = true,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(text.trim(), reading.trim()) }, enabled = text.isNotBlank() && reading.isNotBlank()) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
