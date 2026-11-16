package app.honmani.reader.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.honmani.reader.R
import app.honmani.reader.ui.stats.duration
import app.honmani.reader.ui.stats.number

/**
 * The end card of a book that just became finished, by reaching its end in the reader or by
 * "Mark as finished" ([marked]: closing the card offers Undo, back to [previousFinishedAt]).
 */
data class FinishCard(
    val bookId: Long,
    val title: String,
    val next: LibraryBook?,
    val marked: Boolean,
    val previousFinishedAt: Long? = null,
)

/** What the end card tells about the book: time read, lookups made and words saved in it. */
data class FinishFacts(val seconds: Long = 0, val lookups: Int = 0, val words: Int = 0)

object NextBook {
    /**
     * The book to suggest after [finishedId]: of the shown, unfinished books, the best fit for
     * the reader's [skill] (as [LibrarySort.BEST_FIT]) when some are graded and the skill is
     * known; else the most recently opened one being read; else the most recent one. [books] are
     * in the library's recent order (last opened, then last added).
     */
    fun choose(books: List<LibraryBook>, finishedId: Long, skill: Int?, difficulty: (LibraryBook) -> Int?): LibraryBook? {
        val candidates = books.filter { it.id != finishedId && !it.hidden && it.status != ReadingStatus.FINISHED }
        if (skill != null && candidates.any { difficulty(it) != null }) {
            return LibrarySort.BEST_FIT.apply(candidates, skill, difficulty).first()
        }
        return candidates.firstOrNull { it.status == ReadingStatus.READING } ?: candidates.firstOrNull()
    }
}

@Composable
fun FinishCardDialog(card: FinishCard, facts: FinishFacts, onOpenNext: () -> Unit, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.finish_card_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(card.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.finish_card_time, duration(facts.seconds)))
                Text(pluralStringResource(R.plurals.finish_card_lookups, facts.lookups, number(facts.lookups.toLong())))
                Text(pluralStringResource(R.plurals.finish_card_words, facts.words, number(facts.words.toLong())))
                card.next?.let { next ->
                    Text(
                        stringResource(R.string.finish_card_next, next.title),
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
        },
        confirmButton = {
            if (card.next != null) {
                TextButton(onClick = onOpenNext) { Text(stringResource(R.string.finish_card_open)) }
            } else {
                TextButton(onClick = onClose) { Text(stringResource(R.string.action_close)) }
            }
        },
        dismissButton = if (card.next != null) {
            { TextButton(onClick = onClose) { Text(stringResource(R.string.action_close)) } }
        } else {
            null
        },
    )
}
