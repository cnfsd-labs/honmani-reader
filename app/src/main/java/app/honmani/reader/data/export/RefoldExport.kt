package app.honmani.reader.data.export

import app.honmani.reader.data.stats.ReadingDayEntity
import app.honmani.reader.data.time.DayClock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Reading time as a CSV file for the Refold app's import (Profile, Import/Export): one row per
 * day and book, as "Interactive Reading", and a second one for the time with audio playing
 * ("Interactive Reading w/ Audio"). Only time is tracked there, no words or characters.
 */
object RefoldExport {
    /** Refold's activity id for "Interactive Reading" (reading with lookups). */
    const val ACTIVITY_INTERACTIVE_READING = "20101"

    /**
     * Refold's activity id for "Interactive Reading w/ Audio" (reading and listening simultaneously with
     * lookups), from Refold's own import sheet (2026-11-07).
     */
    const val ACTIVITY_INTERACTIVE_READING_WITH_AUDIO = "10101"

    const val LANGUAGE = "ja"

    /** The nickname of reading in a book that has been removed or has no title. */
    const val FALLBACK_NICKNAME = "Honmani"

    val HEADER = listOf("activityId", "languageCode", "attributedDate", "duration", "startTime", "nickname", "savedAt")

    /**
     * What a file was built from for one book on one day: [seconds] read without audio and [listenSeconds]
     * with audio; they are marked as exported once it is shared.
     */
    data class Mark(val bookId: Long, val day: String, val seconds: Long, val deviceId: String = "", val listenSeconds: Long = 0)

    /** What an export holds: the file's [csv], its row count and the [marks] to store once it is offered. */
    data class Result(val csv: String, val rows: Int, val marks: List<Mark>)

    /**
     * The rows of [days] (the stored reading, whose `day` is already the local date it was read
     * on, so no time zone is applied again) that Refold has not seen: for each day and book the
     * listening seconds beyond `refoldListenSeconds` (activity 10101) and the seconds read without audio
     * (`seconds - listenSeconds`) beyond `refoldSeconds` (activity 20101), today's so far included.
     * Refold adds every imported row up, so a later export holds only the remainder. With [again] each
     * day and book comes in full. Book titles come from [titles] by book id. Sorted by date, then
     * title; a row has the seconds as integer milliseconds; days without time are left out. The start
     * time and the saved-at time are left empty (Refold marks them optional; Honmani does not keep them).
     *
     * Before listening was tracked, `refoldSeconds` was the whole day's seconds and went out as 20101: those
     * seconds stay 20101 (reading-only), and since such a day has no listening time it doubles nothing; only
     * listening added later goes out as 10101 and is taken off the reading-only seconds.
     */
    fun build(
        days: List<ReadingDayEntity>,
        titles: Map<Long, String>,
        again: Boolean = false,
    ): Result {
        // Only this phone's reading: Refold adds every imported row up, and another phone exports its own.
        val chosen = days.filter { it.deviceId.isEmpty() }.flatMap { row ->
            val day = runCatching { LocalDate.parse(row.day) }.getOrNull() ?: return@flatMap emptyList()
            val nickname = nickname(titles[row.bookId])
            val listen = row.listenSeconds.coerceIn(0L, row.seconds.coerceAtLeast(0L))
            val reading = row.seconds - listen
            val newReading = if (again) reading else reading - row.refoldSeconds
            val newListen = if (again) listen else listen - row.refoldListenSeconds
            listOfNotNull(
                if (newReading > 0) Row(row, day, nickname, newReading, ACTIVITY_INTERACTIVE_READING) else null,
                if (newListen > 0) Row(row, day, nickname, newListen, ACTIVITY_INTERACTIVE_READING_WITH_AUDIO) else null,
            )
        }.sortedWith(compareBy({ it.date }, { it.nickname }))
        val rows = chosen.map {
            listOf(it.activity, LANGUAGE, it.date.toString(), (it.seconds * 1000).toString(), "", it.nickname, "")
        }
        // The title is written as it is: not the spreadsheet protection of [Csv.write].
        val csv = buildString {
            for (row in listOf(HEADER) + rows) {
                row.joinTo(this, ",") { Csv.quote(it) }
                append("\r\n")
            }
        }
        // Both counts are marked together, also when only one of them had something new.
        val marks = chosen.map { it.entity }.distinct().map {
            val listen = it.listenSeconds.coerceIn(0L, it.seconds.coerceAtLeast(0L))
            Mark(it.bookId, it.day, it.seconds - listen, it.deviceId, listen)
        }
        return Result(csv, rows.size, marks)
    }

    private class Row(val entity: ReadingDayEntity, val date: LocalDate, val nickname: String, val seconds: Long, val activity: String)

    /** The reader's today in [zone] at [now] (days start at [startHour], see [DayClock]): the day that is not finished yet. */
    fun today(now: Instant, zone: ZoneId, startHour: Int = 0): LocalDate = DayClock.date(now.toEpochMilli(), zone, startHour)

    private fun nickname(title: String?): String = title?.trim()?.replace(Regex("\\s+"), " ").orEmpty().ifEmpty { FALLBACK_NICKNAME }
}
