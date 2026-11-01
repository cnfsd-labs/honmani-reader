package app.honmani.reader.data.difficulty

import app.honmani.reader.data.library.BookEntity
import app.honmani.reader.data.sync.RubyText
import app.honmani.reader.diagnostics.logWarning
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What grading is doing, for the book sheet. */
enum class GradingState {
    IDLE,
    GRADING,

    /** No word dictionary or no frequency list is installed: nothing can be graded. */
    NO_DICTIONARIES,
}

/**
 * Grades the library's books that have no current grade, one at a time, keeping each grade as
 * it is done (an interrupted run continues where it stopped). The lexicon is only built when a
 * book needs it.
 */
class BookGrader(
    private val store: DifficultyStore,
    private val books: suspend () -> List<BookEntity>,
    /** What grading would use now; null when a dictionary or frequency list is missing. */
    private val sources: suspend () -> LexiconSources?,
    private val lexicon: suspend (LexiconSources) -> Lexicon,
    /** The book's text in reading order; null if it can't be read. */
    private val texts: (BookEntity) -> Sequence<RubyText>?,
    private val log: (String) -> Unit = {},
) {
    private val _state = MutableStateFlow(GradingState.IDLE)
    val state: StateFlow<GradingState> = _state.asStateFlow()

    /** Grades until every book has a current grade; returns how many were graded. */
    suspend fun gradeAll(): Int {
        if (store.grades.value.isEmpty()) store.load()
        val sources = sources()
        if (sources == null) {
            _state.value = GradingState.NO_DICTIONARIES
            return 0
        }
        _state.value = GradingState.GRADING
        try {
            return gradeWith(sources)
        } finally {
            _state.value = GradingState.IDLE
        }
    }

    private suspend fun gradeWith(sources: LexiconSources): Int {
        val signature = sources.signature
        var segmenter: Segmenter? = null
        var graded = 0
        while (true) {
            val library = books()
            store.keepOnly(library.mapTo(HashSet()) { it.contentHash })
            val book = library.firstOrNull { store.grades.value[it.contentHash]?.signature != signature } ?: return graded
            val split = segmenter ?: Segmenter(lexicon(sources)).also { segmenter = it }
            val started = System.nanoTime()
            // Unreadable or too short: kept as score 0, so it isn't tried again on every run.
            // Likewise a book that throws: it would otherwise be tried first on every run and
            // stop the books behind it from ever being graded.
            val measured = try {
                texts(book)?.let { Difficulty.measure(it, split) }?.also { store.saveWords(book.contentHash, it.words) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logWarning("Honmani.difficulty", "Grading \"${book.title}\" failed; kept as score 0", e)
                null
            }
            val difficulty = measured?.difficulty
                ?: BookDifficulty(Difficulty.VERSION, score = 0, r95 = 0, kanjiShare = 0f, sentenceLength = 0f, words = 0, distinctWords = 0, unknownShare = 0f)
            store.save(book.contentHash, StoredDifficulty(difficulty, signature))
            graded++
            log("graded \"${book.title}\": ${difficulty.score} (R95 ${difficulty.r95}) in ${(System.nanoTime() - started) / 1_000_000} ms")
        }
    }
}
