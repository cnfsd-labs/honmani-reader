package app.honmani.reader.ui.review

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.honmani.reader.R
import app.honmani.reader.data.library.BookEntity
import app.honmani.reader.data.review.ReviewScope

/** The chip chosen on the Review card, the library's books for "A book…", and what a tap on a chip does. */
class ReviewScopeChoice(
    val scope: ReviewScope = ReviewScope.Due,
    val books: List<BookEntity> = emptyList(),
    val onChange: (ReviewScope) -> Unit = {},
)

/**
 * The review's entrance, pinned at the bottom of Words within thumb's reach: how many cards are due
 * and how many new words the session would start, Start, and the chips for what to work on. With
 * large text it stops growing at [MAX_SCREEN_SHARE] of the screen and scrolls.
 */
@Composable
fun ReviewEntry(
    due: Int,
    newWords: Int,
    onStart: () -> Unit,
    modifier: Modifier = Modifier,
    choice: ReviewScopeChoice = ReviewScopeChoice(),
) {
    // The counts are for "Due"; another chip can also review words early.
    val nothing = due + newWords == 0 && choice.scope == ReviewScope.Due
    var pickBook by remember { mutableStateOf(false) }
    // Under another chip the counts would be wrong: the button reads plain "Start".
    val startDescription =
        if (choice.scope == ReviewScope.Due) stringResource(R.string.review_start_description, due, newWords) else null
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .heightIn(max = (LocalConfiguration.current.screenHeightDp * MAX_SCREEN_SHARE).dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        if (due > 0) stringResource(R.string.review_card_title, due) else stringResource(R.string.review_card_title_none),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    val subtitle = when {
                        nothing -> stringResource(R.string.review_card_nothing)
                        newWords > 0 -> pluralStringResource(R.plurals.review_card_new, newWords, newWords)
                        else -> null
                    }
                    if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium)
                }
                Button(
                    onClick = onStart,
                    enabled = !nothing,
                    modifier = Modifier
                        .padding(start = 12.dp)
                        .heightIn(min = 48.dp)
                        .then(if (startDescription != null) Modifier.semantics { contentDescription = startDescription } else Modifier),
                ) { Text(stringResource(R.string.review_start)) }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(top = 4.dp),
            ) {
                @Composable
                fun chip(scope: ReviewScope, label: String, onClick: () -> Unit = { choice.onChange(scope) }) =
                    FilterChip(
                        selected = choice.scope == scope,
                        onClick = onClick,
                        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 160.dp)) },
                    )
                chip(ReviewScope.Due, stringResource(R.string.review_chip_due))
                chip(ReviewScope.ThisBook, stringResource(R.string.review_chip_this_book))
                val book = choice.scope as? ReviewScope.Book
                chip(book ?: ReviewScope.Book(-1, ""), book?.title ?: stringResource(R.string.review_chip_a_book)) { pickBook = true }
                chip(ReviewScope.Starred, stringResource(R.string.review_chip_starred))
                chip(ReviewScope.AllLearning, stringResource(R.string.review_chip_all_learning))
            }
        }
    }
    if (pickBook) {
        AlertDialog(
            onDismissRequest = { pickBook = false },
            title = { Text(stringResource(R.string.review_pick_book)) },
            text = {
                LazyColumn {
                    items(choice.books, key = { it.id }) { book ->
                        Text(
                            book.title,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    pickBook = false
                                    choice.onChange(ReviewScope.Book(book.id, book.title))
                                }
                                .heightIn(min = 48.dp)
                                .padding(vertical = 12.dp),
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { pickBook = false }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }
}

private const val MAX_SCREEN_SHARE = 0.4f
