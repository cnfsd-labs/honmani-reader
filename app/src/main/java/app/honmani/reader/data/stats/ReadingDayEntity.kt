package app.honmani.reader.data.stats

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

/**
 * Reading in one book on one day: time with the book on screen (not idle) and characters read
 * (see [ReadingTracker]). No foreign key to the book: totals keep a removed book's reading.
 */
@Entity(
    tableName = "reading_day",
    primaryKeys = ["book_id", "day"],
    indices = [Index(value = ["day"])],
)
data class ReadingDayEntity(
    @ColumnInfo(name = "book_id") val bookId: Long,
    /** The local date, `yyyy-MM-dd`. */
    val day: String,
    val seconds: Long = 0,
    val characters: Long = 0,
    /** Seconds of this day and book the Refold export has already handed out ([RefoldExport]). */
    @ColumnInfo(name = "refold_seconds", defaultValue = "0") val refoldSeconds: Long = 0,
)
