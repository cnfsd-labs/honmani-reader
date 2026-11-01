package app.honmani.reader.ui.dictionaries

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.honmani.reader.HonmaniApplication
import app.honmani.reader.data.dictionary.CatalogDictionary
import app.honmani.reader.data.dictionary.DictionaryCatalog
import app.honmani.reader.data.dictionary.DictionaryEntity
import app.honmani.reader.data.dictionary.DictionaryImportScheduler
import app.honmani.reader.data.dictionary.DictionaryRepository
import app.honmani.reader.data.dictionary.DictionaryUpdate
import app.honmani.reader.data.dictionary.DictionaryUpdateChecker
import app.honmani.reader.data.dictionary.ImportStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface DictionariesUiState {
    data object Loading : DictionariesUiState

    data class Loaded(
        val dictionaries: List<DictionaryEntity>,
        /** Queued, running and failed imports, oldest first. */
        val imports: List<ImportStatus> = emptyList(),
        /** Dictionaries being deleted; large ones take a few seconds. */
        val deleting: Set<Long> = emptySet(),
        /** Dictionaries offered for download, with whether each is installed or on its way. */
        val catalog: List<CatalogItem> = emptyList(),
        /** Newer versions of installed dictionaries, by the installed dictionary's id. */
        val updates: Map<Long, DictionaryUpdate> = emptyMap(),
        /** Installed dictionaries whose update was requested and is on its way. */
        val updating: Set<Long> = emptySet(),
        val updateCheck: UpdateCheck = UpdateCheck.NOT_CHECKED,
    ) : DictionariesUiState
}

enum class UpdateCheck { NOT_CHECKED, CHECKING, CHECKED }

data class CatalogItem(val dictionary: CatalogDictionary, val state: State) {
    enum class State { AVAILABLE, IN_PROGRESS, INSTALLED }
}

class DictionariesViewModel(
    private val repository: DictionaryRepository,
    private val importScheduler: DictionaryImportScheduler,
    private val updateChecker: DictionaryUpdateChecker,
) : ViewModel() {
    private val deleting = MutableStateFlow<Set<Long>>(emptySet())

    /** Catalog downloads requested in this session; they show as in progress until installed. */
    private val requested = MutableStateFlow<Set<String>>(emptySet())

    /** Updates requested in this session, by installed dictionary id. */
    private val requestedUpdates = MutableStateFlow<Set<Long>>(emptySet())

    private val updateCheck = combine(updateChecker.checking, updateChecker.checked) { checking, checked ->
        when {
            checking -> UpdateCheck.CHECKING
            checked -> UpdateCheck.CHECKED
            else -> UpdateCheck.NOT_CHECKED
        }
    }

    private val updateState = combine(updateChecker.updates, requestedUpdates, updateCheck) { updates, requested, check ->
        Triple(updates.associateBy { it.dictionaryId }, requested, check)
    }

    val uiState: StateFlow<DictionariesUiState> =
        combine(
            repository.dictionaries,
            importScheduler.imports,
            deleting,
            requested,
            updateState,
        ) { dictionaries, imports, deleting, requested, (updates, updateRequests, check) ->
            val titles = dictionaries.map { it.title }
            val catalog = DictionaryCatalog.dictionaries.map { dictionary ->
                val state = when {
                    DictionaryCatalog.isInstalled(dictionary, titles) -> CatalogItem.State.INSTALLED
                    // Only this dictionary's own failure puts it back on offer; the others go on.
                    dictionary.id in requested &&
                        imports.none { it is ImportStatus.Failed && it.dictionaryId == dictionary.id } ->
                        CatalogItem.State.IN_PROGRESS
                    else -> CatalogItem.State.AVAILABLE
                }
                CatalogItem(dictionary, state)
            }
            val failed = imports.any { it is ImportStatus.Failed }
            DictionariesUiState.Loaded(
                dictionaries = dictionaries,
                imports = imports,
                deleting = deleting,
                catalog = catalog,
                updates = updates,
                // Until the new version replaces the installed one (or the download fails).
                updating = if (failed) emptySet() else updateRequests.filterTo(HashSet()) { it in updates },
                updateCheck = check,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = DictionariesUiState.Loading,
        )

    /** Imports the Yomitan dictionary zip at [uri] (a content URI from the document picker). */
    fun import(uri: String) = importScheduler.enqueue(uri)

    /** Downloads and imports [dictionary]; repeated taps while it is on its way are ignored. */
    fun download(dictionary: CatalogDictionary) {
        val item = (uiState.value as? DictionariesUiState.Loaded)?.catalog?.find { it.dictionary == dictionary }
        if (item != null && item.state != CatalogItem.State.AVAILABLE) return
        requested.update { it + dictionary.id }
        importScheduler.download(dictionary)
    }

    /** Downloads [update] and puts it in place of the installed version; repeated taps are ignored. */
    fun update(update: DictionaryUpdate) {
        if (update.dictionaryId in requestedUpdates.value) return
        requestedUpdates.update { it + update.dictionaryId }
        importScheduler.update(update)
    }

    fun checkForUpdates() = updateChecker.start(all = true)

    /**
     * Retries the failed downloads among [pack] only: their failures are forgotten and they are
     * requested again, while downloads still running stay tracked.
     */
    fun retryFailed(pack: List<CatalogDictionary>) {
        val failed = (uiState.value as? DictionariesUiState.Loaded)?.imports
            .orEmpty().filterIsInstance<ImportStatus.Failed>().mapNotNull { it.dictionaryId }.toSet()
        requested.update { it - failed }
        importScheduler.clearFailures()
        pack.filter { it.id in failed }.forEach(::download)
    }

    fun dismissFailures() {
        // A failed download may be retried.
        requested.value = emptySet()
        requestedUpdates.value = emptySet()
        importScheduler.clearFailures()
    }

    fun setEnabled(id: Long, enabled: Boolean) {
        viewModelScope.launch { repository.setEnabled(id, enabled) }
    }

    /** Moves dictionary [id] one place up or down; the first one's definitions show first. */
    fun move(id: Long, up: Boolean) {
        viewModelScope.launch { repository.move(id, if (up) -1 else 1) }
    }

    fun delete(id: Long) {
        if (id in deleting.value) return
        deleting.update { it + id }
        // A download of it earlier in this session is done; once deleted, it can be downloaded again.
        val title = (uiState.value as? DictionariesUiState.Loaded)?.dictionaries?.find { it.id == id }?.title
        if (title != null) {
            requested.update { ids ->
                ids.filterTo(HashSet()) { catalogId ->
                    DictionaryCatalog.dictionaries.none { it.id == catalogId && DictionaryCatalog.isInstalled(it, listOf(title)) }
                }
            }
        }
        viewModelScope.launch {
            try {
                repository.delete(id)
            } finally {
                deleting.update { it - id }
            }
        }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val container = (this[APPLICATION_KEY] as HonmaniApplication).container
                DictionariesViewModel(
                    container.dictionaryRepository,
                    container.dictionaryImportScheduler,
                    container.dictionaryUpdateChecker,
                )
            }
        }
    }
}
