package app.honmani.reader.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.honmani.reader.R
import app.honmani.reader.data.words.WordEntity
import app.honmani.reader.lookup.LookupGroup

/**
 * The dictionary search field: results follow every key, so the keyboard's search key only closes it.
 * The button at its end opens the radical picker, which inserts a kanji at the cursor.
 */
@Composable
fun SearchField(
    query: String,
    onQuery: (String) -> Unit,
    focusRequester: FocusRequester,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The field keeps its own selection (a plain String has none); [query] may arrive a few keys
    // late, so a value that echoes what was typed is no change from outside (history, clear).
    var field by remember { mutableStateOf(TextFieldValue(query, TextRange(query.length))) }
    val sent = remember { ArrayDeque<String>() }
    LaunchedEffect(query) {
        val echo = sent.indexOf(query)
        if (echo >= 0) {
            repeat(echo + 1) { sent.removeFirst() }
        } else if (query != field.text) {
            sent.clear()
            field = TextFieldValue(query, TextRange(query.length))
        }
    }
    fun change(value: TextFieldValue) {
        val textChanged = value.text != field.text
        field = value
        if (textChanged) {
            sent.addLast(value.text)
            onQuery(value.text)
        }
    }
    var picking by rememberSaveable { mutableStateOf(false) }
    // A thin pill: the page puts it at the bottom, within the thumb's reach.
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 48.dp).padding(start = 16.dp)) {
            Icon(Icons.Filled.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Box(modifier = Modifier.weight(1f).padding(horizontal = 12.dp), contentAlignment = Alignment.CenterStart) {
                if (field.text.isEmpty()) {
                    Text(
                        stringResource(R.string.search_hint),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                BasicTextField(
                    value = field,
                    onValueChange = ::change,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                )
            }
            if (field.text.isNotEmpty()) {
                IconButton(onClick = {
                    change(TextFieldValue(""))
                    focusRequester.requestFocus()
                }) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.words_search_clear))
                }
            }
            val radicalSearch = stringResource(R.string.radical_search)
            IconButton(onClick = { picking = true }, modifier = Modifier.semantics { contentDescription = radicalSearch }) {
                // 部, as in 部首 (radical): no icon says it better.
                Text("部", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
    if (picking) {
        RadicalPicker(
            onPick = { kanji ->
                val at = field.selection
                val text = field.text.replaceRange(at.min, at.max, kanji)
                change(TextFieldValue(text, TextRange(at.min + kanji.length)))
                picking = false
            },
            onDismiss = { picking = false },
        )
    }
}

/**
 * Above the field while searching: the dictionaries' words, then the saved words that match
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
    // Each query's results get their own list position, starting at the top: one shared state
    // would follow a keyed row of the previous results wherever it moved.
    val listState = rememberSaveable(results.query, saver = LazyListState.Saver) { LazyListState() }
    // Reversed: the first result sits right above the field, within the thumb's reach, so the
    // items go bottom to top (a section's title after its rows).
    LazyColumn(modifier = modifier, state = listState, reverseLayout = true) {
        if (query.isBlank()) {
            if (history.isNotEmpty()) {
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
                item(key = "history") {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp, end = 8.dp)) {
                        SectionTitle(stringResource(R.string.search_history), Modifier.weight(1f))
                        TextButton(onClick = onForgetAll) { Text(stringResource(R.string.search_history_clear)) }
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
            items(words, key = { "w:${it.id}" }) { word ->
                ResultRow(word.expression, word.reading, word.gloss) { onOpenWord(word) }
            }
            item(key = "words") {
                Column {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    SectionTitle(stringResource(R.string.search_in_your_words), Modifier.padding(horizontal = 16.dp))
                }
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
