package app.honmani.reader.ui.reader

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.honmani.reader.HonmaniApplication
import app.honmani.reader.anki.AnkiResult
import app.honmani.reader.anki.CardContent
import app.honmani.reader.anki.VoiceProblem
import app.honmani.reader.data.words.LookedUpWord
import app.honmani.reader.data.words.WordRepository
import app.honmani.reader.diagnostics.ReaderDiagnostics
import app.honmani.reader.lookup.KanjiInfo
import app.honmani.reader.lookup.LookupGroup
import app.honmani.reader.lookup.LookupGroups
import app.honmani.reader.lookup.LookupResult
import app.honmani.reader.lookup.RubySpan
import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the lookup panel shows; null when it is closed. */
sealed interface LookupPanelState {
    val tap: TapLookup

    data class Results(
        override val tap: TapLookup,
        val groups: List<LookupGroup>,
        /** Characters of the tapped text covered by the best match, to mark in the book. */
        val highlight: Highlight,
        /** Words already in Anki, as [WordRepository.key]s. */
        val inAnki: Set<String> = emptySet(),
        /** Words whose card is being made right now, as [WordRepository.key]s. */
        val addingToAnki: Set<String> = emptySet(),
        /** The results this word was reached from by tapping a reference; null for a tap in the book. */
        val previous: Results? = null,
        /** What the kanji dictionaries say about the kanji in these words, by character. */
        val kanji: Map<String, KanjiInfo> = emptyMap(),
    ) : LookupPanelState

    /** One kanji, opened from a word's results ([previous]) or tapped in the book alone. */
    data class Kanji(
        override val tap: TapLookup,
        val kanji: KanjiInfo,
        val previous: Results? = null,
        /** What stays marked in the book. */
        val highlight: Highlight? = previous?.highlight,
    ) : LookupPanelState

    data class NothingFound(override val tap: TapLookup) : LookupPanelState

    data class NoDictionaries(override val tap: TapLookup) : LookupPanelState
}

/** The library book open in the reader. */
data class ReadingBook(val id: Long, val title: String)

