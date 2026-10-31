package app.honmani.reader.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.honmani.reader.R
import app.honmani.reader.data.words.UnknownWords
import app.honmani.reader.ui.stats.StatisticsViewModel
import app.honmani.reader.ui.stats.number
import app.honmani.reader.ui.words.openInDictionary

/**
 * The words of a book the reader does not know yet, the most used first (at most
 * [UnknownWords.LIMIT]), to look over before reading it. A tap opens the word in the dictionary.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UnknownWordsSheet(contentHash: String, viewModel: StatisticsViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val flow = remember(contentHash) { viewModel.unknownWords(contentHash) }
    val unknown: UnknownWords? by flow.collectAsStateWithLifecycle(null)
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.navigationBarsPadding()) {
            Text(
                text = stringResource(R.string.unknown_words_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            val found = unknown
            when {
                found == null -> CircularProgressIndicator(
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(32.dp),
                )
                found.words.isEmpty() -> Text(
                    text = stringResource(R.string.book_coverage_all),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(32.dp),
                )
                else -> {
                    Text(
                        text = if (found.total > found.words.size) {
                            stringResource(R.string.unknown_words_capped, number(found.words.size.toLong()), number(found.total.toLong()))
                        } else {
                            pluralStringResource(R.plurals.unknown_words_all, found.total, number(found.total.toLong()))
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 8.dp),
                    )
                    LazyColumn {
                        itemsIndexed(found.words, key = { _, word -> word.key }) { _, word ->
                            ListItem(
                                headlineContent = { Text(word.key) },
                                trailingContent = {
                                    Text(
                                        text = pluralStringResource(R.plurals.unknown_words_uses, word.uses, number(word.uses.toLong())),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                modifier = Modifier.clickable { openInDictionary(context, word.key) },
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}
