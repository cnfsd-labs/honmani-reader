package app.honmani.reader.data.time

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class DayClockTest {
    private val zone = ZoneId.of("Europe/Berlin")
    private var startHour = 0

    private fun at(time: LocalDateTime) = time.atZone(zone).toInstant().toEpochMilli()

    private fun clock(now: LocalDateTime = LocalDateTime.of(2026, 5, 2, 12, 0)) =
        DayClock(zone = { zone }, startHour = { startHour }, now = { at(now) })

    @Test
    fun midnightIsTheDefaultAndKeepsTheCalendarDay() {
        assertEquals(LocalDate.of(2026, 5, 2), clock(LocalDateTime.of(2026, 5, 2, 0, 0)).today())
        assertEquals(LocalDate.of(2026, 5, 1), clock().date(at(LocalDateTime.of(2026, 5, 1, 23, 59))))
        assertEquals(at(LocalDateTime.of(2026, 5, 3, 0, 0)), clock().startOfDay(at(LocalDateTime.of(2026, 5, 2, 9, 0)), 1))
    }

    @Test
    fun before_theStartHour_itIsStillYesterday() {
        startHour = 4
        assertEquals(LocalDate.of(2026, 5, 1), clock(LocalDateTime.of(2026, 5, 2, 3, 59)).today())
        assertEquals(LocalDate.of(2026, 5, 2), clock(LocalDateTime.of(2026, 5, 2, 4, 0)).today())
        assertEquals(LocalDate.of(2026, 5, 2), clock(LocalDateTime.of(2026, 5, 3, 3, 59)).today())
    }

    @Test
    fun aDayRunsFromTheStartHourToTheSameHourNextDay() {
        startHour = 4
        val time = at(LocalDateTime.of(2026, 5, 2, 3, 0))
        assertEquals(at(LocalDateTime.of(2026, 5, 1, 4, 0)), clock().startOfDay(time))
        assertEquals(at(LocalDateTime.of(2026, 5, 2, 4, 0)), clock().startOfDay(time, 1))
        assertEquals(at(LocalDateTime.of(2026, 5, 4, 4, 0)), clock().startOfDay(time, 3))
        assertEquals(at(LocalDateTime.of(2026, 5, 2, 4, 0)), clock().start(LocalDate.of(2026, 5, 2)))
        assertEquals(LocalDate.of(2026, 5, 1).toEpochDay(), clock().localDay(time))
    }

    @Test
    fun theClockChangingDoesNotMoveTheStartHour() {
        startHour = 4
        // Berlin springs forward on 2026-03-29 at 02:00: 04:30 that morning is already the new day.
        assertEquals(LocalDate.of(2026, 3, 29), clock().date(at(LocalDateTime.of(2026, 3, 29, 4, 30))))
        assertEquals(LocalDate.of(2026, 3, 28), clock().date(at(LocalDateTime.of(2026, 3, 29, 3, 30))))
        assertEquals(at(LocalDateTime.of(2026, 3, 30, 4, 0)), clock().startOfDay(at(LocalDateTime.of(2026, 3, 29, 12, 0)), 1))
    }
}
