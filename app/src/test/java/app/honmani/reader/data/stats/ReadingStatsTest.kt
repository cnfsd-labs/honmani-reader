package app.honmani.reader.data.stats

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingStatsTest {
    private val today = LocalDate.of(2026, 10, 17)

    private fun row(book: Long, daysAgo: Long, minutes: Long, characters: Long = 0) =
        ReadingDayEntity(book, today.minusDays(daysAgo).toString(), minutes * 60, characters)

    private val rows = listOf(
        row(1, 0, 20, 4_000),
        row(2, 0, 10, 1_000),
        row(1, 1, 30, 6_000),
        row(1, 2, 15, 2_000),
        // A gap of a day ends the streak.
        row(1, 4, 60, 12_000),
        row(1, 10, 45, 9_000),
    )

    @Test
    fun today() {
        val summary = ReadingStats.period(rows, today, StatsPeriod.TODAY)

        assertEquals(30 * 60L, summary.seconds)
        assertEquals(5_000L, summary.characters)
        assertEquals(3, summary.streak)
        assertEquals(10_000L, summary.charactersPerHour)
        // The 7 days before today: 20,000 characters in 105 minutes.
        assertEquals(20_000L * 3_600 / (105 * 60), summary.earlierCharactersPerHour)
        assertEquals(listOf(1L, 2L), summary.books.map { it.first })
        assertEquals(DayTotal(today, 20 * 60, 4_000), summary.books.first().second)
        assertTrue(summary.chart.isEmpty())
    }

    @Test
    fun theLastSevenDays_comparedWithTheSevenBefore() {
        val summary = ReadingStats.period(rows, today, StatsPeriod.WEEK)

        assertEquals((30 + 30 + 15 + 60) * 60L, summary.seconds)
        assertEquals(25_000L, summary.characters)
        assertEquals(4, summary.daysRead)
        assertEquals(7, summary.chart.size)
        assertEquals(today, summary.chart.last().day)
        assertEquals(9_000L * 3_600 / (45 * 60), summary.earlierCharactersPerHour)
        assertTrue(summary.books.isEmpty())
    }

    @Test
    fun allTime_speedIsTheRecentOne_withATrendPerWeek() {
        // Slow at first, faster now.
        val history = listOf(row(1, 60, 60, 6_000), row(1, 59, 60, 6_000), row(1, 3, 60, 12_000), row(1, 1, 30, 6_000))

        val summary = ReadingStats.period(history, today, StatsPeriod.ALL)

        assertEquals(210 * 60L, summary.seconds)
        assertEquals(30_000L, summary.characters)
        assertEquals(4, summary.daysRead)
        assertEquals(12_000L, summary.charactersPerHour)
        assertEquals(6_000L, summary.earlierCharactersPerHour)
        assertEquals(ReadingStats.TREND_WEEKS, summary.speedTrend.size)
        assertEquals(12_000L, summary.speedTrend.last().charactersPerHour)
        assertNull(summary.speedTrend.first().charactersPerHour)
        assertEquals(ReadingStats.TREND_WEEKS, summary.chart.size)
        assertEquals(90 * 60L, summary.chart.last().seconds)
    }

    @Test
    fun allTime_withLittleHistoryHasNothingToCompare() {
        val summary = ReadingStats.period(rows, today, StatsPeriod.ALL)

        assertNull(summary.earlierCharactersPerHour)
        assertTrue(summary.anyReading)
    }

    @Test
    fun nothingReadYet() {
        // A new install: this closed the statistics screen ("All time" had no first day).
        for (period in StatsPeriod.entries) {
            val summary = ReadingStats.period(emptyList(), today, period)

            assertEquals(0L, summary.seconds)
            assertNull(summary.earlierCharactersPerHour)
            assertFalse(summary.anyReading)
        }
    }

    @Test
    fun theStreakStillCountsBeforeReadingToday_andShortDaysDontCount() {
        val short = listOf(row(1, 1, 10), row(1, 2, 10), ReadingDayEntity(1, today.minusDays(3).toString(), 30, 0))

        val summary = ReadingStats.period(short, today, StatsPeriod.ALL)

        assertEquals(2, summary.streak)
        assertEquals(2, summary.daysRead)
        // Under ten minutes of reading: no speed yet.
        assertNull(ReadingStats.period(listOf(row(1, 0, 5, 900)), today, StatsPeriod.TODAY).charactersPerHour)
        assertFalse(ReadingStats.period(emptyList(), today, StatsPeriod.TODAY).anyReading)
    }

    @Test
    fun oneBook() {
        val reading = ReadingStats.book(listOf(row(1, 3, 40, 8_000), row(1, 1, 20, 4_000)))

        assertEquals(3_600L, reading.seconds)
        assertEquals(12_000L, reading.characters)
        assertEquals(2, reading.daysRead)
        assertEquals(today.minusDays(3), reading.firstDay)
        assertEquals(today.minusDays(1), reading.lastDay)
        assertEquals(12_000L, reading.charactersPerHour)
    }

    @Test
    fun aSpeedBelowOneCharacterAnHourIsNoSpeed() {
        assertNull(ReadingStats.speed(seconds = 7_200, characters = 1))
        assertEquals(3_600L, ReadingStats.speed(seconds = 3_600, characters = 3_600))
    }
}
