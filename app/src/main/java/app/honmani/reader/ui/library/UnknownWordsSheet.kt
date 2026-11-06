package app.honmani.reader.ui.library

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import app.honmani.reader.ui.PullDownSheet
import app.honmani.reader.ui.stats.StatisticsViewModel
import app.honmani.reader.ui.stats.number
import app.honmani.reader.ui.words.openInDictionary

/** "Top 20" ticks this many words. */
private const val PREPARE_TOP = 20

/**
 * The words of a book the reader does not know yet, the most used first (at most
 * [UnknownWords.LIMIT]), to choose from before reading it. A tap on a word ticks it, the
 * magnifier beside it takes a look in the dictionary without making it a word being learned.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UnknownWordsSheet(contentHash: String, viewModel: StatisticsViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val flow = remember(contentHash) { viewModel.unknownWords(contentHash) }
    val unknown: UnknownWords? by flow.collectAsStateWithLifecycle(null)
    // Words ticked to learn before reading ("Prepare"), most used first like the list.
    val chosen = remember(contentHash) { mutableStateListOf<String>() }
    var preparing by remember { mutableStateOf(false) }
    PullDownSheet(onDismiss = onDismiss) {
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
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 24.dp, end = 16.dp),
                    ) {
                        Text(
                            text = if (found.total > found.words.size) {
                                stringResource(R.string.unknown_words_capped, number(found.words.size.toLong()), number(found.total.toLong()))
                            } else {
                                pluralStringResource(R.plurals.unknown_words_all, found.total, number(found.total.toLong()))
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .weight(1f)
                                .padding(vertical = 4.dp),
                        )
                        if (chosen.isNotEmpty()) {
                            TextButton(onClick = { chosen.clear() }) { Text(stringResource(R.string.action_clear)) }
                        }
                        TextButton(onClick = {
                            chosen.clear()
                            chosen.addAll(found.words.take(PREPARE_TOP).map { it.key })
                        }) { Text(stringResource(R.string.prepare_top, PREPARE_TOP)) }
                    }
                    LazyColumn(modifier = Modifier.weight(1f, fill = false), overscrollEffect = null) {
                        itemsIndexed(found.words, key = { _, word -> word.key }) { _, word ->
                            val selected = word.key in chosen
                            ListItem(
                                headlineContent = { Text(word.key) },
                                leadingContent = { Checkbox(checked = selected, onCheckedChange = null) },
                                trailingContent = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = pluralStringResource(R.plurals.unknown_words_uses, word.uses, number(word.uses.toLong())),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        IconButton(onClick = { openInDictionary(context, word.key, preview = true) }) {
                                            Icon(
                                                Icons.Filled.Search,
                                                contentDescription = stringResource(R.string.unknown_words_look_up, word.key),
                                            )
                                        }
                                    }
                                },
                                colors = ListItemDefaults.colors(
                                    containerColor = if (selected) {
                                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = SELECTED_ALPHA)
                                    } else {
                                        Color.Transparent
                                    },
                                ),
                                modifier = Modifier.clickable { if (selected) chosen -= word.key else chosen += word.key },
                            )
                            HorizontalDivider()
                        }
                    }
                    if (chosen.isNotEmpty()) {
                        Button(
                            enabled = !preparing,
                            onClick = {
                                preparing = true
                                val order = found.words.map { it.key }.filter { it in chosen }
                                viewModel.prepare(contentHash, order) { added ->
                                    preparing = false
                                    chosen.clear()
                                    Toast.makeText(
                                        context,
                                        context.resources.getQuantityString(R.plurals.prepare_done, added, added),
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 24.dp, vertical = 12.dp),
                        ) { Text(pluralStringResource(R.plurals.prepare_button, chosen.size, chosen.size)) }
                    }
                }
            }
        }
    }
}

private const val SELECTED_ALPHA = 0.5f
