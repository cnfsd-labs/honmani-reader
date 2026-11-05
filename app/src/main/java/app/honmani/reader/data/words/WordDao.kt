package app.honmani.reader.data.words

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface WordDao {
    /** Words being learned, highest score first. */
    @Query("SELECT * FROM word WHERE status = 'LEARNING' AND ignored = 0 ORDER BY rank_key DESC, id DESC")
    fun observeLearning(): Flow<List<WordEntity>>

    @Query("SELECT * FROM word WHERE starred = 1 AND ignored = 0 ORDER BY rank_key DESC, id DESC")
    fun observeStarred(): Flow<List<WordEntity>>

    @Query("SELECT COUNT(*) FROM word WHERE status = 'LEARNED' AND ignored = 0")
    fun observeLearnedCount(): Flow<Int>

    @Query("SELECT * FROM word WHERE status = 'LEARNED' AND ignored = 0 ORDER BY expression, reading")
    fun observeLearned(): Flow<List<WordEntity>>

    /** Every word that is not ignored, learning ones first: one query, so a word moving between the two lists is never in both. */
    @Query("SELECT * FROM word WHERE ignored = 0 ORDER BY status = 'LEARNED', CASE WHEN status = 'LEARNED' THEN expression END, CASE WHEN status = 'LEARNED' THEN reading END, rank_key DESC, id DESC")
    fun observeAll(): Flow<List<WordEntity>>

    /** Every word including the ignored ones, to work out [WordKnowledge]. */
    @Query("SELECT * FROM word")
    fun observeEvery(): Flow<List<WordEntity>>

    /** The ignored words, by written form. */
    @Query("SELECT * FROM word WHERE ignored = 1 ORDER BY expression, reading")
    fun observeIgnored(): Flow<List<WordEntity>>

    @Query("SELECT COUNT(*) FROM word WHERE ignored = 1")
    fun countIgnored(): Flow<Int>

    @Query("UPDATE word SET ignored = :ignored WHERE id = :id")
    suspend fun setIgnored(id: Long, ignored: Boolean)

    /**
     * Words suggested for Anki: being learned, not in Anki yet, and looked up more than once
     * (a repeat in the same sentence within the hour doesn't count, see [WordRepository.record]).
     */
    @Query("SELECT * FROM word WHERE $SUGGESTED ORDER BY rank_key DESC, id DESC")
    fun observeSuggested(): Flow<List<WordEntity>>

    @Query("SELECT COUNT(*) FROM word WHERE $SUGGESTED")
    fun countSuggested(): Flow<Int>

    @Query("SELECT COUNT(*) FROM word WHERE status = 'LEARNING' AND ignored = 0")
    fun countLearning(): Flow<Int>

    @Query("SELECT COUNT(*) FROM word WHERE starred = 1 AND ignored = 0")
    fun countStarred(): Flow<Int>

    @Query("SELECT COUNT(*) FROM word WHERE status = 'LEARNED' AND ignored = 0")
    fun countLearned(): Flow<Int>

    @Query("SELECT COUNT(*) FROM word WHERE anki_note_id IS NOT NULL")
    fun countInAnki(): Flow<Int>

    /** All lookups ever counted, for statistics; see [LookupEventEntity]. */
    @Query("SELECT COUNT(*) FROM lookup_event")
    fun countLookups(): Flow<Int>

    /** When the latest lookups happened, newest first (to find where the latest session began). */
    @Query("SELECT looked_up_at FROM lookup_event ORDER BY looked_up_at DESC LIMIT :limit")
    fun observeLookupTimes(limit: Int): Flow<List<Long>>

    /** Words looked up at [from] or later, the latest first. */
    @Query("SELECT * FROM word WHERE last_looked_up_at >= :from AND ignored = 0 ORDER BY last_looked_up_at DESC, id DESC")
    fun observeLookedUpSince(from: Long): Flow<List<WordEntity>>

    /** Lookups since [from] (epoch milliseconds). */
    @Query("SELECT COUNT(*) FROM lookup_event WHERE looked_up_at >= :from")
    fun countLookupsSince(from: Long): Flow<Int>

    @Query("SELECT COUNT(*) FROM lookup_event WHERE book_id = :bookId")
    fun countLookups(bookId: Long): Flow<Int>

    @Insert
    suspend fun insertLookupEvent(event: LookupEventEntity): Long

    @Query("DELETE FROM lookup_event WHERE id = :id")
    suspend fun deleteLookupEvent(id: Long)

    @Query("DELETE FROM word_lookup WHERE id = :id")
    suspend fun deleteLookup(id: Long)

    @Query("SELECT * FROM word WHERE id = :id")
    suspend fun find(id: Long): WordEntity?

    @Query("SELECT * FROM word WHERE expression = :expression AND reading = :reading")
    suspend fun find(expression: String, reading: String): WordEntity?

    @Insert
    suspend fun insert(word: WordEntity): Long

    @Update
    suspend fun update(word: WordEntity)

    @Query("UPDATE word SET gloss = :gloss WHERE id = :id")
    suspend fun setGloss(id: Long, gloss: String)

    @Query("UPDATE word SET note = :note WHERE id = :id")
    suspend fun setNote(id: Long, note: String)

    /** Words whose furigana the reader corrected: the suggestions for Read aloud pronunciations. */
    @Query("SELECT * FROM word WHERE furigana != '' AND ignored = 0 ORDER BY last_looked_up_at DESC")
    suspend fun withCorrectedFurigana(): List<WordEntity>

    @Query("UPDATE word SET furigana = :furigana WHERE id = :id")
    suspend fun setFurigana(id: Long, furigana: String)

    @Query("UPDATE word SET starred = :starred WHERE id = :id")
    suspend fun setStarred(id: Long, starred: Boolean)

    @Query("UPDATE word SET anki_note_id = :noteId, scheduler = CASE WHEN :noteId IS NULL THEN 'HONMANI' ELSE 'ANKI' END WHERE id = :id")
    suspend fun setAnkiNote(id: Long, noteId: Long?)

    /** Words among [expressions] that are in Anki, to mark them in the lookup panel. */
    @Query("SELECT * FROM word WHERE anki_note_id IS NOT NULL AND expression IN (:expressions)")
    suspend fun inAnki(expressions: List<String>): List<WordEntity>

    @Query("UPDATE word SET status = 'LEARNED' WHERE id = :id")
    suspend fun markLearned(id: Long)

    @Query("UPDATE word SET status = 'LEARNING' WHERE id = :id")
    suspend fun markLearning(id: Long)

    /** Deletes the word with its lookups. */
    @Query("DELETE FROM word WHERE id = :id")
    suspend fun delete(id: Long)

    @Insert
    suspend fun insertLookup(lookup: WordLookupEntity): Long

    @Insert
    suspend fun insertLookups(lookups: List<WordLookupEntity>)

    @Query("SELECT * FROM word_lookup WHERE word_id = :wordId ORDER BY id")
    suspend fun allLookups(wordId: Long): List<WordLookupEntity>

    /** The latest lookups of a word, newest first. */
    @Query(
        "SELECT * FROM word_lookup WHERE word_id = :wordId " +
            "ORDER BY looked_up_at DESC, id DESC LIMIT :limit",
    )
    fun observeLookups(wordId: Long, limit: Int): Flow<List<WordLookupEntity>>

    @Query(
        "SELECT * FROM word_lookup WHERE word_id = :wordId " +
            "ORDER BY looked_up_at DESC, id DESC LIMIT 1",
    )
    suspend fun latestLookup(wordId: Long): WordLookupEntity?

    /** Keeps the [keep] latest lookups of a word. */
    @Query(
        "DELETE FROM word_lookup WHERE word_id = :wordId AND id NOT IN " +
            "(SELECT id FROM word_lookup WHERE word_id = :wordId " +
            "ORDER BY looked_up_at DESC, id DESC LIMIT :keep)",
    )
    suspend fun pruneLookups(wordId: Long, keep: Int)
}

private const val SUGGESTED = "ignored = 0 AND status = 'LEARNING' AND anki_note_id IS NULL AND lookup_count >= 2"
