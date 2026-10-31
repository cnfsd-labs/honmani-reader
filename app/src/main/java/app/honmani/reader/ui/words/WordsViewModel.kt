package app.honmani.reader.ui.words

import app.honmani.reader.diagnostics.logWarning
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.honmani.reader.HonmaniApplication
import app.honmani.reader.anki.AnkiCheck
import app.honmani.reader.anki.AnkiResult
import app.honmani.reader.anki.VoiceProblem
import app.honmani.reader.data.words.DeletedWord
import app.honmani.reader.data.words.SessionWords
import app.honmani.reader.data.words.WordEntity
import app.honmani.reader.data.words.WordKnowledge
import app.honmani.reader.data.words.WordLookupEntity
import app.honmani.reader.data.words.WordRepository
import app.honmani.reader.lookup.Frequency
import app.honmani.reader.lookup.LookupGroup
import app.honmani.reader.lookup.LookupGroups
import app.honmani.reader.lookup.metaKey
import app.honmani.reader.data.export.CsvExports
import java.io.IOException
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class WordFilter {
    /** Saved words not known yet, starred first, then the most looked up (recently). */
    LEARNING,

    /** Words known: read often enough without a lookup, or marked as learned. */
    KNOWN,
}

data class WordsUiState(
    /** The list shown, the one exports take. */
    val filter: WordFilter = WordFilter.LEARNING,
    /** The saved words of each list; null until loaded. */
    val learning: List<WordEntity>? = null,
    val known: List<WordEntity>? = null,
    /** In the known list, after the saved words: words known from reading alone, with how often each was read. */
    val knownByReading: List<Pair<String, Int>> = emptyList(),
    /** Progress of saved words in steps of `WordProgress.KNOWN`, by id. */
    val steps: Map<Long, Int> = emptyMap(),
    val knownCount: Int = 0,
    /** Words in the learning list without an Anki card, for "Add all to Anki" (grammar words it left out not counted). */
    val notInAnki: Int = 0,
    /** The word whose details are shown, if any. */
    val expanded: Long? = null,
    /** The expanded word's latest lookups, newest first. */
    val lookups: List<WordLookupEntity> = emptyList(),
    /** The word known from reading alone whose meaning is shown, if any. */
    val readWord: ReadWordDetails? = null,
    /** Words added so far and how many in all, while "Add all" or a package for Anki runs. */
    val ankiProgress: Pair<Int, Int>? = null,
    /** The progress is that of an Anki package being saved, not of "Add all". */
    val savingPackage: Boolean = false,
    /** "Check against Anki" is running. */
    val checkingAnki: Boolean = false,
    /** Words whose card is being made right now. */
    val addingToAnki: Set<Long> = emptySet(),
    /** How common each word of the shown list is (first frequency dictionary), by id; empty if switched off. */
    val frequencies: Map<Long, Frequency> = emptyMap(),
    /** The words of the latest reading session, for the sheet "Words from this session". */
    val session: SessionWords = SessionWords(null, emptyList()),
)

/** How adding to Anki went: one word, or several from "Add all". */
sealed interface AnkiOutcome {
    data class One(val expression: String, val result: AnkiResult) : AnkiOutcome

    /**
     * "Add all" finished: [added] new cards; [stopped] is the result that ended it early,
     * [audioProblem] why some cards went in without audio, if so, and [leftOut] how many
     * grammar words (の, です) were passed over.
     */
    data class Many(
        val added: Int,
        val stopped: AnkiResult? = null,
        val audioProblem: VoiceProblem? = null,
        val leftOut: Int = 0,
    ) : AnkiOutcome

    /**
     * [count] words were written to a file for Anki's import: a package with audio if
     * [packaged] ([audioProblem]: why some cards have none), else a text file.
     */
    data class Exported(val count: Int, val packaged: Boolean = false, val audioProblem: VoiceProblem? = null) :
        AnkiOutcome

    /** [count] words were written to a CSV file. */
    data class CsvExported(val count: Int) : AnkiOutcome

    data object ExportFailed : AnkiOutcome

    /** "Check against Anki" finished. */
    data class Checked(val result: AnkiCheck) : AnkiOutcome
}

/** A change the user can undo from a snackbar. */
sealed interface WordChange {
    val expression: String

    data class Learned(val id: Long, override val expression: String) : WordChange

