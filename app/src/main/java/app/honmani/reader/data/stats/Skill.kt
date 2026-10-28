package app.honmani.reader.data.stats

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** The reader's skill on a week's last day, on the books' 1–100 difficulty scale; null before any measurement. */
data class SkillPoint(val weekEnd: LocalDate, val skill: Int?)

data class SkillEstimate(
    /**
     * Today's skill: [fluency] and the [vocabulary] part combined ([Skill.combine]); null while
     * there is too little reading in graded books ([Skill.MIN_SECONDS]).
     */
    val current: Int?,
    /** The reading fluency (speed against difficulty), one point per week, oldest first, the last ending today. */
    val trend: List<SkillPoint>,
    /** Time read in books that have a difficulty. */
    val gradedSeconds: Long,
    /** Characters read in books that have a difficulty. */
    val gradedCharacters: Long = 0,
    /** Today's skill from reading speed alone. */
    val fluency: Int? = current,
    /** The vocabulary part, once enough has been read ([Skill.VOCABULARY_MIN_CHARACTERS]). */
    val vocabulary: VocabularySkill? = null,
)

/** The vocabulary estimate on the difficulty scale: [score] is the difficulty of books with an R95 of [estimate]'s words. */
data class VocabularySkill(val score: Int, val estimate: VocabularyEstimate)

/**
 * How hard a book the reader reads comfortably, from their reading speed in books of known
 * difficulty: a week in a book counts as the book's difficulty, plus [POINTS_PER_DOUBLING] for
 * every doubling of speed above [REFERENCE_SPEED] (minus below it). Reading a level-50 book at
 * 8,000 characters an hour counts as 50, at 16,000 as 60. Recent weeks count more (half-life
 * [HALF_LIFE_DAYS] days), and longer reading more than shorter. Speed depends on the screen and
 * the text direction, so this compares a reader only with themselves.
 *
 * Only a book's first read counts: once a book's reading adds up to its length (plus
 * [REREAD_SLACK] for pages read again while going back and forth), the rest is a reread, fast
 * because the story and its words are remembered, not because a new book of that difficulty
 * would read as fast. Rereads still count as time and characters read.
 */
object Skill {
    const val REFERENCE_SPEED = 8_000.0
    const val POINTS_PER_DOUBLING = 10.0
    const val HALF_LIFE_DAYS = 30.0

    /** Half an hour in graded books before a skill is shown. */
    const val MIN_SECONDS = 1_800L

    /** Share of a book that may be read twice before its reading counts as a reread. */
    const val REREAD_SLACK = 0.1

    /** A book-week counts from ten minutes, like the reading speed in the statistics. */
    private const val MIN_WEEK_SECONDS = 600L

    private class Measurement(val weeksAgo: Int, val level: Double, val seconds: Long)

    /**
     * [difficulty] gives a book's score (1–100), or null for books not graded; [length] its
     * characters as the reading counts them, or null if not known (then all its reading counts).
     */
    fun estimate(
        rows: List<ReadingDayEntity>,
        difficulty: (bookId: Long) -> Int?,
        today: LocalDate,
        weeks: Int = 12,
        length: (bookId: Long) -> Long? = { null },
    ): SkillEstimate {
        var gradedSeconds = 0L
        var gradedCharacters = 0L
        val firstRead = firstReadShares(rows, length)
        val measurements = rows
            .mapNotNull { row ->
                val score = difficulty(row.bookId) ?: return@mapNotNull null
                val daysAgo = ChronoUnit.DAYS.between(LocalDate.parse(row.day), today)
                if (daysAgo < 0) return@mapNotNull null
                gradedSeconds += row.seconds
                gradedCharacters += row.characters
                Triple(row.bookId to (daysAgo / 7).toInt(), score, row)
            }
            .groupBy({ it.first }, { it.second to it.third })
            .mapNotNull { (key, list) ->
                val seconds = list.sumOf { (_, row) -> row.seconds * firstRead.getValue(row) }.roundToLong()
                val characters = list.sumOf { (_, row) -> row.characters * firstRead.getValue(row) }
                if (seconds < MIN_WEEK_SECONDS || characters <= 0) return@mapNotNull null
                val speed = characters * 3_600.0 / seconds
                val level = list.first().first + POINTS_PER_DOUBLING * ln(speed / REFERENCE_SPEED) / ln(2.0)
                Measurement(key.second, level.coerceIn(1.0, 100.0), seconds)
            }

        fun skillAt(weeksAgo: Int): Int? {
            var weighted = 0.0
            var weights = 0.0
            for (m in measurements) {
                if (m.weeksAgo < weeksAgo) continue
                val weight = m.seconds * 0.5.pow((m.weeksAgo - weeksAgo) * 7 / HALF_LIFE_DAYS)
                weighted += m.level * weight
                weights += weight
            }
            return if (weights > 0) (weighted / weights).roundToInt() else null
        }

        return SkillEstimate(
            current = if (gradedSeconds >= MIN_SECONDS) skillAt(0) else null,
            trend = (weeks - 1 downTo 0).map { SkillPoint(today.minusDays(7L * it), skillAt(it)) },
            gradedSeconds = gradedSeconds,
            gradedCharacters = gradedCharacters,
        )
    }

