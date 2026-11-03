package app.honmani.reader.data.time

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Which day a moment belongs to: the one place that turns time into a reading or review day.
 * A day runs from [startHour] o'clock local time to the same hour the next day (Settings → "Day
 * starts at"), so at 03:00 with a start of 4 it is still yesterday. The start hour 0 is plain
 * midnight. Days are local calendar days of the zone, 23 or 25 hours long when the clock changes.
 *
 * The instance follows the device's current zone and the setting through its providers; the
 * functions of the companion take both explicitly (for pure rules and tests).
 */
class DayClock(
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val startHour: () -> Int = { 0 },
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** The day [time] belongs to. */
    fun date(time: Long): LocalDate = date(time, zone(), startHour())

    /** The day it is now. */
    fun today(): LocalDate = date(now())

    /** [date] as an epoch day. */
    fun localDay(time: Long): Long = date(time).toEpochDay()

    /** When the day of [time], or the one [plusDays] after it, starts. */
    fun startOfDay(time: Long, plusDays: Int = 0): Long = startOfDay(time, zone(), startHour(), plusDays)

    /** When the day [date] starts. */
    fun start(date: LocalDate): Long = start(date, zone(), startHour())

    companion object {
        const val MAX_START_HOUR = 6

        fun date(time: Long, zone: ZoneId, startHour: Int): LocalDate =
            // Local clock arithmetic, not elapsed time: right across a change of the clock.
            Instant.ofEpochMilli(time).atZone(zone).toLocalDateTime().minusHours(startHour.toLong()).toLocalDate()

        fun start(date: LocalDate, zone: ZoneId, startHour: Int): Long =
            date.atTime(startHour, 0).atZone(zone).toInstant().toEpochMilli()

        fun startOfDay(time: Long, zone: ZoneId, startHour: Int, plusDays: Int = 0): Long =
            start(date(time, zone, startHour).plusDays(plusDays.toLong()), zone, startHour)
    }
}
