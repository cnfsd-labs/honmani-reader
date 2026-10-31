package app.honmani.reader.data.stats

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingGoalTest {
    private val today = LocalDate.of(2026, 10, 20)

    private fun read(daysAgo: Long, minutes: Int, book: Long = 1) =
        ReadingDayEntity(book, today.minusDays(daysAgo).toString(), seconds = minutes * 60L)

    @Test
    fun todayCountsAllBooksTogetherAgainstTheGoal() {
        val progress = ReadingGoal.progress(listOf(read(0, 6, book = 1), read(0, 4, book = 2)), today, goalMinutes = 20)

        assertEquals(600L, progress.secondsToday)
        assertEquals(0.5f, progress.fraction, 0f)
        assertFalse(progress.met)
    }

    @Test
    fun theBarStopsAtFullAndTheGoalIsMetFromTheExactMinutes() {
        assertTrue(ReadingGoal.progress(listOf(read(0, 20)), today, 20).met)
        val over = ReadingGoal.progress(listOf(read(0, 50)), today, 20)
        assertEquals(1f, over.fraction, 0f)
        assertFalse(ReadingGoal.progress(listOf(ReadingDayEntity(1, today.toString(), seconds = 1_199)), today, 20).met)
    }

    @Test
    fun nothingReadIsZeroWithoutAStreak() {
        val progress = ReadingGoal.progress(emptyList(), today, 20)

        assertEquals(0L, progress.secondsToday)
        assertEquals(0f, progress.fraction, 0f)
        assertEquals(0, progress.streak)
    }

    @Test
    fun theStreakCountsDaysThatReachedTheGoalUpToToday() {
        val rows = listOf(read(0, 25), read(1, 20), read(2, 30), read(3, 19), read(4, 60))

        assertEquals(3, ReadingGoal.progress(rows, today, 20).streak)
    }

    @Test
    fun theStreakSurvivesUntilTheDayIsOver() {
        // Yesterday and the day before reached it; today is still short.
        val rows = listOf(read(0, 5), read(1, 20), read(2, 21))

        assertEquals(2, ReadingGoal.progress(rows, today, 20).streak)
    }

    @Test
    fun aMissedYesterdayEndsTheStreak() {
        val rows = listOf(read(0, 5), read(2, 40), read(3, 40))

        assertEquals(0, ReadingGoal.progress(rows, today, 20).streak)
    }

    @Test
    fun aDayCountsWithTheTimeOfAllItsBooks() {
        val rows = listOf(read(1, 12, book = 1), read(1, 12, book = 2))

        assertEquals(1, ReadingGoal.progress(rows, today, 20).streak)
    }
}
