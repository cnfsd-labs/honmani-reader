package app.honmani.reader.ui.reader

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalResources
import app.honmani.reader.R

/**
 * Shows each change made from [viewModel]'s lookup panel ("Mark as learned", "Remove from Words")
 * in [hostState] with an undo, as the word list does; the next waits until it is gone.
 */
@Composable
fun PanelUndo(viewModel: ReaderViewModel, hostState: SnackbarHostState) {
    val resources = LocalResources.current
    LaunchedEffect(viewModel, hostState) {
        viewModel.changes.collect { change ->
            val message = resources.getString(
                when (change) {
                    is PanelChange.Learned -> R.string.word_moved_to_learned
                    is PanelChange.Removed -> R.string.word_removed_from_words
                },
                change.expression,
            )
            val result = hostState.showSnackbar(message, resources.getString(R.string.action_undo), duration = SnackbarDuration.Short)
            if (result == SnackbarResult.ActionPerformed) viewModel.undo(change)
        }
    }
}
