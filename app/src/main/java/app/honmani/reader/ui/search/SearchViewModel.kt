package app.honmani.reader.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.honmani.reader.HonmaniApplication
import app.honmani.reader.diagnostics.logWarning
import app.honmani.reader.lookup.LookupGroup
import app.honmani.reader.lookup.LookupGroups
import app.honmani.reader.lookup.LookupResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What a dictionary search found for [query] (the text as typed when it ran). */
data class SearchResults(
    val query: String = "",
    val groups: List<LookupGroup> = emptyList(),
    /** False when no dictionary is installed: nothing can be found. */
    val dictionariesAvailable: Boolean = true,
    /** True while English search is still being prepared (see [LookupResult.englishPending]). */
    val englishPending: Boolean = false,
    /** True when the search threw: shown as an error, not as "nothing found". */
    val failed: Boolean = false,
)

/**
 * The dictionary search in Words: results follow the text as it is typed (no search button),
 * a short pause after the last key. Never saves a word; see [app.honmani.reader.ui.reader.ReaderViewModel.SearchFactory].
 */
class SearchViewModel(
    private val search: suspend (String) -> LookupResult,
    /** The last searches, newest first. */
    val history: StateFlow<List<String>> = MutableStateFlow(emptyList()),
    private val addToHistory: (String) -> Unit = {},
    private val removeFromHistory: (String) -> Unit = {},
    private val clearHistory: () -> Unit = {},
    private val compute: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _results = MutableStateFlow(SearchResults())
    val results: StateFlow<SearchResults> = _results.asStateFlow()

    init {
        viewModelScope.launch {
            @OptIn(FlowPreview::class)
            _query.debounce { if (it.isBlank()) 0L else DEBOUNCE_MS }.collectLatest { text ->
                _results.value = if (text.isBlank()) SearchResults() else run(text)
            }
        }
    }

    private suspend fun run(text: String): SearchResults = try {
        val result = withContext(compute) { search(text) }
        SearchResults(text, withContext(compute) { LookupGroups.from(result, MAX_GROUPS) }, result.dictionariesAvailable, result.englishPending)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // A damaged dictionary row must not take the app down while typing.
        logWarning("Honmani.search", "Search failed", e)
        SearchResults(text, failed = true)
    }

    fun setQuery(text: String) {
        _query.value = text
    }

    /** The current text is worth keeping: a result was opened or the keyboard's search pressed. */
    fun remember() = addToHistory(_query.value)

    fun forget(query: String) = removeFromHistory(query)

    fun forgetAll() = clearHistory()

    companion object {
        /** Pause after the last key before searching. */
        const val DEBOUNCE_MS = 150L

        /** Words shown at most. */
        const val MAX_GROUPS = 60

        val Factory = viewModelFactory {
            initializer {
                val container = (this[APPLICATION_KEY] as HonmaniApplication).container
                val history = container.searchHistory
                SearchViewModel(
                    search = { container.dictionaryLookup.search(it) },
                    history = history.entries,
                    addToHistory = history::add,
                    removeFromHistory = history::remove,
                    clearHistory = history::clear,
                )
            }
        }
    }
}
