package app.honmani.reader.data.stats

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import app.honmani.reader.data.export.RefoldExport
import kotlinx.coroutines.flow.Flow

@Dao
interface ReadingDao {
    /** Creates this phone's row (device `''`) for a book and day if it doesn't exist yet. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfMissing(day: ReadingDayEntity)

    @Query(
        "UPDATE reading_day SET seconds = seconds + :seconds, characters = characters + :characters " +
            "WHERE book_id = :bookId AND day = :day AND device_id = ''",
    )
    suspend fun add(bookId: Long, day: String, seconds: Long, characters: Long)

    /** Remembers that [seconds] of a book, day and phone went into a Refold export; never lowers the mark. */
    @Query(
        "UPDATE reading_day SET refold_seconds = MAX(refold_seconds, :seconds) " +
            "WHERE book_id = :bookId AND day = :day AND device_id = :deviceId",
    )
    suspend fun markRefoldExported(bookId: Long, day: String, seconds: Long, deviceId: String = "")

    /** Marks everything a Refold file held, together or not at all. */
    @Transaction
    suspend fun markRefoldExported(marks: List<RefoldExport.Mark>) {
        for (mark in marks) markRefoldExported(mark.bookId, mark.day, mark.seconds, mark.deviceId)
    }

    /** Forgets all reading of a book (removed from the library with its statistics). */
    @Query("DELETE FROM reading_day WHERE book_id = :bookId")
    suspend fun deleteBook(bookId: Long)

    /** The rows of every phone; add them up per book and day with [perBookAndDay]. */
    @Query("SELECT * FROM reading_day ORDER BY day")
    fun observeAll(): Flow<List<ReadingDayEntity>>

    @Query("SELECT * FROM reading_day WHERE book_id = :bookId ORDER BY day")
    fun observeBook(bookId: Long): Flow<List<ReadingDayEntity>>
}