class ReaderViewModel(
    private val lookup: suspend (text: String, ruby: List<RubySpan>) -> LookupResult,
    private val log: (String) -> Unit = {},
    val diagnostics: ReaderDiagnostics = ReaderDiagnostics(),
    /** The reader's link to the library; see [TtuLibrary]. */
    val ttuLibrary: TtuLibrary = TtuLibrary(),
    /** Where the library keeps book files, which the reader WebView serves to ttu. */
    val booksDir: File = File("books"),
    /** Saves a lookup to the word list. */
    private val recordLookup: suspend (LookedUpWord) -> Unit = {},
    /** The library book that ttu holds under an id. */
    private val findBook: suspend (ttuId: Long) -> ReadingBook? = { null },
    /** Adds a card to Anki; see [app.honmani.reader.anki.AnkiExporter]. */
    private val addCard: suspend (CardContent) -> AnkiResult = { AnkiResult.Refused },
    /** Which of these written forms are in Anki, as [WordRepository.key]s. */
    private val inAnki: suspend (Collection<String>) -> Set<String> = { emptySet() },
    /** The kanji dictionaries' entries for the kanji in a text; see [app.honmani.reader.lookup.DictionaryLookup.kanji]. */
    private val lookupKanji: suspend (String) -> Map<String, KanjiInfo> = { emptyMap() },
    /** Says a word (its reading); returns why it couldn't. See [app.honmani.reader.anki.WordAudio]. */
    private val say: suspend (String) -> VoiceProblem? = { null },
    /** Where lookups run and results become what the panel shows (parsing definitions), off the main thread. */
    private val compute: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private var book: ReadingBook? = null
    private var bookJob: Job? = null

    private val _panel = MutableStateFlow<LookupPanelState?>(null)
    val panel: StateFlow<LookupPanelState?> = _panel.asStateFlow()

    private val _highlight = MutableStateFlow<Highlight?>(null)

    /**
     * What is marked in the book: the panel's word, set as soon as the lookup has found it,
     * before the panel is built, so the book marks it while the panel is still being drawn.
     */
    val highlight: StateFlow<Highlight?> = _highlight.asStateFlow()

    private var lookupJob: Job? = null
    private var lookupCount = 0L

    /** The last tap and when its message came, until its panel is drawn ([onPanelDrawn]). */
    private var tapStarted: Pair<TapLookup, TimeMark>? = null

    private val _ankiResults = Channel<Pair<String, AnkiResult>>(Channel.BUFFERED)

    /** How adding a word to Anki went (written form and result), to tell the reader. */
    val ankiResults: Flow<Pair<String, AnkiResult>> = _ankiResults.receiveAsFlow()

    private val _voiceProblems = Channel<VoiceProblem>(Channel.BUFFERED)

    /** Why a word couldn't be said, to tell the reader. */
    val voiceProblems: Flow<VoiceProblem> = _voiceProblems.receiveAsFlow()

    /** Says [group]'s word: its reading, which a voice can't misread. */
    fun listen(group: LookupGroup) {
        viewModelScope.launch {
            say(group.reading.ifEmpty { group.expression })?.let { _voiceProblems.send(it) }
        }
    }

    fun onTap(tap: TapLookup) {
        lookupJob?.cancel()
        val lookupId = ++lookupCount
        lookupJob = viewModelScope.launch {
            val start = TimeSource.Monotonic.markNow()
            tapStarted = tap to start
            // Deinflection and matching take up to ~20 ms on older phones: not on the main thread,
            // where the reader draws.
            val result = withContext(compute) { lookup(tap.text, tap.ruby) }
            log("Lookup of ${tap.text.length} chars: ${result.entries.size} entries in ${start.elapsedNow()}")
            if (result.entries.isNotEmpty()) {
                _highlight.value = Highlight(lookupId, result.matchedLength)
                log("Mark sent ${start.elapsedNow()} after the tap's message")
            }
            // Keep the previous panel on screen until the new result is ready, to avoid flicker.
            show(when {
                !result.dictionariesAvailable -> LookupPanelState.NoDictionaries(tap)
                result.entries.isEmpty() -> {
                    // A kanji no word starts with (often a name) is still worth explaining.
                    val first = tap.text.takeIf { it.isNotEmpty() }
                        ?.let { String(Character.toChars(it.codePointAt(0))) }.orEmpty()
                    kanjiOf(first)[first]
                        ?.let { LookupPanelState.Kanji(tap, it, highlight = Highlight(lookupId, first.length)) }
                        ?: LookupPanelState.NothingFound(tap)
                }
                else -> {
                    val groups = withContext(compute) { LookupGroups.from(result) }
                    record(tap, groups.first())
                    LookupPanelState.Results(tap = tap, groups = groups, highlight = Highlight(lookupId, result.matchedLength))
                }
            })
            log("Panel shown ${start.elapsedNow()} after the tap's message")
            // Which words are in Anki (another app's content provider) and the kanji come a moment
            // later, while the panel slides in: waiting for them made every lookup slower.
            val groups = (_panel.value as? LookupPanelState.Results)?.takeIf { it.tap == tap }?.groups ?: return@launch
            val inAnki = wordsInAnki(groups)
            val kanji = kanjiOf(groups)
            updateResults(tap) { it.copy(inAnki = inAnki, kanji = kanji) }
        }
    }

    /**
     * Looks up [word], referenced in a definition, and shows it in the panel; back returns to
     * the results before. Not saved to the word list: it wasn't read in the book.
     */
    fun lookupWord(word: String) {
        val current = _panel.value as? LookupPanelState.Results ?: return
        lookupJob?.cancel()
        lookupJob = viewModelScope.launch {
            val result = withContext(compute) { lookup(word, emptyList()) }
            if (result.entries.isEmpty()) return@launch
            val groups = withContext(compute) { LookupGroups.from(result) }
            show(
                current.copy(
                    groups = groups,
                    inAnki = wordsInAnki(groups),
                    addingToAnki = emptySet(),
                    previous = current,
                    kanji = kanjiOf(groups),
                ),
            )
        }
    }

    /** Shows [character], a kanji of the words in the panel; back returns to them. */
    fun showKanji(character: String) {
        val current = _panel.value as? LookupPanelState.Results ?: return
        val kanji = current.kanji[character] ?: return
        lookupJob?.cancel()
        show(LookupPanelState.Kanji(current.tap, kanji, previous = current))
    }

    /** Back from a referenced word or a kanji; false if the panel shows a tap in the book. */
    fun back(): Boolean {
        val previous = when (val panel = _panel.value) {
            is LookupPanelState.Results -> panel.previous
            is LookupPanelState.Kanji -> panel.previous
            else -> null
        } ?: return false
        lookupJob?.cancel()
        show(previous)
        return true
    }

    private suspend fun kanjiOf(groups: List<LookupGroup>): Map<String, KanjiInfo> =
        kanjiOf(groups.joinToString("") { it.expression })

    /** Kanji entries for [text]; a failure only means no kanji to show. */
    private suspend fun kanjiOf(text: String): Map<String, KanjiInfo> = try {
        if (text.isEmpty()) emptyMap() else lookupKanji(text)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log("Kanji lookup failed: $e")
        emptyMap()
    }

    private suspend fun wordsInAnki(groups: List<LookupGroup>): Set<String> = try {
        inAnki(groups.map { it.expression })
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log("Checking Anki words failed: $e")
        emptySet()
    }

    /** Adds [group] from the panel to Anki, with the tapped sentence, and saves the word. */
    fun addToAnki(group: LookupGroup) {
        val state = _panel.value as? LookupPanelState.Results ?: return
        val tap = state.tap
        val key = WordRepository.key(group.expression, group.reading)
        if (key in state.addingToAnki) return
        updateResults(tap) { it.copy(addingToAnki = it.addingToAnki + key) }
        // A word reached from a reference isn't in the tapped sentence: no sentence on its card.
        val fromBook = state.previous == null
        viewModelScope.launch {
            if (fromBook) record(tap, group).join()
            val start = tap.sentenceOffset.coerceIn(0, tap.sentence.length)
            val content = CardContent(
                expression = group.expression,
                reading = group.reading,
                senses = group.sections.firstOrNull()?.senses?.map { it.glosses }.orEmpty(),
                sentence = if (fromBook) tap.sentence else "",
                surfaceStart = if (fromBook) start else 0,
                surfaceEnd = if (fromBook) (start + group.source.length).coerceAtMost(tap.sentence.length) else 0,
                bookTitle = book?.title,
                pitches = group.pitches,
                frequency = group.frequencies.firstOrNull()?.label,
            )
            val result = try {
                addCard(content)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("Adding to Anki failed: $e")
                AnkiResult.Failed(e.message ?: e.javaClass.simpleName)
            }
            val added = result is AnkiResult.Added || result == AnkiResult.AlreadyInAnki
            updateResults(tap) {
                it.copy(
                    inAnki = if (added) it.inAnki + key else it.inAnki,
                    addingToAnki = it.addingToAnki - key,
                )
            }
            _ankiResults.send(group.expression to result)
        }
    }

    /** Changes the panel's results if they are still those of [tap]. */
    private fun updateResults(tap: TapLookup, change: (LookupPanelState.Results) -> LookupPanelState.Results) {
        _panel.update { panel ->
            if (panel is LookupPanelState.Results && panel.tap == tap) change(panel) else panel
        }
    }

    /** ttu opened a book (or closed it: null); lookups are recorded with the library book. */
    fun onBookChanged(ttuId: Long?) {
        bookJob?.cancel()
        book = null
        if (ttuId != null) bookJob = viewModelScope.launch { book = findBook(ttuId) }
    }

    /** Saves a match (the best one on a tap) to the word list, independently of later taps. */
    private fun record(tap: TapLookup, best: LookupGroup): Job {
        val word = LookedUpWord(
            expression = best.expression,
            reading = best.reading,
            gloss = best.sections.firstOrNull()?.senses?.firstOrNull()?.glosses?.joinToString("; ").orEmpty(),
            surface = best.source,
            sentence = tap.sentence,
            surfaceStart = tap.sentenceOffset,
            bookId = book?.id,
            bookTitle = book?.title,
        )
        return viewModelScope.launch {
            try {
                recordLookup(word)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The word list is a convenience; a failed write must not disturb reading.
                log("Recording a lookup failed: $e")
            }
        }
    }

    fun dismiss() {
        lookupJob?.cancel()
        show(null)
    }

    /** The panel showing [state] has been drawn: logs how long after its tap's message, once per tap. */
    fun onPanelDrawn(state: LookupPanelState) {
        val start = tapStarted?.takeIf { it.first == state.tap }?.second ?: return
        tapStarted = null
        log("Panel drawn ${start.elapsedNow()} after the tap's message")
    }

    /** Shows [state] in the panel (null: closes it) and marks its word in the book. */
    private fun show(state: LookupPanelState?) {
        _panel.value = state
        _highlight.value = when (state) {
            is LookupPanelState.Results -> state.highlight
            is LookupPanelState.Kanji -> state.highlight
            else -> null
        }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val container = (this[APPLICATION_KEY] as HonmaniApplication).container
                val lookup = container.dictionaryLookup
                ReaderViewModel(
                    lookup = { text, ruby -> lookup.lookup(text, ruby) },
                    log = {
                        Log.d("Honmani.lookup", it)
                        // Timings for "Share diagnostics", beside the page's tap timing.
                        container.readerDiagnostics.log("lookup: $it")
                    },
                    diagnostics = container.readerDiagnostics,
                    ttuLibrary = container.ttuLibrary,
                    booksDir = container.booksDir,
                    recordLookup = container.wordRepository::record,
                    findBook = { ttuId ->
                        container.bookDao.findByTtuId(ttuId)?.let { ReadingBook(it.id, it.title) }
                    },
                    addCard = { container.ankiExporter.add(it) },
                    inAnki = { container.wordRepository.inAnki(it) },
                    lookupKanji = { lookup.kanji(it) },
                    say = { container.wordAudio.play(it) },
                )
            }
        }
    }
}
