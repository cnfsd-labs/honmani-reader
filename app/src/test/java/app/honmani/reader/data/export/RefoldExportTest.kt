package app.honmani.reader.data.export

import app.honmani.reader.data.stats.ReadingDayEntity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class RefoldExportTest {
    private val header = "activityId,languageCode,attributedDate,duration,startTime,nickname,savedAt\r\n"
    private val today = LocalDate.of(2026, 11, 2)

    private fun day(book: Long, day: String, seconds: Long, exported: Long = 0) =
        ReadingDayEntity(book, day, seconds, characters = 100, refoldSeconds = exported)

    @Test
    fun theHeaderAndRowsAreExactlyRefoldsImportFormat() {
        val result = RefoldExport.build(
            days = listOf(day(1, "2026-11-01", 90), day(2, "2026-10-31", 3600)),
            titles = mapOf(1L to "注文の多い料理店", 2L to "Kokoro"),
        )
        assertEquals(
            header +
                "20101,ja,2026-10-31,3600000,,Kokoro,\r\n" +
                "20101,ja,2026-11-01,90000,,注文の多い料理店,\r\n",
            result.csv,
        )
        assertEquals(2, result.rows)
    }

    @Test
    fun durationIsWholeSecondsAsIntegerMilliseconds() {
        val csv = RefoldExport.build(listOf(day(1, "2026-11-01", 1)), mapOf(1L to "A")).csv
        assertEquals(header + "20101,ja,2026-11-01,1000,,A,\r\n", csv)
        // Days with no time are left out.
        val none = RefoldExport.build(listOf(day(1, "2026-11-01", 0)), mapOf(1L to "A"))
        assertEquals(header, none.csv)
        assertEquals(0, none.rows)
        assertEquals(emptyList<RefoldExport.Mark>(), none.marks)
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
        ).csv
        assertEquals(
            header + "20101,ja,2026-11-01,60000,,Honmani,\r\n" + "20101,ja,2026-11-01,60000,,Honmani,\r\n",
            csv,
        )
    }

    @Test
    fun todayIsIncluded_andOnlyTheRemainderOfAnExportedDayComesLater() {
        val titles = mapOf(1L to "A")
        // Today, 100 s read so far: the whole of it goes out.
        val first = RefoldExport.build(listOf(day(1, "2026-11-02", 100)), titles)
        assertEquals(header + "20101,ja,2026-11-02,100000,,A,\r\n", first.csv)
        assertEquals(listOf(RefoldExport.Mark(1, "2026-11-02", 100)), first.marks)
        // After marking, nothing is new.
        assertEquals(0, RefoldExport.build(listOf(day(1, "2026-11-02", 100, exported = 100)), titles).rows)
        // 70 s more were read: a second row holds just those, and marks the new total.
        val second = RefoldExport.build(listOf(day(1, "2026-11-02", 170, exported = 100)), titles)
        assertEquals(header + "20101,ja,2026-11-02,70000,,A,\r\n", second.csv)
        assertEquals(listOf(RefoldExport.Mark(1, "2026-11-02", 170)), second.marks)
    }

    @Test
    fun nothingNewGivesNoRows() {
        val result = RefoldExport.build(
            listOf(day(1, "2026-10-31", 60, exported = 60), day(2, "2026-11-02", 30, exported = 30), day(3, "2026-11-01", 0)),
            mapOf(1L to "A", 2L to "B", 3L to "C"),
        )
        assertEquals(header, result.csv)
        assertEquals(0, result.rows)
        assertEquals(emptyList<RefoldExport.Mark>(), result.marks)
    }

    @Test
    fun anOlderDayThatGrewAfterItsExportIsSentAsTheDifference() {
        val days = listOf(
            day(1, "2026-10-30", 90, exported = 60), // grew by 30 s
            day(1, "2026-10-31", 120, exported = 120),
            day(1, "2026-11-01", 180),
        )
        val result = RefoldExport.build(days, mapOf(1L to "A"))
        assertEquals(header + "20101,ja,2026-10-30,30000,,A,\r\n20101,ja,2026-11-01,180000,,A,\r\n", result.csv)
        assertEquals(listOf(RefoldExport.Mark(1, "2026-10-30", 90), RefoldExport.Mark(1, "2026-11-01", 180)), result.marks)
    }

    @Test
    fun againSendsTheFullTotalsOfEveryDayAndBook() {
        val days = listOf(day(1, "2026-11-01", 180, exported = 180), day(1, "2026-11-02", 170, exported = 100), day(2, "2026-11-02", 0))
        val result = RefoldExport.build(days, mapOf(1L to "A", 2L to "B"), again = true)
        assertEquals(header + "20101,ja,2026-11-01,180000,,A,\r\n20101,ja,2026-11-02,170000,,A,\r\n", result.csv)
        assertEquals(listOf(RefoldExport.Mark(1, "2026-11-01", 180), RefoldExport.Mark(1, "2026-11-02", 170)), result.marks)
    }

    @Test
    fun todayFollowsTheTimeZone() {
        // 2026-11-01 23:30 UTC is already the 2nd in Auckland and still the 1st in Los Angeles.
        val now = Instant.parse("2026-11-01T23:30:00Z")
        assertEquals(LocalDate.of(2026, 11, 2), RefoldExport.today(now, ZoneId.of("Pacific/Auckland")))
        assertEquals(LocalDate.of(2026, 11, 1), RefoldExport.today(now, ZoneId.of("America/Los_Angeles")))
    }

    @Test
    fun rowsAreSortedByDateThenTitle_andBadDaysAreSkipped() {
        val csv = RefoldExport.build(
            listOf(day(1, "2026-11-01", 60), day(2, "2026-11-01", 60), day(2, "2026-10-01", 60), day(1, "garbage", 60)),
            mapOf(1L to "Zebra", 2L to "Apple"),
        ).csv
        assertEquals(
            header + "20101,ja,2026-10-01,60000,,Apple,\r\n" + "20101,ja,2026-11-01,60000,,Apple,\r\n" +
                "20101,ja,2026-11-01,60000,,Zebra,\r\n",
            csv,
        )
    }
}
