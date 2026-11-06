package app.honmani.reader.ui.words

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.dp
import app.honmani.reader.data.words.WordEntity
import app.honmani.reader.lookup.LookupGroup
import app.honmani.reader.ui.search.SearchContent
import app.honmani.reader.ui.search.SearchField
import app.honmani.reader.ui.search.SearchResults

/**
 * The Dictionary page: the results (or the last searches) fill the space, the first one at the
 * bottom, and the search pill sits below them, above the keyboard and the navigation bar.
 * [padding] is what the screen already keeps clear (the bottom bar), so the keyboard adds only the rest.
 */
@Composable
fun DictionaryPage(
    padding: PaddingValues,
    query: String,
    results: SearchResults,
    words: List<WordEntity>,
    history: List<String>,
    focusRequester: FocusRequester,
    onQuery: (String) -> Unit,
    onSearch: () -> Unit,
    onOpenGroup: (LookupGroup) -> Unit,
    onOpenWord: (WordEntity) -> Unit,
    onHistory: (String) -> Unit,
    onForget: (String) -> Unit,
    onForgetAll: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().consumeWindowInsets(padding).imePadding()) {
        SearchContent(
            query = query,
            results = results,
            words = words,
            history = history,
            onOpenGroup = onOpenGroup,
            onOpenWord = onOpenWord,
            onHistory = onHistory,
            onForget = onForget,
            onForgetAll = onForgetAll,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
        SearchField(
            query = query,
            onQuery = onQuery,
            focusRequester = focusRequester,
            onSearch = onSearch,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}
