package app.honmani.reader.data.review

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import app.honmani.reader.data.words.WordEntity

enum class ReviewState {
    /** Introduced to the review queue, not reviewed yet. */
    NEW,
    REVIEW,

    /** "Don't review": keeps its memory, ignores reads and lookups. */
    SUSPENDED,
}

/**
 * The memory of one word (FSRS, see [Fsrs]); a word without a row is not in review. Times are
 * epoch milliseconds; [due] of a card with a day interval is the start of that local day.
 */
@Entity(
    tableName = "review_card",
    foreignKeys = [
        ForeignKey(
            entity = WordEntity::class,
            parentColumns = ["id"],
            childColumns = ["word_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["state", "due"])],
)
data class ReviewCardEntity(
    @PrimaryKey @ColumnInfo(name = "word_id") val wordId: Long,
    val state: ReviewState = ReviewState.NEW,
    val due: Long,
    val stability: Double = 0.0,
    val difficulty: Double = 0.0,
    /** All gradings, real reviews and evidence from reading or lookups. */
    val reps: Int = 0,
    /** Times graded Again after the first grading. */
    val lapses: Int = 0,
    /** Real reviews (review screen) graded Hard or better; reading can't raise it. */
    @ColumnInfo(name = "passed_reviews") val passedReviews: Int = 0,
    /** The latest [Grade.value] of any kind of grading. */
    @ColumnInfo(name = "last_grade") val lastGrade: Int? = null,
    @ColumnInfo(name = "last_review_at") val lastReviewAt: Long? = null,
    /** Hidden from the queue until then ("until tomorrow": the next local midnight). */
    @ColumnInfo(name = "buried_until") val buriedUntil: Long? = null,
    @ColumnInfo(name = "added_at") val addedAt: Long,
)

/**
 * A real review on the review screen (not reading or lookups), for retention statistics and
 * export. Not tied to the word by a foreign key, like lookup events, so statistics outlive it.
 */
@Entity(
    tableName = "review_log",
    indices = [Index(value = ["word_id", "at"]), Index(value = ["at"])],
)
data class ReviewLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "word_id") val wordId: Long,
    val at: Long,
    /** [Grade.value]. */
    val grade: Int,
    /** Days since the previous grading; 0 for the first. */
    @ColumnInfo(name = "elapsed_days") val elapsedDays: Double,
)

/** That a word was read (met in a book without a lookup) [count] times on a local day. */
@Entity(
    tableName = "read_day",
    primaryKeys = ["word_id", "day"],
    foreignKeys = [
        ForeignKey(
            entity = WordEntity::class,
            parentColumns = ["id"],
            childColumns = ["word_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class ReadDayEntity(
    @ColumnInfo(name = "word_id") val wordId: Long,
    /** The local date as days since 1970-01-01 (`LocalDate.toEpochDay`). */
    val day: Long,
    val count: Int = 1,
)