    /**
     * The share of each row (0 to 1) that is a book's first read: its rows in date order count
     * until their characters reach the book's length plus [REREAD_SLACK]. A row with time only
     * counts while the book is still within its first read.
     */
    private fun firstReadShares(rows: List<ReadingDayEntity>, length: (bookId: Long) -> Long?): Map<ReadingDayEntity, Double> {
        val shares = HashMap<ReadingDayEntity, Double>(rows.size)
        for ((bookId, bookRows) in rows.groupBy { it.bookId }) {
            val limit = length(bookId)?.takeIf { it > 0 }?.let { it * (1 + REREAD_SLACK) }
            var counted = 0.0
            for (row in bookRows.sortedBy { it.day }) {
                shares[row] = when {
                    limit == null -> 1.0
                    row.characters <= 0 -> if (counted < limit) 1.0 else 0.0
                    else -> ((limit - counted) / row.characters).coerceIn(0.0, 1.0)
                }
                counted += row.characters
            }
        }
        return shares
    }

    /** The vocabulary needs this much reading in graded books before it counts. */
    const val VOCABULARY_MIN_CHARACTERS = 20_000L

    /** From this much reading on, the vocabulary has its full weight, [VOCABULARY_WEIGHT]. */
    const val VOCABULARY_FULL_CHARACTERS = 100_000L
    const val VOCABULARY_WEIGHT = 0.6

    /**
     * [estimate] with the vocabulary part: the skill becomes a weighted mean of fluency and
     * vocabulary, the vocabulary's weight growing with the characters read to
     * [VOCABULARY_WEIGHT]. Speed data comes first; the vocabulary needs more reading to say much.
     */
    fun combine(estimate: SkillEstimate, vocabulary: VocabularySkill?): SkillEstimate {
        val fluency = estimate.fluency
        if (vocabulary == null || estimate.gradedCharacters < VOCABULARY_MIN_CHARACTERS || fluency == null) {
            return estimate.copy(vocabulary = null)
        }
        val weight = VOCABULARY_WEIGHT * (estimate.gradedCharacters.toDouble() / VOCABULARY_FULL_CHARACTERS).coerceAtMost(1.0)
        return estimate.copy(
            current = (weight * vocabulary.score + (1 - weight) * fluency).roundToInt(),
            vocabulary = vocabulary,
        )
    }

    /** The best fit is this much harder than the reader's skill: a little stretch. */
    const val STRETCH = 2

    /**
     * Up to [count] of [books] (id to difficulty) that fit a reader of [skill]: from a little
     * easier to a little harder, the best stretch first.
     */
    fun fitting(books: List<Pair<Long, Int>>, skill: Int, count: Int = 3): List<Long> =
        books.filter { (_, score) -> score in skill - 5..skill + 8 }
            .sortedBy { (_, score) -> kotlin.math.abs(score - (skill + STRETCH)) }
            .take(count)
            .map { it.first }
}
