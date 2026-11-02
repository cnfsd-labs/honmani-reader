package app.honmani.reader.data.words

import app.honmani.reader.data.review.ReviewCardEntity
import app.honmani.reader.data.review.ReviewRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * What one lookup in the reader changed, to take back ("Don't count"): the saved word (created, or
 * moved by the lookup), its word progress as it was, and the review card it lapsed. Each part is
 * null if the lookup left it alone.
 */
class LookupUndo(
    val word: WordUndo?,
    /** The word's progress before the lookup; null: none stored yet. Only meaningful with [word]. */
    val progress: WordStep?,
    /** The card as it was before the lookup made it due. */
    val card: ReviewCardEntity?,
) {
    /** The saved word's key ([WordRepository.key]), if the lookup changed the word. */
    val key: String? get() = word?.key
}

/**
 * Records a lookup in the reader everywhere it counts (the saved word with its lookup events, the
 * word progress that a lookup costs steps, a due review card that lapses) and takes it back.
 */
class LookupRecorder(
    private val words: WordRepository,
    private val reviews: ReviewRepository,
    private val progress: WordProgressStore,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    /** Records [word]; the result describes what changed, null if nothing did. */
    suspend fun record(word: LookedUpWord): LookupUndo? {
        // The step is read before the lookup: the tracker works out the cost of it afterwards.
        val step = words.find(word.expression, word.reading)?.let { saved -> withContext(io) { progress.load()[saved.id] } }
        val saved = words.record(word)
        // A lookup in a book is a lapse of a word in review (search and review lookups aren't).
        val card = reviews.lapse(word.expression, word.reading, word.origin)
        if (saved == null && card == null) return null
        return LookupUndo(saved, step, card)
    }

    /** Takes back what [undo] changed, and nothing else. */
    suspend fun undo(undo: LookupUndo) {
        undo.card?.let { reviews.restore(it) }
        val saved = undo.word ?: return
        words.undo(saved)
        withContext(io) { progress.revert(saved.wordId, undo.progress) }
    }
}
