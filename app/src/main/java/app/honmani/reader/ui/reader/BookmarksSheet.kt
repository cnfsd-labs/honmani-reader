package app.honmani.reader.ui.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
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
import app.honmani.reader.data.library.BOOKMARK_NAME_MAX
import app.honmani.reader.data.library.BookmarkEntity
import kotlinx.coroutines.launch

/**
 * The reader's named bookmarks in this book, in reading order: a tap jumps there, the pencil
 * renames, the bin deletes with an Undo.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookmarksSheet(
    bookmarks: List<BookmarkEntity>,
    onOpen: (BookmarkEntity) -> Unit,
    onRename: (BookmarkEntity, String) -> Unit,
    onDelete: (BookmarkEntity) -> Unit,
    onUndoDelete: (BookmarkEntity) -> Unit,
    onDismiss: () -> Unit,
) {
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var renaming by remember { mutableStateOf<BookmarkEntity?>(null) }
    val deleted = stringResource(R.string.bookmark_deleted)
    val undo = stringResource(R.string.action_undo)
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Box(modifier = Modifier.navigationBarsPadding()) {
            if (bookmarks.isEmpty()) {
                Text(
                    stringResource(R.string.bookmark_list_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            } else {
                LazyColumn(modifier = Modifier.padding(bottom = 48.dp)) {
                    items(bookmarks, key = { it.id }) { bookmark ->
                        ListItem(
                            headlineContent = { Text(bookmark.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = { Text(stringResource(R.string.book_search_position, bookmark.percent)) },
                            trailingContent = {
                                Row {
                                    IconButton(onClick = { renaming = bookmark }) {
                                        Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.bookmark_rename))
                                    }
                                    IconButton(onClick = {
                                        onDelete(bookmark)
                                        scope.launch {
                                            snackbar.currentSnackbarData?.dismiss()
                                            val result = snackbar.showSnackbar(deleted, undo, duration = SnackbarDuration.Long)
                                            if (result == SnackbarResult.ActionPerformed) onUndoDelete(bookmark)
                                        }
                                    }) {
                                        Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.bookmark_delete))
                                    }
                                }
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.clickable { onOpen(bookmark) },
                        )
                    }
                }
            }
            SnackbarHost(snackbar, modifier = Modifier.align(Alignment.BottomCenter))
        }
    }
    renaming?.let { bookmark ->
        BookmarkNameDialog(
            title = stringResource(R.string.bookmark_rename_title),
            initial = bookmark.name,
            onSave = {
                onRename(bookmark, it)
                renaming = null
            },
            onDismiss = { renaming = null },
        )
    }
}

/** A dialog asking for a bookmark's name, [initial] filled in; Save is off for a blank name. */
@Composable
fun BookmarkNameDialog(title: String, initial: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(BOOKMARK_NAME_MAX) },
                label = { Text(stringResource(R.string.bookmark_name_label)) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(name.trim()) }, enabled = name.isNotBlank()) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** A bookmark being named before it is saved: the place and the name suggested from the text there. */
data class BookmarkDraft(val characters: Long, val total: Long, val name: String)
