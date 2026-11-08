package app.honmani.reader.data.review

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import androidx.room.Upsert
import app.honmani.reader.data.merge.SyncKeys
import app.honmani.reader.data.merge.TombstoneWriter
import app.honmani.reader.data.merge.UpdateClock
import app.honmani.reader.data.words.WordEntity
import kotlinx.coroutines.flow.Flow

/** A card with its word. */
data class CardWithWord(
    @Embedded val card: ReviewCardEntity,
    @Relation(parentColumn = "word_id", entityColumn = "id") val word: WordEntity,
)

/** A word with a card in review: its id and how it is written. */
data class ReviewWord(val id: Long, val expression: String)

/** A word reviewed today; [carried]: its first review was before today (a new card is not [carried]). */
data class ReviewedToday(val wordId: Long, val carried: Boolean)

@Dao
interface ReviewDao : TombstoneWriter {
    /** Words in review under Honmani's own scheduler, the only ones reading can move. */
    @Query(
        "SELECT w.id AS id, w.expression AS expression FROM review_card c JOIN word w ON w.id = c.word_id " +
            "WHERE c.state = 'REVIEW' AND w.scheduler = 'HONMANI' AND w.ignored = 0",
    )
    suspend fun reviewWords(): List<ReviewWord>

    @Query("SELECT * FROM review_card WHERE word_id = :wordId")
    suspend fun find(wordId: Long): ReviewCardEntity?

    @Query("SELECT * FROM review_card")
    fun observeAll(): Flow<List<ReviewCardEntity>>

    @Query("SELECT COUNT(*) FROM review_card")
    fun observeCardCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM review_card WHERE state = 'REVIEW'")
    fun observeCardsInReviewCount(): Flow<Int>

    /** Creates the card if the word has none; -1 if it already has one. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCardRow(card: ReviewCardEntity): Long

    @Query("SELECT * FROM word WHERE id = :id")
    suspend fun wordById(id: Long): WordEntity?

    /**
     * A new card removes the marker of a deleted one. Never answered, it knows nothing yet: unstamped,
     * so it can't replace the review memory of the same word on another phone.
     */
    @Transaction
    suspend fun insertIfMissing(card: ReviewCardEntity): Long {
        val id = insertCardRow(card.copy(updatedAt = if (card.reps == 0) 0 else UpdateClock.now()))
        if (id != -1L) clearCardTombstone(card.wordId)
        return id
    }

    @Upsert
    suspend fun upsertRow(card: ReviewCardEntity)

    /** Stamped now. */
    @Transaction
    suspend fun upsert(card: ReviewCardEntity) {
        upsertRow(card.copy(updatedAt = UpdateClock.now()))
        clearCardTombstone(card.wordId)
    }

    /**
     * Puts back the card an answer started from. A card that was never answered (reps 0) knows nothing:
     * unstamped like [insertIfMissing], so the blank card can't replace the same word's real review
     * memory from another phone. Any other card is stamped now, like [upsert].
     */
    @Transaction
    suspend fun restoreBefore(card: ReviewCardEntity) {
        upsertRow(card.copy(updatedAt = if (card.reps == 0) 0 else UpdateClock.now()))
        clearCardTombstone(card.wordId)
    }

    @Query("DELETE FROM review_card WHERE word_id = :wordId")
    suspend fun deleteCardRow(wordId: Long)

    /** Deletes the card and leaves a marker, in one transaction. */
    @Transaction
    suspend fun delete(wordId: Long) {
        val word = wordById(wordId)
        if (word != null && find(wordId) != null) {
            putTombstone(SyncKeys.REVIEW_CARD, SyncKeys.reviewCard(word.expression, word.reading))
        }
        deleteCardRow(wordId)
    }

    /** Cards in review of Honmani's own scheduler that are due before [cutoff], not buried at [now] and not retired. */
    @Query("SELECT COUNT(*) FROM review_card c JOIN word w ON w.id = c.word_id WHERE $DUE")
    suspend fun dueCount(cutoff: Long, now: Long): Int

    /** The due cards' words, most overdue first (the daily limit cuts this list). */
    @Query("SELECT w.id AS id, w.expression AS expression FROM review_card c JOIN word w ON w.id = c.word_id WHERE $DUE ORDER BY c.due, c.word_id")
    fun observeDueWords(cutoff: Long, now: Long): Flow<List<ReviewWord>>

    @Query("SELECT w.id AS id, w.expression AS expression FROM review_card c JOIN word w ON w.id = c.word_id WHERE $DUE ORDER BY c.due, c.word_id")
    suspend fun dueWords(cutoff: Long, now: Long): List<ReviewWord>