    data class Deleted(val deleted: DeletedWord) : WordChange {
        override val expression: String get() = deleted.word.expression
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class WordsViewModel(
    private val repository: WordRepository,
    /** Adds a word to Anki with the sentence of a lookup (null: the latest). */
    private val addCard: suspend (WordEntity, WordLookupEntity?) -> AnkiResult = { _, _ -> AnkiResult.Refused },
    /** The words as a text file for Anki's import. */
    private val ankiText: suspend (List<WordEntity>) -> String = { "" },
    /** Writes text to a document the reader picked (a content URI). */
    private val writeDocument: suspend (uri: String, text: String) -> Unit = { _, _ -> },
    /**
     * Writes the words as an Anki package with audio to a document the reader picked, telling
     * how many are done; returns why cards have no audio, if so.
     */
    private val writePackage: suspend (uri: String, List<WordEntity>, onProgress: (Int) -> Unit) -> VoiceProblem? =
        { _, _, _ -> null },
    /** Compares the "in Anki" marks with AnkiDroid's notes. */
    private val checkAnki: suspend () -> AnkiCheck = { AnkiCheck.NotInstalled },
    /** What was read and how well each word is known; null while it is worked out. */
    private val knowledge: Flow<WordKnowledge?> = flowOf(null),
    /** Looks a word up in the dictionaries, as a tap in a book would. */
    private val lookUp: suspend (String) -> List<LookupGroup> = { emptyList() },
    /** Whether the list shows how common each word is (a setting). */
    private val showFrequency: Flow<Boolean> = flowOf(false),
    /** How common each word is, by [app.honmani.reader.lookup.metaKey] of its form and reading; one batch for all. */
    private val frequencyOf: suspend (List<Pair<String, String>>) -> Map<String, Frequency> = { emptyMap() },
    /** The words of the latest reading session. */
    private val session: Flow<SessionWords> = flowOf(SessionWords(null, emptyList())),
    /** The time zone the CSV export writes times in. */
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) : ViewModel() {
    private val filter = MutableStateFlow(WordFilter.LEARNING)
    private val expanded = MutableStateFlow<Long?>(null)
    private val _changes = Channel<WordChange>(Channel.BUFFERED)
    private val _ankiOutcomes = Channel<AnkiOutcome>(Channel.BUFFERED)
    private val ankiProgress = MutableStateFlow<Pair<Int, Int>?>(null)
    private val savingPackage = MutableStateFlow(false)
    private val addingToAnki = MutableStateFlow<Set<Long>>(emptySet())
    private val checkingAnki = MutableStateFlow(false)

    /** Grammar words "Add all" passed over, so it neither counts nor checks them again. */
    private val grammarWords = MutableStateFlow<Set<Long>>(emptySet())
    private val readWord = MutableStateFlow<ReadWordDetails?>(null)
    private var readWordJob: Job? = null

    /** Results of adding to Anki, to tell the reader. */
    val ankiOutcomes: Flow<AnkiOutcome> = _ankiOutcomes.receiveAsFlow()

    /** Changes to offer an undo for. */
    val changes: Flow<WordChange> = _changes.receiveAsFlow()

    private val lists: Flow<WordLists> = combine(repository.all, knowledge.onStart { emit(null) }) { words, knowledge ->
        WordLists.of(words, knowledge)
    }

    private val words: Flow<List<WordEntity>> = combine(filter, lists) { filter, lists ->
        if (filter == WordFilter.LEARNING) lists.learning else lists.known
    }

    private val lookups: Flow<List<WordLookupEntity>> = expanded.flatMapLatest { id ->
        if (id == null) flowOf(emptyList()) else repository.lookups(id)
    }

    /** Looked up again only when the words shown change, not on every lookup count. */
    private val frequencies: Flow<Map<Long, Frequency>> = showFrequency.flatMapLatest { on ->
        if (!on) {
            flowOf(emptyMap())
        } else {
            words.map { list -> list.map { Triple(it.id, it.expression, it.reading) }.toSet() }
                .distinctUntilChanged()
                .mapLatest { shown ->
                    val found = try {
                        frequencyOf(shown.map { (_, expression, reading) -> expression to reading })
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        logWarning("Honmani.words", "Frequency lookup failed", e)
                        emptyMap()
                    }
                    shown.mapNotNull { (id, expression, reading) ->
                        found[metaKey(expression, reading)]?.let { id to it }
                    }.toMap()
                }
        }
    }

    val uiState: StateFlow<WordsUiState> =
        combine(filter, lists, expanded, lookups) { filter, lists, expanded, lookups ->
            WordsUiState(
                filter = filter,
                learning = lists.learning,
                known = lists.known,
                knownByReading = lists.knownByReading,
                steps = lists.steps,
                knownCount = lists.knownCount,
                notInAnki = lists.learning.count { it.ankiNoteId == null },
                expanded = expanded,
                lookups = if (expanded == null) emptyList() else lookups,
            )
        }.combine(ankiProgress) { state, progress -> state.copy(ankiProgress = progress) }
            .combine(addingToAnki) { state, adding -> state.copy(addingToAnki = adding) }
            .combine(savingPackage) { state, saving -> state.copy(savingPackage = saving) }
            .combine(checkingAnki) { state, checking -> state.copy(checkingAnki = checking) }
            .combine(readWord) { state, word -> state.copy(readWord = word) }
            .combine(frequencies.onStart { emit(emptyMap()) }) { state, found -> state.copy(frequencies = found) }
            .combine(session) { state, session -> state.copy(session = session) }
            .combine(grammarWords) { state, grammar ->
                state.copy(notInAnki = state.learning.orEmpty().count { it.ankiNoteId == null && it.id !in grammar })
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WordsUiState())

    fun setFilter(value: WordFilter) {
        if (filter.value == value) return
        filter.value = value
        expanded.value = null
        closeReadWord()
    }

    /** Shows or hides the details of a word. */
    fun toggleExpanded(id: Long) {
        expanded.value = if (expanded.value == id) null else id
        closeReadWord()
    }

    /**
     * Shows or hides the meaning of [expression], a word known from reading alone: it was never
     * looked up, so the dictionaries are asked now.
     */
    fun toggleReadWord(expression: String) {
        val open = readWord.value?.expression == expression
        closeReadWord()
        if (open) return
        expanded.value = null
        readWord.value = ReadWordDetails(expression)
        readWordJob = viewModelScope.launch {
            val entry = try {
                ReadWordDetails.entryFor(expression, withContext(Dispatchers.Default) { lookUp(expression) })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // No meaning to show is all a failed lookup means here.
                null
            }
            readWord.value = ReadWordDetails(expression, loading = false, entry = entry)
        }
    }

    private fun closeReadWord() {
        readWordJob?.cancel()
        readWord.value = null
    }

    fun setStarred(id: Long, starred: Boolean) {
        viewModelScope.launch { repository.setStarred(id, starred) }
    }

    fun markLearned(word: WordEntity) {
        viewModelScope.launch {
            repository.markLearned(word.id)
            _changes.send(WordChange.Learned(word.id, word.expression))
        }
    }

    fun markLearning(id: Long) {
        viewModelScope.launch { repository.markLearning(id) }
    }

    fun delete(id: Long) {
        viewModelScope.launch {
            repository.delete(id)?.let { _changes.send(WordChange.Deleted(it)) }
        }
    }

    /** Adds [word] to Anki, with the sentence of [lookup] if given. */
    fun addToAnki(word: WordEntity, lookup: WordLookupEntity? = null) {
        if (word.id in addingToAnki.value) return
        addingToAnki.update { it + word.id }
        viewModelScope.launch {
            try {
                _ankiOutcomes.send(AnkiOutcome.One(word.expression, addCard(word, lookup)))
            } finally {
                addingToAnki.update { it - word.id }
            }
        }
    }

    /**
     * Adds every word being learned that has no card to Anki, one after another, except grammar
     * words (の, です: [LookupGroups.isGrammarWord]); stops if AnkiDroid can't take them. A
     * single word's button still adds a grammar word.
     */
    fun addAllToAnki() {
        if (ankiProgress.value != null) return
        // Set now, not after the list is read, so a second tap can't start a second run.
        ankiProgress.value = 0 to 1
        viewModelScope.launch {
            var added = 0
            var leftOut = 0
            var stopped: AnkiResult? = null
            var audioProblem: VoiceProblem? = null
            try {
                val suggested = lists.first().learning.filter { it.ankiNoteId == null && it.id !in grammarWords.value }
                for ((i, word) in suggested.withIndex()) {
                    ankiProgress.value = i to suggested.size
                    if (isGrammarWord(word)) {
                        grammarWords.update { it + word.id }
                        leftOut++
                        continue
                    }
                    when (val result = addCard(word, null)) {
                        is AnkiResult.Added -> {
                            added++
                            audioProblem = audioProblem ?: result.audioProblem
                        }
                        AnkiResult.AlreadyInAnki -> Unit
                        else -> {
                            stopped = result
                            break
                        }
                    }
                }
            } finally {
                ankiProgress.value = null
            }
            _ankiOutcomes.send(AnkiOutcome.Many(added, stopped, audioProblem, leftOut))
        }
    }

    private suspend fun isGrammarWord(word: WordEntity): Boolean = try {
        withContext(Dispatchers.Default) { lookUp(word.expression) }
            .firstOrNull { it.expression == word.expression && it.reading == word.reading }
            ?.let(LookupGroups::isGrammarWord) == true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logWarning("Honmani.words", "Grammar word check failed", e)
        false
    }

    /** Compares the "in Anki" marks with AnkiDroid: forgets cards deleted there, marks cards found. */
    fun checkAgainstAnki() {
        if (checkingAnki.value) return
        checkingAnki.value = true
        viewModelScope.launch {
            try {
                _ankiOutcomes.send(AnkiOutcome.Checked(checkAnki()))
            } finally {
                checkingAnki.value = false
            }
        }
    }

    /** Writes the words of the current list to [uri] as a file for Anki's import. */
    fun exportToFile(uri: String) {
        viewModelScope.launch {
            val outcome = try {
                val list = words.first()
                writeDocument(uri, ankiText(list))
                AnkiOutcome.Exported(list.size)
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                logWarning("Honmani.words", "Anki text export failed", e)
                AnkiOutcome.ExportFailed
            }
            _ankiOutcomes.send(outcome)
        }
    }

    /** Writes every saved word, with its lookup history, to [uri] as a CSV file. */
    fun exportCsv(uri: String) {
        viewModelScope.launch {
            val outcome = try {
                val list = repository.all.first()
                writeDocument(uri, CsvExports.words(list, zone()))
                AnkiOutcome.CsvExported(list.size)
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                logWarning("Honmani.words", "CSV export failed", e)
                AnkiOutcome.ExportFailed
            }
            _ankiOutcomes.send(outcome)
        }
    }

    /** Writes the words of the current list to [uri] as an Anki package with audio. */
    fun exportPackage(uri: String) {
        if (ankiProgress.value != null) return
        savingPackage.value = true
        ankiProgress.value = 0 to 1
        viewModelScope.launch {
            val outcome = try {
                val list = words.first()
                ankiProgress.value = 0 to list.size
                val problem = writePackage(uri, list) { done ->
                    ankiProgress.value = done.coerceAtMost(list.size - 1) to list.size
                }
                AnkiOutcome.Exported(list.size, packaged = true, audioProblem = problem)
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                logWarning("Honmani.words", "Anki package export failed", e)
                AnkiOutcome.ExportFailed
            } finally {
                ankiProgress.value = null
                savingPackage.value = false
            }
            _ankiOutcomes.send(outcome)
        }
    }

    fun undo(change: WordChange) {
        viewModelScope.launch {
            when (change) {
                is WordChange.Learned -> repository.markLearning(change.id)
                is WordChange.Deleted -> repository.restore(change.deleted)
            }
        }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val container = (this[APPLICATION_KEY] as HonmaniApplication).container
                val application = this[APPLICATION_KEY] as HonmaniApplication
                WordsViewModel(
                    repository = container.wordRepository,
                    addCard = { word, lookup -> container.ankiExporter.add(word, lookup) },
                    ankiText = { list -> container.ankiExporter.textFile(list) },
                    checkAnki = { container.ankiSync.check() },
                    writeDocument = { uri, text ->
                        withContext(Dispatchers.IO) {
                            val out = application.contentResolver.openOutputStream(Uri.parse(uri), "wt")
                                ?: throw IOException("Cannot write $uri")
                            out.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                        }
                    },
                    writePackage = { uri, list, onProgress ->
                        val out = withContext(Dispatchers.IO) {
                            application.contentResolver.openOutputStream(Uri.parse(uri), "wt")
                        } ?: throw IOException("Cannot write $uri")
                        out.use { container.ankiExporter.ankiPackage(list, it, onProgress) }
                    },
                    knowledge = container.wordKnowledge,
                    lookUp = { word -> LookupGroups.from(container.dictionaryLookup.lookup(word, emptyList())) },
                    showFrequency = container.appearanceSettings.showWordFrequency,
                    frequencyOf = { words -> container.dictionaryLookup.frequencies(words) },
                    session = container.wordRepository.session,
                )
            }
        }
    }
}
