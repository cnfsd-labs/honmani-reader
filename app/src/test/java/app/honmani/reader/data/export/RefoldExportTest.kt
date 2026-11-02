package app.honmani.reader.data.export

import app.honmani.reader.data.stats.ReadingDayEntity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RefoldExportTest {
    private val header = "activityId,languageCode,attributedDate,duration,startTime,nickname,savedAt\r\n"
    private val today = LocalDate.of(2026, 11, 2)

    private fun day(book: Long, day: String, seconds: Long) = ReadingDayEntity(book, day, seconds, characters = 100)

    @Test
    fun theHeaderAndRowsAreExactlyRefoldsImportFormat() {
        val result = RefoldExport.build(
            days = listOf(day(1, "2026-11-01", 90), day(2, "2026-10-31", 3600)),
            titles = mapOf(1L to "注文の多い料理店", 2L to "Kokoro"),
            since = null,
            today = today,
        )
        assertEquals(
            header +
                "20101,ja,2026-10-31,3600000,,Kokoro,\r\n" +
                "20101,ja,2026-11-01,90000,,注文の多い料理店,\r\n",
            result.csv,
        )
        assertEquals(2, result.rows)
        assertEquals(LocalDate.of(2026, 11, 1), result.lastDay)
    }

    @Test
    fun durationIsWholeSecondsAsIntegerMilliseconds() {
        val csv = RefoldExport.build(listOf(day(1, "2026-11-01", 1)), mapOf(1L to "A"), null, today).csv
        assertEquals(header + "20101,ja,2026-11-01,1000,,A,\r\n", csv)
        // Days with no time are left out.
        val none = RefoldExport.build(listOf(day(1, "2026-11-01", 0)), mapOf(1L to "A"), null, today)
        assertEquals(header, none.csv)
        assertEquals(0, none.rows)
        assertNull(none.lastDay)
    }

    @Test
    fun titlesWithCommasAndQuotesAreQuoted_lineBreaksBecomeSpaces_andFormulaCharsStay() {
        val titles = mapOf(
            1L to "Hello, \"World\"",
            2L to "=Formula",
            3L to "Two\nLines",
            4L to "  spaced   out ",
        )
        val csv = RefoldExport.build(
            days = (1L..4L).map { day(it, "2026-11-01", 60) },
            titles = titles,
            since = null,
            today = today,
        ).csv
        assertEquals(
            header +
                "20101,ja,2026-11-01,60000,,=Formula,\r\n" +
                "20101,ja,2026-11-01,60000,,\"Hello, \"\"World\"\"\",\r\n" +
                "20101,ja,2026-11-01,60000,,Two Lines,\r\n" +
                "20101,ja,2026-11-01,60000,,spaced out,\r\n",
            csv,
        )
    }

    @Test
    fun aRemovedOrUntitledBookGetsTheFallbackNickname() {
        val csv = RefoldExport.build(
            listOf(day(1, "2026-11-01", 60), day(2, "2026-11-01", 60)),
            mapOf(2L to "   "),
            null,
            today,
        ).csv
        assertEquals(
            header + "20101,ja,2026-11-01,60000,,Honmani,\r\n" + "20101,ja,2026-11-01,60000,,Honmani,\r\n",
            csv,
        )
    }

    @Test
    fun sinceTheLastExportTakesOnlyLaterFinishedDays() {
        val days = listOf(
            day(1, "2026-10-30", 60),
            day(1, "2026-10-31", 120),
            day(1, "2026-11-01", 180),
            day(1, "2026-11-02", 240), // today, still being read
        )
        val titles = mapOf(1L to "A")
        val since = RefoldExport.build(days, titles, LocalDate.of(2026, 10, 30), today)
        assertEquals(header + "20101,ja,2026-10-31,120000,,A,\r\n20101,ja,2026-11-01,180000,,A,\r\n", since.csv)
        assertEquals(LocalDate.of(2026, 11, 1), since.lastDay)
        // Exporting again from that point finds nothing new until tomorrow.
        assertEquals(0, RefoldExport.build(days, titles, since.lastDay, today).rows)
        assertEquals(1, RefoldExport.build(days, titles, since.lastDay, today.plusDays(1)).rows)
        // "Everything" leaves out the unfinished day unless asked to include it.
        assertEquals(3, RefoldExport.build(days, titles, null, today).rows)
        assertEquals(4, RefoldExport.build(days, titles, null, today, includeToday = true).rows)
    }

    @Test
    fun todayFollowsTheTimeZone() {
        // 2026-11-01 23:30 UTC is already the 2nd in Auckland and still the 1st in Los Angeles.
        val now = Instant.parse("2026-11-01T23:30:00Z")
        assertEquals(LocalDate.of(2026, 11, 2), RefoldExport.today(now, ZoneId.of("Pacific/Auckland")))
        assertEquals(LocalDate.of(2026, 11, 1), RefoldExport.today(now, ZoneId.of("America/Los_Angeles")))
        // The stored day is the local date it was read on and is not shifted again.
        val rows = listOf(day(1, "2026-11-01", 60))
        val auckland = RefoldExport.build(rows, mapOf(1L to "A"), null, RefoldExport.today(now, ZoneId.of("Pacific/Auckland")))
        val losAngeles = RefoldExport.build(rows, mapOf(1L to "A"), null, RefoldExport.today(now, ZoneId.of("America/Los_Angeles")))
        assertEquals(1, auckland.rows)
        assertEquals(0, losAngeles.rows)
    }

    @Test
    fun rowsAreSortedByDateThenTitle_andBadDaysAreSkipped() {
        val csv = RefoldExport.build(
            listOf(day(1, "2026-11-01", 60), day(2, "2026-11-01", 60), day(2, "2026-10-01", 60), day(1, "garbage", 60)),
            mapOf(1L to "Zebra", 2L to "Apple"),
            null,
            today,
        ).csv
        assertEquals(
            header + "20101,ja,2026-10-01,60000,,Apple,\r\n" + "20101,ja,2026-11-01,60000,,Apple,\r\n" +
                "20101,ja,2026-11-01,60000,,Zebra,\r\n",
            csv,
        )
    }
}
