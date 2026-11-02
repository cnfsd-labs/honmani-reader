package app.honmani.reader.data.review

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import androidx.room.Upsert
import app.honmani.reader.data.words.WordEntity
import kotlinx.coroutines.flow.Flow

/** A card with its word. */
data class CardWithWord(
    @Embedded val card: ReviewCardEntity,
    @Relation(parentColumn = "word_id", entityColumn = "id") val word: WordEntity,
)

/** A word with a card in review: its id and how it is written. */
data class ReviewWord(val id: Long, val expression: String)

@Dao
interface ReviewDao {
    /** Words in review under Honmani's own scheduler, the only ones reading can move. */
    @Query(
        "SELECT w.id AS id, w.expression AS expression FROM review_card c JOIN word w ON w.id = c.word_id " +
            "WHERE c.state = 'REVIEW' AND w.scheduler = 'HONMANI'",
    )
    suspend fun reviewWords(): List<ReviewWord>

    @Query("SELECT * FROM review_card WHERE word_id = :wordId")
    suspend fun find(wordId: Long): ReviewCardEntity?

    @Query("SELECT * FROM review_card")
    fun observeAll(): Flow<List<ReviewCardEntity>>

    /** Creates the card if the word has none; -1 if it already has one. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfMissing(card: ReviewCardEntity): Long

    @Upsert
    suspend fun upsert(card: ReviewCardEntity)

    @Query("DELETE FROM review_card WHERE word_id = :wordId")
    suspend fun delete(wordId: Long)

    /** Cards in review of Honmani's own scheduler that are due before [cutoff] and not buried at [now]. */
    @Query("SELECT COUNT(*) FROM review_card c JOIN word w ON w.id = c.word_id WHERE $DUE")
    fun observeDueCount(cutoff: Long, now: Long): Flow<Int>

    @Query("SELECT COUNT(*) FROM review_card c JOIN word w ON w.id = c.word_id WHERE $DUE")
    suspend fun dueCount(cutoff: Long, now: Long): Int

    /** Most overdue first. */
    @Transaction
    @Query("SELECT c.* FROM review_card c JOIN word w ON w.id = c.word_id WHERE $DUE ORDER BY c.due, c.word_id LIMIT :limit")
    suspend fun dueCards(cutoff: Long, now: Long, limit: Int): List<CardWithWord>

    /** Introduced cards not reviewed yet, in the order they were introduced. */
    @Transaction
    @Query(
        "SELECT c.* FROM review_card c JOIN word w ON w.id = c.word_id WHERE c.state = 'NEW' AND w.scheduler = 'HONMANI' " +
            "AND (c.buried_until IS NULL OR c.buried_until <= :now) ORDER BY c.added_at, c.word_id LIMIT :limit",
    )
    suspend fun newCards(now: Long, limit: Int): List<CardWithWord>

    @Transaction
    @Query("SELECT * FROM review_card WHERE word_id = :wordId")
    suspend fun cardWithWord(wordId: Long): CardWithWord?

    /** Learning words of Honmani's scheduler that have no card yet, best ranked first. */
    @Query("SELECT w.id FROM word w WHERE $UNINTRODUCED ORDER BY w.rank_key DESC, w.id DESC LIMIT :limit")
    suspend fun unintroducedIds(limit: Int): List<Long>

    /** The words of [unintroducedIds], all of them. */
    @Query("SELECT w.* FROM word w WHERE $UNINTRODUCED ORDER BY w.rank_key DESC, w.id DESC")
    suspend fun unintroducedWords(): List<WordEntity>

    /** Cards of Learning words in review that are not due yet, soonest due first (to review early). */
    @Transaction
    @Query(
        "SELECT c.* FROM review_card c JOIN word w ON w.id = c.word_id WHERE c.state = 'REVIEW' AND w.scheduler = 'HONMANI' " +
            "AND w.status = 'LEARNING' AND c.due >= :cutoff AND (c.buried_until IS NULL OR c.buried_until <= :now) " +
            "ORDER BY c.due, c.word_id",
    )
    suspend fun notDueLearningCards(cutoff: Long, now: Long): List<CardWithWord>

    @Query("SELECT COUNT(*) FROM word w WHERE $UNINTRODUCED")
    fun observeUnintroducedCount(): Flow<Int>

    /** Introduced, not yet reviewed and not buried at [now]. */
    @Query(
        "SELECT COUNT(*) FROM review_card c JOIN word w ON w.id = c.word_id WHERE c.state = 'NEW' AND w.scheduler = 'HONMANI' " +
            "AND (c.buried_until IS NULL OR c.buried_until <= :now)",
    )
    fun observeNewCardCount(now: Long): Flow<Int>

    @Query("SELECT COUNT(*) FROM (SELECT word_id FROM review_log GROUP BY word_id HAVING MIN(at) >= :from)")
    fun observeFirstReviewsSince(from: Long): Flow<Int>

    @Insert
    suspend fun insertLog(log: ReviewLogEntity): Long

    @Query("DELETE FROM review_log WHERE id = :id")
    suspend fun deleteLog(id: Long)

    @Query("SELECT COUNT(*) FROM review_log WHERE at >= :from")
    fun observeReviewsSince(from: Long): Flow<Int>

    @Query("SELECT * FROM review_log WHERE at >= :from ORDER BY at, id")
    fun observeLogsSince(from: Long): Flow<List<ReviewLogEntity>>

    @Query("SELECT COUNT(*) FROM review_log WHERE at >= :from")
    suspend fun reviewsSince(from: Long): Int

    /** Words whose first review was at [from] or later (new cards done today). */
    @Query("SELECT COUNT(*) FROM (SELECT word_id FROM review_log GROUP BY word_id HAVING MIN(at) >= :from)")
    suspend fun firstReviewsSince(from: Long): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertReadDay(day: ReadDayEntity): Long

    @Query("UPDATE read_day SET count = count + 1 WHERE word_id = :wordId AND day = :day")
    suspend fun incrementReadDay(wordId: Long, day: Long)

    @Query("SELECT COALESCE(SUM(count), 0) FROM read_day WHERE word_id = :wordId AND day = :day")
    suspend fun readsOn(wordId: Long, day: Long): Int
}

private const val DUE =
    "c.state = 'REVIEW' AND w.scheduler = 'HONMANI' AND c.due < :cutoff AND (c.buried_until IS NULL OR c.buried_until <= :now)"

private const val UNINTRODUCED =
    "w.status = 'LEARNING' AND w.scheduler = 'HONMANI' AND NOT EXISTS (SELECT 1 FROM review_card c WHERE c.word_id = w.id)"
