package app.honmani.reader.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.honmani.reader.R
import app.honmani.reader.data.library.HighlightEntity
import kotlinx.coroutines.launch

/**
 * The reader's highlights in this book, in reading order: a tap jumps there, the bin removes one
 * with an Undo. Each row shows the passage (two lines at most), its colour and where it is.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HighlightsSheet(
    highlights: List<HighlightEntity>,
    onOpen: (HighlightEntity) -> Unit,
    onDelete: (HighlightEntity) -> Unit,
    onUndoDelete: (HighlightEntity) -> Unit,
    onDismiss: () -> Unit,
    /** Back (not a tap outside): returns to the reader menu; null closes like a tap outside. */
    onBack: (() -> Unit)? = null,
) {
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val removed = stringResource(R.string.highlight_removed)
    val undo = stringResource(R.string.action_undo)
    ModalBottomSheet(onDismissRequest = onDismiss, properties = menuReturnSheetProperties(onBack)) {
        MenuReturnBack(onBack)
        Box(modifier = Modifier.navigationBarsPadding()) {
            if (highlights.isEmpty()) {
                Text(
                    stringResource(R.string.highlight_list_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            } else {
                LazyColumn(modifier = Modifier.padding(bottom = 48.dp)) {
                    items(highlights, key = { it.id }) { highlight ->
                        ListItem(
                            leadingContent = {
                                Box(
                                    modifier = Modifier
                                        .size(16.dp)
                                        .background(HIGHLIGHT_SWATCHES[highlight.color.coerceIn(0, HIGHLIGHT_SWATCHES.lastIndex)], CircleShape),
                                )
                            },
                            headlineContent = { Text(highlight.text, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                            supportingContent = { Text(stringResource(R.string.book_search_position, highlight.percent)) },
                            trailingContent = {
                                IconButton(onClick = {
                                    onDelete(highlight)
                                    scope.launch {
                                        snackbar.currentSnackbarData?.dismiss()
                                        val result = snackbar.showSnackbar(removed, undo, duration = SnackbarDuration.Long)
                                        if (result == SnackbarResult.ActionPerformed) onUndoDelete(highlight)
                                    }
                                }) {
                                    Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.highlight_delete))
                                }
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.clickable { onOpen(highlight) },
                        )
                    }
                }
            }
            SnackbarHost(snackbar, modifier = Modifier.align(Alignment.BottomCenter))
        }
    }
}
