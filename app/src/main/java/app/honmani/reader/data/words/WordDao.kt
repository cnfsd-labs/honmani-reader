package app.honmani.reader.data.words

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface WordDao {
    /** Words being learned, highest score first. */
    @Query("SELECT * FROM word WHERE status = 'LEARNING' ORDER BY rank_key DESC, id DESC")
    fun observeLearning(): Flow<List<WordEntity>>

    @Query("SELECT * FROM word WHERE starred = 1 ORDER BY rank_key DESC, id DESC")
    fun observeStarred(): Flow<List<WordEntity>>

    @Query("SELECT * FROM word WHERE status = 'LEARNED' ORDER BY expression, reading")
    fun observeLearned(): Flow<List<WordEntity>>

    /** Every word, learning ones first: one query, so a word moving between the two lists is never in both. */
    @Query("SELECT * FROM word ORDER BY status = 'LEARNED', CASE WHEN status = 'LEARNED' THEN expression END, CASE WHEN status = 'LEARNED' THEN reading END, rank_key DESC, id DESC")
    fun observeAll(): Flow<List<WordEntity>>

    /**
     * Words suggested for Anki: being learned, not in Anki yet, and looked up more than once
     * (a repeat in the same sentence within the hour doesn't count, see [WordRepository.record]).
     */
    @Query("SELECT * FROM word WHERE $SUGGESTED ORDER BY rank_key DESC, id DESC")
    fun observeSuggested(): Flow<List<WordEntity>>

    @Query("SELECT COUNT(*) FROM word WHERE $SUGGESTED")
    fun countSuggested(): Flow<Int>

    @Query("SELECT COUNT(*) FROM word WHERE status = 'LEARNING'")
    fun countLearning(): Flow<Int>

    @Query("SELECT COUNT(*) FROM word WHERE starred = 1")
    fun countStarred(): Flow<Int>

    @Query("SELECT COUNT(*) FROM word WHERE status = 'LEARNED'")
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
    @Query("SELECT * FROM word WHERE last_looked_up_at >= :from ORDER BY last_looked_up_at DESC, id DESC")
    fun observeLookedUpSince(from: Long): Flow<List<WordEntity>>

    /** Lookups since [from] (epoch milliseconds). */
    @Query("SELECT COUNT(*) FROM lookup_event WHERE looked_up_at >= :from")
    fun countLookupsSince(from: Long): Flow<Int>

    @Query("SELECT COUNT(*) FROM lookup_event WHERE book_id = :bookId")
    fun countLookups(bookId: Long): Flow<Int>

    @Insert
    suspend fun insertLookupEvent(event: LookupEventEntity)

    @Query("SELECT * FROM word WHERE id = :id")
    suspend fun find(id: Long): WordEntity?

    @Query("SELECT * FROM word WHERE expression = :expression AND reading = :reading")
    suspend fun find(expression: String, reading: String): WordEntity?

    @Insert
    suspend fun insert(word: WordEntity): Long

    @Update
    suspend fun update(word: WordEntity)

    @Query("UPDATE word SET starred = :starred WHERE id = :id")
    suspend fun setStarred(id: Long, starred: Boolean)

    @Query("UPDATE word SET anki_note_id = :noteId WHERE id = :id")
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

private const val SUGGESTED = "status = 'LEARNING' AND anki_note_id IS NULL AND lookup_count >= 2"
