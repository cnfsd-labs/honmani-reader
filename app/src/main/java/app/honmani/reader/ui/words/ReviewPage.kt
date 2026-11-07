package app.honmani.reader.ui.words

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.honmani.reader.R
import app.honmani.reader.data.review.ReviewScope
import app.honmani.reader.ui.PullDownSheet
import app.honmani.reader.ui.review.ReviewScopeChoice
import app.honmani.reader.ui.settings.ReviewSettingsSection
import app.honmani.reader.ui.theme.LocalMotionEnabled

/** What the Review page tells and whether Start works. */
enum class ReviewPageMode {
    /** Cards are due, or new words wait ([ReviewScope.Due]). */
    Due,

    /** Nothing due today. */
    Nothing,

    /** Another group of words, reviewed ahead of schedule. */
    Ahead,

    /** The Starred chip while no learning word is starred. */
    NoStarred,

    /** No word saved yet: nothing to start, nothing to choose. */
    NoWords,
}

class ReviewPageState(val mode: ReviewPageMode, val canStart: Boolean)

/**
 * The page for the chosen [scope]; [starredLearning] counts the starred words in Learning.
 * [hasWords] is false for a person who has saved no word at all.
 */
fun reviewPageState(
    scope: ReviewScope,
    due: Int,
    newWords: Int,
    starredLearning: Int,
    hasWords: Boolean = true,
): ReviewPageState = when {
    !hasWords -> ReviewPageState(ReviewPageMode.NoWords, false)
    scope == ReviewScope.Due || scope == ReviewScope.DueOnly ->
        if (due + (if (scope == ReviewScope.DueOnly) 0 else newWords) == 0) ReviewPageState(ReviewPageMode.Nothing, false) else ReviewPageState(ReviewPageMode.Due, true)
    scope == ReviewScope.Starred && starredLearning == 0 -> ReviewPageState(ReviewPageMode.NoStarred, false)
    else -> ReviewPageState(ReviewPageMode.Ahead, true)
}

/**
 * The Review page of Words: how many cards are due and how many new words wait, Start, what to
 * work on, the review's settings and a way to the statistics. Start opens the review screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewPage(
    due: Int,
    newWords: Int,
    starredLearning: Int,
    onStart: () -> Unit,
    choice: ReviewScopeChoice,
    modifier: Modifier = Modifier,
    /** False while no word is saved: the page then explains where words come from. */
    hasWords: Boolean = true,
    /** The Stats button: on to the Progress tab. */
    onShowStats: () -> Unit = {},
) {
    val state = reviewPageState(choice.scope, due, newWords, starredLearning, hasWords)
    // Reviews only starts no new word.
    val newShown = if (choice.scope == ReviewScope.DueOnly) 0 else newWords
    var pickBook by rememberSaveable { mutableStateOf(false) }
    var settings by rememberSaveable { mutableStateOf(false) }
    // Under another chip the counts would be wrong: the button reads plain "Start".
    val startDescription =
        if (state.mode == ReviewPageMode.Due) stringResource(R.string.review_start_description, due, newShown) else null
    // Counts on top (small), what is due in the middle, and what the thumb needs (the scope
    // chips and a big Start) at the bottom; the page scrolls when the screen is too short.
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .heightIn(min = maxHeight)
                .padding(horizontal = 16.dp, vertical = 16.dp),
        ) {
            Spacer(Modifier.weight(1f))
            when (state.mode) {
                ReviewPageMode.Due -> {
                    val motion = LocalMotionEnabled.current
                    AnimatedContent(
                        targetState = due,
                        transitionSpec = {
                            if (!motion) {
                                EnterTransition.None togetherWith ExitTransition.None
                            } else {
                                val up = targetState < initialState
                                (fadeIn(tween(180)) + slideInVertically(tween(220)) { if (up) it / 2 else -it / 2 }) togetherWith
                                    (fadeOut(tween(120)) + slideOutVertically(tween(220)) { if (up) -it / 2 else it / 2 })
                            }
                        },
                        label = "due count",
                    ) { count ->
                        Text(count.toString(), style = MaterialTheme.typography.displayMedium, color = MaterialTheme.colorScheme.primary)
                    }
                    Text(pluralStringResource(R.plurals.review_page_due, due), style = MaterialTheme.typography.titleMedium)
                    Text(
                        pluralStringResource(R.plurals.review_card_new, newShown, newShown),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                ReviewPageMode.Nothing -> Message(R.string.review_page_nothing_title, R.string.review_page_nothing_body)
                ReviewPageMode.Ahead -> Message(R.string.review_page_ahead_title, R.string.review_page_ahead_body)
                ReviewPageMode.NoStarred -> Message(R.string.review_page_no_starred_title, R.string.review_page_no_starred_body)
                ReviewPageMode.NoWords -> Message(R.string.review_page_no_words_title, R.string.review_page_no_words_body)
            }
            Spacer(Modifier.weight(1f))
            if (state.mode != ReviewPageMode.NoWords) FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                modifier = Modifier.fillMaxWidth(),
            ) {
                @Composable
                fun chip(scope: ReviewScope, label: String, onClick: () -> Unit = { choice.onChange(scope) }) {
                    val selected = choice.scope == scope
                    FilterChip(
                        selected = selected,
                        onClick = onClick,
                        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 160.dp)) },
                        // A tick as well as the tint: a selected chip stands out in every theme.
                        leadingIcon = if (selected) {
                            { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
                        } else {
                            null
                        },
                    )
                }
                chip(ReviewScope.Due, stringResource(R.string.review_chip_due))
                chip(ReviewScope.DueOnly, stringResource(R.string.review_chip_due_only))
                chip(ReviewScope.ThisBook, stringResource(R.string.review_chip_this_book))
                val book = choice.scope as? ReviewScope.Book
                chip(book ?: ReviewScope.Book(-1, ""), book?.title ?: stringResource(R.string.review_chip_a_book)) { pickBook = true }
                chip(ReviewScope.Starred, stringResource(R.string.review_chip_starred))
                chip(ReviewScope.AllLearning, stringResource(R.string.review_chip_all_learning))
            }
            if (state.mode != ReviewPageMode.NoWords) Button(
                onClick = onStart,
                enabled = state.canStart,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 64.dp)
                    .then(if (startDescription != null) Modifier.semantics { contentDescription = startDescription } else Modifier),
            ) { Text(stringResource(R.string.review_start), style = MaterialTheme.typography.titleLarge) }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                modifier = Modifier.fillMaxWidth(),
            ) {
                TextButton(onClick = { settings = true }) { Text(stringResource(R.string.review_page_settings)) }
                TextButton(onClick = onShowStats) { Text(stringResource(R.string.review_page_stats)) }
            }
        }
    }
    if (settings) {
        PullDownSheet(onDismiss = { settings = false }) {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
                ReviewSettingsSection()
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

@Composable
private fun Message(title: Int, body: Int) {
    Text(stringResource(title), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
    Text(
        stringResource(body),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}
