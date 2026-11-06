package app.honmani.reader.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.honmani.reader.R
import app.honmani.reader.data.library.FOLDER_NAME_MAX
import app.honmani.reader.data.library.FolderResult

/**
 * The folders as a row of chips above the toolbar (scrolling when there are many): All, then each
 * folder with its books, then "New folder"; the chosen folder has a menu to rename or delete it.
 */
@Composable
internal fun FolderBar(
    folders: List<LibraryFolder>,
    chosen: Long?,
    allCount: Int,
    onChoose: (Long?) -> Unit,
    onRename: (LibraryFolder) -> Unit,
    onDelete: (LibraryFolder) -> Unit,
    onNew: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        LazyRow(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item(key = "all") {
                FilterChip(
                    colors = FilterChipDefaults.filterChipColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                    selected = chosen == null,
                    onClick = { onChoose(null) },
                    label = { Text(stringResource(R.string.library_folder_all, allCount), maxLines = 1) },
                )
            }
            items(folders, key = { it.id }) { folder ->
                FilterChip(
                    colors = FilterChipDefaults.filterChipColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                    selected = chosen == folder.id,
                    // Tapping the chosen folder again shows all, like the status filters.
                    onClick = { onChoose(if (chosen == folder.id) null else folder.id) },
                    label = {
                        Text(
                            text = stringResource(R.string.library_folder_chip, folder.name, folder.count),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 200.dp),
                        )
                    },
                    leadingIcon = {
                        Icon(painterResource(R.drawable.ic_folder), contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize))
                    },
                )
            }
            item(key = "new") {
                AssistChip(
                    colors = AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                    onClick = onNew,
                    label = { Text(stringResource(R.string.folder_new_title), maxLines = 1) },
                    leadingIcon = {
                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(AssistChipDefaults.IconSize))
                    },
                )
            }
        }
        val current = folders.firstOrNull { it.id == chosen }
        if (current != null) {
            var open by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { open = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.folder_options))
                }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.folder_rename)) },
                        leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                        onClick = {
                            open = false
                            onRename(current)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.folder_delete)) },
                        leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                        onClick = {
                            open = false
                            onDelete(current)
                        },
                    )
                }
            }
        }
    }
}

/**
 * The chosen folder as one small chip while the folder row is closed, so a filter is never
 * invisible; the cross shows all books again.
 */
@Composable
internal fun ActiveFolderChip(name: String, onClear: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp)) {
        FilterChip(
            colors = FilterChipDefaults.filterChipColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            selected = true,
            onClick = onClear,
            label = {
                Text(text = name, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 240.dp))
            },
            leadingIcon = {
                Icon(painterResource(R.drawable.ic_folder), contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize))
            },
            trailingIcon = {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = stringResource(R.string.library_show_all),
                    modifier = Modifier.size(FilterChipDefaults.IconSize),
                )
            },
        )
    }
}

/**
 * Asks for a folder name, [initial] filled in. [onSave] hands the name on and reports back what
 * came of it; a refused name (blank, taken) is explained in the dialog, which stays open.
 */
@Composable
internal fun FolderNameDialog(
    title: String,
    initial: String,
    confirm: String,
    onSave: (name: String, done: (FolderResult) -> Unit) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initial) }
    var problem by remember { mutableStateOf<FolderResult?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = {
                    name = it.take(FOLDER_NAME_MAX)
                    problem = null
                },
                label = { Text(stringResource(R.string.folder_name_label)) },
                singleLine = true,
                isError = problem != null,
                supportingText = problem?.let { result ->
                    {
                        Text(
                            stringResource(
                                if (result == FolderResult.Blank) R.string.folder_error_blank else R.string.folder_error_duplicate,
                            ),
                        )
                    }
                },
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(name) { result ->
                        if (result is FolderResult.Done) onDismiss() else problem = result
                    }
                },
            ) { Text(confirm) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** Deleting a folder is confirmed first, and says that the books stay. */
@Composable
internal fun DeleteFolderDialog(folder: LibraryFolder, onDelete: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.folder_delete_title, folder.name), maxLines = 3, overflow = TextOverflow.Ellipsis) },
        text = { Text(stringResource(R.string.folder_delete_message)) },
        confirmButton = {
            TextButton(
                onClick = {
                    onDelete()
                    onDismiss()
                },
            ) { Text(stringResource(R.string.action_delete)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** Picks a book's folder: none, one of [folders] (scrolls when there are many), or a new one. */
@Composable
internal fun FolderPickerDialog(
    folders: List<LibraryFolder>,
    chosen: Long?,
    onPick: (Long?) -> Unit,
    onNew: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.book_folder_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                FolderChoice(stringResource(R.string.book_folder_none), chosen == null) { onPick(null) }
                for (folder in folders) {
                    FolderChoice(folder.name, chosen == folder.id) { onPick(folder.id) }
                }
                TextButton(onClick = onNew) { Text(stringResource(R.string.book_folder_new)) }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun FolderChoice(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(text = label, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(vertical = 8.dp))
    }
}
