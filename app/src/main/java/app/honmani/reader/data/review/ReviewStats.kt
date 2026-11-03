package app.honmani.reader.data.review

import java.time.ZoneId

/** What the Progress tab shows about reviews. */
data class ReviewSummary(
    /** Real reviews since the start of the local day, and the words among them reviewed for the first time. */
    val today: Int = 0,
    val newToday: Int = 0,
    /** Estimated time spent on today's reviews ([ReviewStats.timeSpent]), milliseconds. */
    val todayMillis: Long = 0,
    /** Share of the last [ReviewStats.RETENTION_DAYS] days' reviews of known cards that passed; null without any. */
    val retention: Double? = null,
    val retentionReviews: Int = 0,
    /** Cards in review (learning, not new and not suspended). */
    val inReview: Int = 0,
    /** Any review ever made or card introduced: the tiles are hidden before that. */
    val any: Boolean = false,
)

/** Review statistics from the log. Pure, so the numbers can be tested without a database. */
object ReviewStats {
    const val RETENTION_DAYS = 30

    /**
     * The log has no durations, so the time is estimated from the answers: each review costs the
     * time since the answer before it, at most [MAX_GAP_MS]; one that follows a longer pause (or
     * is the first of the day) costs [FIRST_MS], about the time to read a card and answer.
     */
    const val MAX_GAP_MS = 60_000L
    const val FIRST_MS = 10_000L

    /** [logs] oldest first (they may reach back before [from]); the time of those at [from] or later. */
    fun timeSpent(logs: List<ReviewLogEntity>, from: Long): Long {
        var total = 0L
        var previous: Long? = null
        for (log in logs.sortedBy { it.at }) {
            if (log.at >= from) {
                val gap = previous?.let { log.at - it }
                total += if (gap != null && gap in 0..MAX_GAP_MS) gap else FIRST_MS
            }
            previous = log.at
        }
        return total
    }

    /**
     * Passed reviews over all of those in [from]..now by cards that had been graded before: the
     * first grading of a word is learning it, not remembering it. Null if there are none.
     */
    fun retention(logs: List<ReviewLogEntity>, from: Long): Pair<Double, Int>? {
        val reviews = logs.filter { it.at >= from && it.elapsedDays > 0.0 }
        if (reviews.isEmpty()) return null
        return reviews.count { it.grade != Grade.AGAIN.value }.toDouble() / reviews.size to reviews.size
    }

    fun summary(logs: List<ReviewLogEntity>, cardsInReview: Int, anyCard: Boolean, newToday: Int, now: Long, zone: ZoneId, startHour: Int = 0): ReviewSummary {
        val dayStart = ReviewRules.startOfDay(now, zone, startHour = startHour)
        val retention = retention(logs, now - RETENTION_DAYS * ReviewRules.DAY_MS)
        return ReviewSummary(
            today = logs.count { it.at >= dayStart },
            newToday = newToday,
            todayMillis = timeSpent(logs, dayStart),
            retention = retention?.first,
            retentionReviews = retention?.second ?: 0,
            inReview = cardsInReview,
            any = logs.isNotEmpty() || anyCard,
        )
    }
}