    /** Words with a review since [from] (the start of today), and whether they had one before. */
    @Query(
        "SELECT l.word_id AS wordId, (SELECT MIN(r.at) FROM review_log r WHERE r.word_id = l.word_id) < :from AS carried " +
            "FROM review_log l WHERE l.at >= :from GROUP BY l.word_id",
    )
    fun observeReviewedSince(from: Long): Flow<List<ReviewedToday>>

    @Query(
        "SELECT l.word_id AS wordId, (SELECT MIN(r.at) FROM review_log r WHERE r.word_id = l.word_id) < :from AS carried " +
            "FROM review_log l WHERE l.at >= :from GROUP BY l.word_id",
    )
    suspend fun reviewedSince(from: Long): List<ReviewedToday>

    /** Most overdue first. */
    @Transaction
    @Query("SELECT c.* FROM review_card c JOIN word w ON w.id = c.word_id WHERE $DUE ORDER BY c.due, c.word_id LIMIT :limit")
    suspend fun dueCards(cutoff: Long, now: Long, limit: Int): List<CardWithWord>

    /** Introduced cards not reviewed yet, in the order they were introduced. */
    @Transaction
    @Query(
        "SELECT c.* FROM review_card c JOIN word w ON w.id = c.word_id WHERE c.state = 'NEW' AND w.scheduler = 'HONMANI' AND w.ignored = 0 " +
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
            "AND w.ignored = 0 AND w.status = 'LEARNING' AND c.due >= :cutoff AND (c.buried_until IS NULL OR c.buried_until <= :now) " +
            "ORDER BY c.due, c.word_id",
    )
    suspend fun notDueLearningCards(cutoff: Long, now: Long): List<CardWithWord>

    @Query("SELECT COUNT(*) FROM word w WHERE $UNINTRODUCED")
    fun observeUnintroducedCount(): Flow<Int>

    /** Introduced, not yet reviewed and not buried at [now]. */
    @Query(
        "SELECT COUNT(*) FROM review_card c JOIN word w ON w.id = c.word_id WHERE c.state = 'NEW' AND w.scheduler = 'HONMANI' AND w.ignored = 0 " +
            "AND (c.buried_until IS NULL OR c.buried_until <= :now)",
    )
    fun observeNewCardCount(now: Long): Flow<Int>

    @Query("SELECT COUNT(*) FROM (SELECT word_id FROM review_log GROUP BY word_id HAVING MIN(at) >= :from)")
    fun observeFirstReviewsSince(from: Long): Flow<Int>

    @Insert
    suspend fun insertLogRow(log: ReviewLogEntity): Long

    @Transaction
    suspend fun insertLog(log: ReviewLogEntity): Long {
        wordById(log.wordId)?.let { clearTombstone(SyncKeys.REVIEW_LOG, SyncKeys.reviewLog(it.expression, it.reading, log.at, log.grade)) }
        return insertLogRow(log)
    }

    @Query("SELECT * FROM review_log WHERE id = :id")
    suspend fun logById(id: Long): ReviewLogEntity?

    @Query("DELETE FROM review_log WHERE id = :id")
    suspend fun deleteLogRow(id: Long)

    /** The review Undo: deletes the log entry and leaves a marker, in one transaction. */
    @Transaction
    suspend fun deleteLog(id: Long) {
        val log = logById(id) ?: return
        wordById(log.wordId)?.let { putTombstone(SyncKeys.REVIEW_LOG, SyncKeys.reviewLog(it.expression, it.reading, log.at, log.grade)) }
        deleteLogRow(id)
    }

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

private suspend fun ReviewDao.clearCardTombstone(wordId: Long) {
    val word = wordById(wordId) ?: return
    clearTombstone(SyncKeys.REVIEW_CARD, SyncKeys.reviewCard(word.expression, word.reading))
}

/** Due: in review, Honmani's own, due before the cutoff, not buried, and not retired (stability under [ReviewRules.RETIRE_AFTER_DAYS]). */
private const val DUE =
    "c.state = 'REVIEW' AND w.scheduler = 'HONMANI' AND w.ignored = 0 AND c.due < :cutoff AND (c.buried_until IS NULL OR c.buried_until <= :now) " +
        "AND c.stability < ${ReviewRules.RETIRE_AFTER_DAYS}"

private const val UNINTRODUCED =
    "w.ignored = 0 AND w.status = 'LEARNING' AND w.scheduler = 'HONMANI' AND NOT EXISTS (SELECT 1 FROM review_card c WHERE c.word_id = w.id)"
