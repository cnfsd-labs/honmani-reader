package app.honmani.reader.data.words

import androidx.room.withTransaction
import app.honmani.reader.data.HonmaniDatabase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/** A lookup in the reader, to be recorded as a saved word. */
data class LookedUpWord(
    val expression: String,
    val reading: String,
    val gloss: String,
    /** The tapped text the word covers, as written in the book. */
    val surface: String,
    val sentence: String,
    /** Where [surface] starts in [sentence]. */
    val surfaceStart: Int,
    val bookId: Long? = null,
    val bookTitle: String? = null,
)

data class WordCounts(val learning: Int = 0, val starred: Int = 0, val learned: Int = 0, val forAnki: Int = 0)

class DeletedWord(val word: WordEntity, val lookups: List<WordLookupEntity>)

/** Saved words: every lookup is recorded, words are ranked by [WordScore]. */
class WordRepository(
    private val database: HonmaniDatabase,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val dao = database.wordDao()

    val learning = dao.observeLearning()

    /** How many lookups were recorded since [from] (epoch milliseconds; null: ever). */
    fun lookupCount(from: Long?): Flow<Int> = if (from == null) dao.countLookups() else dao.countLookupsSince(from)

    /** How many lookups were recorded in a library book. */
    fun bookLookupCount(bookId: Long): Flow<Int> = dao.countLookups(bookId)
    val starred = dao.observeStarred()
    val learned = dao.observeLearned()

    /** Every saved word, being learned or learned. */
    val all: Flow<List<WordEntity>> = combine(learning, learned) { learning, learned -> learning + learned }

    /** The words of the latest reading session, see [ReadingSession]. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val session: Flow<SessionWords> = dao.observeLookupTimes(ReadingSession.MAX_LOOKUPS)
        .map { ReadingSession.start(it) }
        .distinctUntilChanged()
        .flatMapLatest { start ->
            if (start == null) flowOf(SessionWords(null, emptyList())) else dao.observeLookedUpSince(start).map { SessionWords(start, it) }
        }

    /** Words to suggest for Anki; see [WordDao.observeSuggested]. */
    val forAnki = dao.observeSuggested()

    /** Word counts for the list's tabs. */
    val counts: Flow<WordCounts> =
        combine(dao.countLearning(), dao.countStarred(), dao.countLearned(), dao.countSuggested(), ::WordCounts)

    /** Words added to AnkiDroid as cards. */
    val ankiCards: Flow<Int> = dao.countInAnki()

    suspend fun find(id: Long): WordEntity? = dao.find(id)

    suspend fun find(expression: String, reading: String): WordEntity? = dao.find(expression, reading)

    /** The latest sentences a word was looked up in, newest first. */
    suspend fun latestLookups(wordId: Long): List<WordLookupEntity> =
        dao.observeLookups(wordId, KEPT_LOOKUPS).first()

    suspend fun setAnkiNote(id: Long, noteId: Long?) = dao.setAnkiNote(id, noteId)

    /** "expression\u0000reading" keys of the words among [expressions] that are in Anki. */
    suspend fun inAnki(expressions: Collection<String>): Set<String> {
        if (expressions.isEmpty()) return emptySet()
        return dao.inAnki(expressions.distinct()).map { key(it.expression, it.reading) }.toSet()
    }

    fun lookups(wordId: Long) = dao.observeLookups(wordId, KEPT_LOOKUPS)

    /**
     * Records a lookup. Looking the same word up again in the same sentence soon after (a
     * second tap, a correction) does not count again. A learned word looked up is being learned
     * again.
     */
    suspend fun record(word: LookedUpWord) {
        val time = now()
        val sentence = word.sentence.take(MAX_SENTENCE_LENGTH)
        val start = word.surfaceStart.coerceIn(0, sentence.length)
        val end = (start + word.surface.length).coerceAtMost(sentence.length)
        val gloss = word.gloss.take(MAX_GLOSS_LENGTH)
        database.withTransaction {
            val existing = dao.find(word.expression, word.reading)
            val wordId = if (existing == null) {
                dao.insert(
                    WordEntity(
                        expression = word.expression,
                        reading = word.reading,
                        gloss = gloss,
                        lookupCount = 1,
                        firstLookedUpAt = time,
                        lastLookedUpAt = time,
                        rankKey = WordScore.add(null, time),
                        lastSentence = sentence,
                        lastSurfaceStart = start,
                        lastSurfaceEnd = end,
                        lastBookTitle = word.bookTitle,
                    ),
                )
            } else {
                // The same word tapped again in the same sentence within REPEAT_WINDOW_MS counts as
                // one lookup: nothing is saved, including the lookup event, so the statistics count
                // at most one lookup event per hour per word and sentence.
                val latest = dao.latestLookup(existing.id)
                if (latest != null && latest.sentence == sentence && time - latest.lookedUpAt < REPEAT_WINDOW_MS) {
                    return@withTransaction
                }
                dao.update(
                    existing.copy(
                        // Looked up again: being forgotten, so learned no longer (see WordProgress).
                        status = WordStatus.LEARNING,
                        gloss = gloss.ifEmpty { existing.gloss },
                        lookupCount = existing.lookupCount + 1,
                        lastLookedUpAt = time,
                        rankKey = WordScore.add(existing.rankKey, time),
                        lastSentence = sentence,
                        lastSurfaceStart = start,
                        lastSurfaceEnd = end,
                        lastBookTitle = word.bookTitle,
                    ),
                )
                existing.id
            }
            dao.insertLookup(
                WordLookupEntity(
                    wordId = wordId,
                    surface = word.surface,
                    sentence = sentence,
                    surfaceStart = start,
                    surfaceEnd = end,
                    bookId = word.bookId,
                    bookTitle = word.bookTitle,
                    lookedUpAt = time,
                ),
            )
            dao.pruneLookups(wordId, KEPT_LOOKUPS)
            dao.insertLookupEvent(LookupEventEntity(lookedUpAt = time, bookId = word.bookId))
        }
    }

    suspend fun setStarred(id: Long, starred: Boolean) = dao.setStarred(id, starred)

    suspend fun markLearned(id: Long) = dao.markLearned(id)

    suspend fun markLearning(id: Long) = dao.markLearning(id)

    /** Deletes a word with its lookups and returns them, for [restore]; null if it is gone. */
    suspend fun delete(id: Long): DeletedWord? = database.withTransaction {
        val word = dao.find(id) ?: return@withTransaction null
        val lookups = dao.allLookups(id)
        dao.delete(id)
        DeletedWord(word, lookups)
    }

    /** Puts back a word removed by [delete] (undo), unless it has been looked up again since. */
    suspend fun restore(deleted: DeletedWord) {
        database.withTransaction {
            if (dao.find(deleted.word.expression, deleted.word.reading) != null) return@withTransaction
            dao.insert(deleted.word)
            dao.insertLookups(deleted.lookups)
        }
    }

    companion object {
        /** Identifies a word by written form and reading, as in [inAnki]. */
        fun key(expression: String, reading: String) = "$expression\u0000$reading"

        /** Sentences kept per word; enough for example sentences on cards later. */
        const val KEPT_LOOKUPS = 10
        const val REPEAT_WINDOW_MS = 60L * 60 * 1000
        private const val MAX_SENTENCE_LENGTH = 400
        private const val MAX_GLOSS_LENGTH = 200
    }
}
