package app.honmani.reader.data.stats

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import app.honmani.reader.data.library.BookEntity
import kotlinx.coroutines.flow.Flow

/**
 * A finished book that was removed from the library, kept so the "books finished" counts and
 * milestones don't drop. Keyed by the book's content hash: adding the same book again and
 * finishing it again counts once (see [FinishedBooks.merge]).
 */
@Entity(tableName = "finished_book")
data class FinishedBookEntity(
    @PrimaryKey @ColumnInfo(name = "content_hash") val contentHash: String,
    val title: String,
    /** When the reader marked it finished (epoch milliseconds); null where it only reached the end. */
    @ColumnInfo(name = "finished_at") val finishedAt: Long? = null,
)

@Dao
interface FinishedBookDao {
    @Query("SELECT * FROM finished_book")
    fun observeAll(): Flow<List<FinishedBookEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfMissing(book: FinishedBookEntity)

    @Query("UPDATE finished_book SET finished_at = :finishedAt WHERE content_hash = :contentHash AND finished_at IS NULL")
    suspend fun setDateIfUnknown(contentHash: String, finishedAt: Long)

    /** Keeps the first record of a book; a date fills in where the first one had none. */
    @Transaction
    suspend fun record(book: FinishedBookEntity) {
        insertIfMissing(book)
        book.finishedAt?.let { setDateIfUnknown(book.contentHash, it) }
    }

    @Query("DELETE FROM finished_book WHERE content_hash = :contentHash")
    suspend fun forget(contentHash: String)
}

/** A finished book, in the library or only recorded: its content hash and the day it was finished, if known. */
data class FinishedBook(val contentHash: String, val finishedAt: Long?)

object FinishedBooks {
    /** Marked finished, or read to the end. */
    fun isFinished(book: BookEntity) = book.finishedAt != null || book.progress >= 1f

    /**
     * The finished books of the library plus the recorded ones of books removed since, each book
     * once (by content hash; the earlier date wins when both know one).
     */
    fun merge(library: List<BookEntity>, recorded: List<FinishedBookEntity>): List<FinishedBook> {
        val byHash = LinkedHashMap<String, Long?>()
        fun add(hash: String, finishedAt: Long?) {
            if (hash in byHash) {
                val known = byHash.getValue(hash)
                byHash[hash] = if (known != null && finishedAt != null) minOf(known, finishedAt) else known ?: finishedAt
            } else {
                byHash[hash] = finishedAt
            }
        }
        for (book in library) if (isFinished(book)) add(book.contentHash, book.finishedAt)
        for (book in recorded) add(book.contentHash, book.finishedAt)
        return byHash.map { (hash, at) -> FinishedBook(hash, at) }
    }
}
