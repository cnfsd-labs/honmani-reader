package app.honmani.reader.ui.reader

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalResources
import app.honmani.reader.R

/**
 * Shows each change made from [viewModel]'s lookup panel ("Mark as learned", "Remove from Words", a review, "Don't count")
 * in [hostState] with an undo, as the word list does; the next waits until it is gone.
 */
@Composable
fun PanelUndo(viewModel: ReaderViewModel, hostState: SnackbarHostState) {
    val resources = LocalResources.current
    LaunchedEffect(viewModel, hostState) {
        viewModel.changes.collect { change ->
            val message = when (change) {
                is PanelChange.Learned -> resources.getString(R.string.word_moved_to_learned, change.expression)
                is PanelChange.Removed -> resources.getString(R.string.word_removed_from_words, change.expression)
                is PanelChange.NotCounted -> resources.getString(R.string.lookup_not_counted, change.expression)
                is PanelChange.Reviewed -> if (change.undo.markedLearned) {
                    resources.getString(R.string.word_moved_to_learned, change.expression)
                } else if (change.undo.nextInDays <= 1) {
                    resources.getString(R.string.panel_review_next_tomorrow, change.expression)
                } else {
                    resources.getString(R.string.panel_review_next_in_days, change.expression, change.undo.nextInDays)
                }
            }
            val result = hostState.showSnackbar(message, resources.getString(R.string.action_undo), duration = SnackbarDuration.Long)
            if (result == SnackbarResult.ActionPerformed) viewModel.undo(change)
        }
    }
}
