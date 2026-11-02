package app.honmani.reader.data.review

import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewStatsTest {
    private val zone = ZoneId.of("UTC")
    private val day = ReviewRules.DAY_MS
    private val now = 20_000 * day + 12 * 3_600_000L
    private val dayStart = 20_000 * day
    private var nextId = 1L

    private fun log(at: Long, grade: Grade = Grade.GOOD, elapsed: Double = 1.0, word: Long = 1) =
        ReviewLogEntity(id = nextId++, wordId = word, at = at, grade = grade.value, elapsedDays = elapsed)

    @Test
    fun timeIsTheGapBetweenAnswersWithPausesAndTheFirstCountingAFixedShare() {
        val logs = listOf(
            log(dayStart - day), // yesterday: not today's time, but today's first review is not close to it
            log(dayStart + 3_600_000),
            log(dayStart + 3_600_000 + 5_000),
            log(dayStart + 3_600_000 + 25_000),
            log(dayStart + 3_600_000 + 25_000 + 600_000), // a long pause: a new sitting
            log(dayStart + 3_600_000 + 25_000 + 600_000 + 40_000),
        )

        val time = ReviewStats.timeSpent(logs, dayStart)

        assertEquals(ReviewStats.FIRST_MS + 5_000 + 20_000 + ReviewStats.FIRST_MS + 40_000, time)
    }

    @Test
    fun noReviewsTodayTakeNoTime() {
        assertEquals(0L, ReviewStats.timeSpent(listOf(log(dayStart - 5_000)), dayStart))
        assertEquals(0L, ReviewStats.timeSpent(emptyList(), dayStart))
    }

    @Test
    fun retentionIsTheShareOfPassedReviewsOfKnownCardsInTheLast30Days() {
        val from = now - 30 * day
        val logs = listOf(
            log(now - 40 * day, Grade.AGAIN), // too old
            log(now - 20 * day, Grade.GOOD, elapsed = 0.0), // the first grading is learning, not remembering
            log(now - 10 * day, Grade.GOOD),
            log(now - 9 * day, Grade.GOOD),
            log(now - 8 * day, Grade.AGAIN),
            log(now - 7 * day, Grade.HARD),
        )

        val (retention, count) = ReviewStats.retention(logs, from)!!

        assertEquals(0.75, retention, 1e-9)
        assertEquals(4, count)
    }

    @Test
    fun retentionIsNullWithoutReviewsOfKnownCards() {
        assertNull(ReviewStats.retention(listOf(log(now - day, elapsed = 0.0)), now - 30 * day))
        assertNull(ReviewStats.retention(emptyList(), 0))
    }

    @Test
    fun summaryCountsTodaysReviewsAndHidesWhenThereIsNothing() {
        val logs = listOf(log(dayStart - 1_000), log(dayStart + 10_000), log(dayStart + 20_000, Grade.AGAIN))

        val summary = ReviewStats.summary(logs, cardsInReview = 7, anyCard = true, newToday = 1, now = now, zone = zone)

        assertEquals(2, summary.today)
        assertEquals(1, summary.newToday)
        assertEquals(7, summary.inReview)
        // The first of today follows an answer 11 s before midnight: that gap counts.
        assertEquals(11_000L + 10_000, summary.todayMillis)
        assertEquals(2.0 / 3, summary.retention!!, 1e-9)
        assertTrue(summary.any)
        assertFalse(ReviewStats.summary(emptyList(), 0, false, 0, now, zone).any)
    }
}
