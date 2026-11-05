package app.honmani.reader.data.export

import app.honmani.reader.data.stats.ReadingDayEntity
import app.honmani.reader.data.time.DayClock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Reading time as a CSV file for the Refold app's import (Profile, Import/Export): one row per
 * day and book, as "Interactive Reading". Only time is tracked there, no words or characters.
 */
object RefoldExport {
    /** Refold's activity id for "Interactive Reading" (reading with lookups). */
    const val ACTIVITY_INTERACTIVE_READING = "20101"

    const val LANGUAGE = "ja"

    /** The nickname of reading in a book that has been removed or has no title. */
    const val FALLBACK_NICKNAME = "Honmani"

    val HEADER = listOf("activityId", "languageCode", "attributedDate", "duration", "startTime", "nickname", "savedAt")

    /** Seconds of one book on one day that a file holds; they are marked as exported once it is shared. */
    data class Mark(val bookId: Long, val day: String, val seconds: Long, val deviceId: String = "")

    /** What an export holds: the file's [csv], its row count and the [marks] to store once it is offered. */
    data class Result(val csv: String, val rows: Int, val marks: List<Mark>)

    /**
     * The rows of [days] (the stored reading, whose `day` is already the local date it was read
     * on, so no time zone is applied again) that Refold has not seen: for each day and book the
     * seconds beyond `refoldSeconds`, today's so far included. Refold adds every imported row up,
     * so a later export holds only the remainder. With [again] each day and book comes in full.
     * Book titles come from [titles] by book id. Sorted by date, then title; a row has the seconds
     * as integer milliseconds; days without time are left out. The start time and the saved-at
     * time are left empty (Refold marks them optional; Honmani does not keep them).
     */
    fun build(
        days: List<ReadingDayEntity>,
        titles: Map<Long, String>,
        again: Boolean = false,
    ): Result {
        // Only this phone's reading: Refold adds every imported row up, and another phone exports its own.
        val chosen = days.filter { it.deviceId.isEmpty() }.mapNotNull { row ->
            val day = runCatching { LocalDate.parse(row.day) }.getOrNull() ?: return@mapNotNull null
            val seconds = if (again) row.seconds else row.seconds - row.refoldSeconds
            if (seconds > 0) Row(row, day, nickname(titles[row.bookId]), seconds) else null
        }.sortedWith(compareBy({ it.date }, { it.nickname }))
        val rows = chosen.map {
            listOf(ACTIVITY_INTERACTIVE_READING, LANGUAGE, it.date.toString(), (it.seconds * 1000).toString(), "", it.nickname, "")
        }
        // The title is written as it is: not the spreadsheet protection of [Csv.write].
        val csv = buildString {
            for (row in listOf(HEADER) + rows) {
                row.joinTo(this, ",") { Csv.quote(it) }
                append("\r\n")
            }
        }
        return Result(csv, rows.size, chosen.map { Mark(it.entity.bookId, it.entity.day, it.entity.seconds, it.entity.deviceId) })
    }

    private class Row(val entity: ReadingDayEntity, val date: LocalDate, val nickname: String, val seconds: Long)

    /** The reader's today in [zone] at [now] (days start at [startHour], see [DayClock]): the day that is not finished yet. */
    fun today(now: Instant, zone: ZoneId, startHour: Int = 0): LocalDate = DayClock.date(now.toEpochMilli(), zone, startHour)

    private fun nickname(title: String?): String = title?.trim()?.replace(Regex("\\s+"), " ").orEmpty().ifEmpty { FALLBACK_NICKNAME }
}
