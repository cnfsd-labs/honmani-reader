package app.honmani.reader.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.honmani.reader.R
import app.honmani.reader.data.words.WordEntity
import app.honmani.reader.lookup.LookupGroup

/** The dictionary search field: results follow every key, so the keyboard's search key only closes it. */
@Composable
fun SearchField(
    query: String,
    onQuery: (String) -> Unit,
    focusRequester: FocusRequester,
    onFocusChanged: (Boolean) -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQuery,
        singleLine = true,
        placeholder = { Text(stringResource(R.string.search_hint), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = {
                    onQuery("")
                    focusRequester.requestFocus()
                }) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.words_search_clear))
                }
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
        modifier = modifier
            .fillMaxWidth()
            .focusRequester(focusRequester)
            .onFocusChanged { onFocusChanged(it.isFocused) },
    )
}

/**
 * Below the field while searching: the dictionaries' words, then the saved words that match
 * ([words], "In your words"); with an empty field, the last searches.
 */
@Composable
fun SearchContent(
    query: String,
    results: SearchResults,
    words: List<WordEntity>,
    history: List<String>,
    onOpenGroup: (LookupGroup) -> Unit,
    onOpenWord: (WordEntity) -> Unit,
    onHistory: (String) -> Unit,
    onForget: (String) -> Unit,
    onForgetAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // New results start at the top: the list would otherwise follow a word that moved down.
    val listState = rememberLazyListState()
    LaunchedEffect(results.query) { listState.scrollToItem(0) }
    LazyColumn(modifier = modifier, state = listState) {
        if (query.isBlank()) {
            if (history.isNotEmpty()) {
                item(key = "history") {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp, end = 8.dp)) {
                        SectionTitle(stringResource(R.string.search_history), Modifier.weight(1f))
                        TextButton(onClick = onForgetAll) { Text(stringResource(R.string.search_history_clear)) }
                    }
                }
                items(history, key = { "h:$it" }) { text ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onHistory(text) }
                            .padding(start = 16.dp),
                    ) {
                        Text(
                            text,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { onForget(text) }) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = stringResource(R.string.search_history_remove, text),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            return@LazyColumn
        }
        when {
            !results.dictionariesAvailable -> item(key = "none") { Message(stringResource(R.string.lookup_no_dictionaries)) }
            results.groups.isEmpty() && results.query == query ->
                item(key = "empty") { Message(stringResource(R.string.search_nothing_found, query.trim())) }
        }
        if (results.englishPending && results.query == query) {
            item(key = "english") { Message(stringResource(R.string.search_english_pending)) }
        }
        // The last results stay until the next ones are ready: no flicker while typing.
        items(results.groups, key = { "d:${it.expression}:${it.reading}" }) { group ->
            ResultRow(
                expression = group.expression,
                reading = group.reading,
                meaning = group.sections.firstOrNull()?.senses.orEmpty().take(MEANINGS).joinToString("; ") { it.glosses.joinToString(", ") },
                onClick = { onOpenGroup(group) },
            )
        }
        if (words.isNotEmpty()) {
            item(key = "words") {
                Column {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    SectionTitle(stringResource(R.string.search_in_your_words), Modifier.padding(horizontal = 16.dp))
                }
            }
            items(words, key = { "w:${it.id}" }) { word ->
                ResultRow(word.expression, word.reading, word.gloss) { onOpenWord(word) }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier
            .padding(vertical = 12.dp)
            .semantics { heading() },
    )
}

@Composable
private fun Message(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(16.dp),
    )
}

/** A word: written form and reading on one line, its first meanings below. */
@Composable
private fun ResultRow(expression: String, reading: String, meaning: String, onClick: () -> Unit) {
    Column(
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(expression, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            if (reading.isNotEmpty() && reading != expression) {
                Text(
                    reading,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (meaning.isNotEmpty()) {
            Text(
                meaning,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Meanings a result shows on its line. */
private const val MEANINGS = 3
