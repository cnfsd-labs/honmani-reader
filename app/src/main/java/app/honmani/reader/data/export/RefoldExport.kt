package app.honmani.reader.data.export

import app.honmani.reader.data.stats.ReadingDayEntity
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

    /** What an export holds: the file's [csv], its row count and the latest day it covers. */
    data class Result(val csv: String, val rows: Int, val lastDay: LocalDate?)

    /**
     * The rows of [days] (the stored reading, whose `day` is already the local date it was read
     * on, so no time zone is applied again) after [since] (null: all) and before [today]
     * unless [includeToday]; today is still being read. Book titles come from [titles] by book
     * id. Sorted by date, then title; a row has the whole seconds of the book that day as
     * integer milliseconds; days without time are left out. The start time and the saved-at
     * time are left empty (Refold marks them optional; Honmani does not keep them).
     */
    fun build(
        days: List<ReadingDayEntity>,
        titles: Map<Long, String>,
        since: LocalDate?,
        today: LocalDate,
        includeToday: Boolean = false,
    ): Result {
        val chosen = days.mapNotNull { row ->
            val day = runCatching { LocalDate.parse(row.day) }.getOrNull() ?: return@mapNotNull null
            val wanted = row.seconds > 0 &&
                (since == null || day.isAfter(since)) &&
                (includeToday || day.isBefore(today))
            if (wanted) Triple(day, nickname(titles[row.bookId]), row.seconds) else null
        }.sortedWith(compareBy({ it.first }, { it.second }))
        val rows = chosen.map { (day, nickname, seconds) ->
            listOf(ACTIVITY_INTERACTIVE_READING, LANGUAGE, day.toString(), (seconds * 1000).toString(), "", nickname, "")
        }
        // The title is written as it is: not the spreadsheet protection of [Csv.write].
        val csv = buildString {
            for (row in listOf(HEADER) + rows) {
                row.joinTo(this, ",") { Csv.quote(it) }
                append("\r\n")
            }
        }
        return Result(csv, rows.size, chosen.maxOfOrNull { it.first })
    }

    /** The reader's today in [zone] at [now]: the day that is not finished yet. */
    fun today(now: Instant, zone: ZoneId): LocalDate = now.atZone(zone).toLocalDate()

    private fun nickname(title: String?): String = title?.trim()?.replace(Regex("\\s+"), " ").orEmpty().ifEmpty { FALLBACK_NICKNAME }
}
