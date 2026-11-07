package app.honmani.reader.data.stats

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

/**
 * Reading in one book on one day on one phone: time with the book on screen (not idle) and characters read
 * (see [ReadingTracker]). No foreign key to the book: totals keep a removed book's reading.
 */
@Entity(
    tableName = "reading_day",
    primaryKeys = ["book_id", "day", "device_id"],
    indices = [Index(value = ["day"])],
)
data class ReadingDayEntity(
    @ColumnInfo(name = "book_id") val bookId: Long,
    /** The local date, `yyyy-MM-dd`. */
    val day: String,
    val seconds: Long = 0,
    val characters: Long = 0,
    /**
     * Seconds of [seconds] with audio playing (an audiobook or Read aloud): a subset of the total, never
     * counted twice ([ReadingTracker]).
     */
    @ColumnInfo(name = "listen_seconds", defaultValue = "0") val listenSeconds: Long = 0,
    /**
     * Seconds of this day and book the Refold export has already handed out as reading without audio
     * ([RefoldExport]). Before listening was tracked it was the whole day's seconds.
     */
    @ColumnInfo(name = "refold_seconds", defaultValue = "0") val refoldSeconds: Long = 0,
    /** How much of [listenSeconds] the Refold export has handed out (as reading with audio). */
    @ColumnInfo(name = "refold_listen_seconds", defaultValue = "0") val refoldListenSeconds: Long = 0,
    /**
     * Which phone read: `''` is this one; the sync file of another phone carries its installation id.
     * One row per book, day and phone, so two phones' minutes add up instead of one replacing the
     * other. Everything that shows or sums reading adds the rows of a day and book ([ReadingStats]).
     */
    @ColumnInfo(name = "device_id", defaultValue = "''") val deviceId: String = "",
)

/**
 * One row per book and day: the rows of the phones added up (device `''`). What every statistic works
 * from, so that 20 minutes here and 15 on another phone are 35 for that day. The order of first
 * appearance is kept; a list without a repeat comes back as it is.
 */
fun List<ReadingDayEntity>.perBookAndDay(): List<ReadingDayEntity> {
    if (size < 2 || distinctBy { it.bookId to it.day }.size == size) return this
    val sums = LinkedHashMap<Pair<Long, String>, ReadingDayEntity>()
    for (row in this) {
        val key = row.bookId to row.day
        val sum = sums[key]
        sums[key] = if (sum == null) {
            row.copy(deviceId = "")
        } else {
            sum.copy(
                seconds = sum.seconds + row.seconds,
                characters = sum.characters + row.characters,
                listenSeconds = sum.listenSeconds + row.listenSeconds,
                refoldSeconds = sum.refoldSeconds + row.refoldSeconds,
                refoldListenSeconds = sum.refoldListenSeconds + row.refoldListenSeconds,
            )
        }
    }
    return sums.values.toList()
}
