package app.honmani.reader.ui.aozora

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.honmani.reader.HonmaniApplication
import app.honmani.reader.data.aozora.AozoraClient
import app.honmani.reader.data.aozora.AozoraException
import app.honmani.reader.data.aozora.AozoraIndex
import app.honmani.reader.data.aozora.AozoraRepository
import app.honmani.reader.data.aozora.AozoraSearch
import app.honmani.reader.data.aozora.AozoraSource
import app.honmani.reader.data.aozora.AozoraWork
import app.honmani.reader.ui.library.SharedText
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface AozoraDownload {
    /** [progress] from 0 to 1, or null while the size is unknown. */
    data class Running(val progress: Float?) : AozoraDownload

    data class Failed(val reason: AozoraException.Reason) : AozoraDownload
}

data class AozoraUiState(
    /** Looking for a list kept from before. */
    val starting: Boolean = true,
    /** Whether there is a list to browse. */
    val hasList: Boolean = false,
    /** The list is being downloaded. */
    val loading: Boolean = false,
    /** Why the last load of the list failed, if it did. */
    val error: AozoraException.Reason? = null,
    /** Date the list was generated, `YYYY-MM-DD`. */
    val generated: String = "",
    val total: Int = 0,
    val query: String = "",
    /** Also works in old kana (旧仮名). */
    val includeOld: Boolean = false,
    val results: List<AozoraWork> = emptyList(),
    /** The work whose details are open. */
    val selected: AozoraWork? = null,
    val download: AozoraDownload? = null,
)

/** A downloaded book, ready for the library: [fileName] is what the importer names it by. */
class AozoraBook(val fileName: String, val bytes: ByteArray)

/** The free books catalogue (Aozora Bunko): loading the list, searching it, downloading a work. */
class AozoraViewModel(
    private val repository: AozoraRepository,
    private val source: AozoraSource,
    /** Where searching runs. */
    private val worker: CoroutineDispatcher = Dispatchers.Default,
    private val searchDelayMs: Long = SEARCH_DELAY_MS,
) : ViewModel() {
    private val state = MutableStateFlow(AozoraUiState())
    val uiState: StateFlow<AozoraUiState> = state

    private val books = Channel<AozoraBook>(Channel.BUFFERED)

    /** Books downloaded, once each; the screen hands them to the library. */
    val downloaded: Flow<AozoraBook> = books.receiveAsFlow()

    private var index: AozoraIndex? = null
    private var searchJob: Job? = null
    private var downloadJob: Job? = null

    init {
        viewModelScope.launch {
            val kept = repository.cached()
            if (kept != null) show(kept)
            state.update { it.copy(starting = false) }
        }
    }

    /** Downloads the list (the user chose to), or checks that the kept one is current. */
    fun load() {
        if (state.value.loading) return
        state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                show(repository.refresh())
                state.update { it.copy(loading = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                val reason = (e as? AozoraException)?.reason ?: AozoraException.Reason.NETWORK
                state.update { it.copy(loading = false, error = reason) }
            }
        }
    }

    private fun show(list: AozoraIndex) {
        index = list
        state.update { it.copy(hasList = true, generated = list.generated, total = list.works.size) }
        filter(0)
    }

    fun setQuery(query: String) {
        state.update { it.copy(query = query) }
        filter(searchDelayMs)
    }

    fun setIncludeOld(include: Boolean) {
        state.update { it.copy(includeOld = include) }
        filter(0)
    }

    private fun filter(delayMs: Long) {
        val list = index ?: return
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            if (delayMs > 0) delay(delayMs)
            val (query, includeOld) = state.value.let { it.query to it.includeOld }
            val found = withContext(worker) { AozoraSearch.filter(list.works, query, includeOld) }
            state.update { it.copy(results = found) }
        }
    }

    fun select(work: AozoraWork) {
        if (state.value.download is AozoraDownload.Running) return
        state.update { it.copy(selected = work, download = null) }
    }

    /** Closes the work's details; a download that runs is stopped. */
    fun deselect() {
        downloadJob?.cancel()
        state.update { it.copy(selected = null, download = null) }
    }

    /** Downloads the selected work; it arrives in [downloaded]. */
    fun add() {
        val work = state.value.selected ?: return
        if (state.value.download is AozoraDownload.Running) return
        state.update { it.copy(download = AozoraDownload.Running(null)) }
        downloadJob = viewModelScope.launch {
            try {
                val bytes = source.book(work.file) { done, total ->
                    val progress = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else null
                    state.update { if (it.download is AozoraDownload.Running) it.copy(download = AozoraDownload.Running(progress)) else it }
                }
                state.update { it.copy(selected = null, download = null) }
                books.send(AozoraBook(fileName(work), bytes))
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                val reason = (e as? AozoraException)?.reason ?: AozoraException.Reason.NETWORK
                state.update { it.copy(download = AozoraDownload.Failed(reason)) }
            }
        }
    }

    /** Stops the download that runs. */
    fun cancelDownload() {
        downloadJob?.cancel()
        state.update { it.copy(download = null) }
    }

    companion object {
        const val SEARCH_DELAY_MS = 250L

        /** "<title>.txt": the importer names a book without an Aozora header after it. */
        fun fileName(work: AozoraWork): String =
            SharedText.title(work.title, "", "Aozora ${work.id}") + ".txt"

        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as HonmaniApplication
                val client = AozoraClient()
                AozoraViewModel(AozoraRepository(File(app.filesDir, "aozora"), client), client)
            }
        }
    }
}
