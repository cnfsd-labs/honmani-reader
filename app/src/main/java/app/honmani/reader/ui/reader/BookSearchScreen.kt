package app.honmani.reader.ui.reader

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.honmani.reader.R
import app.honmani.reader.data.library.BookText
import app.honmani.reader.data.library.SearchHit
import app.honmani.reader.data.library.SearchResults
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** The text to search, as it loads: [Loading], then the book's [BookText] or [Unavailable]. */
private sealed interface Loaded {
    data object Loading : Loaded
    data object Unavailable : Loaded
    data class Ready(val text: BookText) : Loaded
}

/**
 * Search in the open book: a field and the list of matches in reading order, each with its
 * surroundings (the match in bold) and where in the book it is. A tap on one opens the book
 * there ([onOpenHit] with the book's character total); Back closes the search.
 */
@Composable
fun BookSearchScreen(
    loadText: suspend () -> BookText?,
    onOpenHit: (hit: SearchHit, total: Int) -> Unit,
    onClose: () -> Unit,
) {
    BackHandler(onBack = onClose)
    val loaded by produceState<Loaded>(Loaded.Loading, loadText) {
        value = loadText()?.let { Loaded.Ready(it) } ?: Loaded.Unavailable
    }
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<SearchResults?>(null) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    LaunchedEffect(query, loaded) {
        val ready = loaded as? Loaded.Ready
        if (ready == null || query.isBlank()) {
            results = null
            return@LaunchedEffect
        }
        // Not on every key of a quick typist.
        delay(DEBOUNCE_MS)
        results = withContext(Dispatchers.Default) { ready.text.find(query) }
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(end = 12.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onClose) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text(stringResource(R.string.book_search_hint)) },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = {
                                query = ""
                                focus.requestFocus()
                            }) {
                                Icon(Icons.Filled.Clear, contentDescription = stringResource(R.string.action_clear))
                            }
                        }
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    modifier = Modifier.weight(1f).focusRequester(focus),
                )
            }
            HorizontalDivider()
            Box(modifier = Modifier.fillMaxSize()) {
                when (val state = loaded) {
                    Loaded.Loading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                    Loaded.Unavailable -> Text(
                        stringResource(R.string.book_search_unavailable),
                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                        textAlign = TextAlign.Center,
                    )
                    is Loaded.Ready -> ResultList(results, query.isNotBlank()) { onOpenHit(it, state.text.total) }
                }
            }
        }
    }
}

@Composable
private fun ResultList(results: SearchResults?, asked: Boolean, onOpen: (SearchHit) -> Unit) {
    if (results == null) return
    if (results.hits.isEmpty()) {
        if (asked) {
            Text(
                stringResource(R.string.book_search_none),
                modifier = Modifier.fillMaxWidth().padding(24.dp),
                textAlign = TextAlign.Center,
            )
        }
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            val count = results.hits.size
            Text(
                if (results.more) {
                    stringResource(R.string.book_search_more, count)
                } else {
                    pluralStringResource(R.plurals.book_search_count, count, count)
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        itemsIndexed(results.hits, key = { index, _ -> index }) { _, hit ->
            HitRow(hit, onClick = { onOpen(hit) })
            HorizontalDivider()
        }
    }
}

@Composable
private fun HitRow(hit: SearchHit, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val highlight = MaterialTheme.colorScheme.primary
        Text(
            buildAnnotatedString {
                append(hit.before)
                withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = highlight)) { append(hit.match) }
                append(hit.after)
            },
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            stringResource(R.string.book_search_position, hit.percent),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private const val DEBOUNCE_MS = 200L
