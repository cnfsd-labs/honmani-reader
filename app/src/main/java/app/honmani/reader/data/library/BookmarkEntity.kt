package app.honmani.reader.data.library

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import app.honmani.reader.data.merge.SyncKeys
import app.honmani.reader.data.merge.TombstoneWriter
import app.honmani.reader.data.merge.UpdateClock
import kotlinx.coroutines.flow.Flow

/** The longest bookmark name kept. */
const val BOOKMARK_NAME_MAX = 100

/**
 * A named place the reader saved in a book (not ttu's single reading-position bookmark). Keyed
 * by the book's content hash like the other per-book data, so a backup brings it back once the
 * same file is in the library; it goes when the book is removed.
 */
@Entity(tableName = "bookmark", indices = [Index(value = ["content_hash", "characters"])])
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "content_hash") val contentHash: String,
    val name: String,
    /** ttu's character count before the place: the position the book opens at. */
    val characters: Long,
    /** The characters in the whole book, so the place can be shown as a percentage without opening it. */
    val total: Long,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    /** When the bookmark last changed here (epoch ms; 0 = never stamped); the merge keeps the later edit. */
    @ColumnInfo(name = "updated_at", defaultValue = "0") val updatedAt: Long = 0,
) {
    /** Where in the book the place is, 0 to 100. */
    val percent: Int get() = if (total <= 0) 0 else (characters * 100 / total).toInt().coerceIn(0, 100)
}

@Dao
interface BookmarkDao : TombstoneWriter {
    /** The book's bookmarks in reading order. */
    @Query("SELECT * FROM bookmark WHERE content_hash = :contentHash ORDER BY characters, id")
    fun observe(contentHash: String): Flow<List<BookmarkEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putRow(bookmark: BookmarkEntity): Long

    /** Stamped now; removes the marker of a deleted bookmark at the same place. */
    @Transaction
    suspend fun put(bookmark: BookmarkEntity): Long {
        clearTombstone(SyncKeys.BOOKMARK, SyncKeys.bookmark(bookmark.contentHash, bookmark.characters))
        return putRow(bookmark.copy(updatedAt = UpdateClock.now()))
    }

    @Query("UPDATE bookmark SET name = :name, updated_at = :now WHERE id = :id")
    suspend fun rename(id: Long, name: String, now: Long = UpdateClock.now())

    @Query("SELECT * FROM bookmark WHERE id = :id")
    suspend fun byId(id: Long): BookmarkEntity?

    @Query("SELECT * FROM bookmark WHERE content_hash = :contentHash")
    suspend fun forBook(contentHash: String): List<BookmarkEntity>

    @Query("DELETE FROM bookmark WHERE id = :id")
    suspend fun deleteRow(id: Long)

    /** Deletes the bookmark and leaves a marker, in one transaction. */
    @Transaction
    suspend fun delete(id: Long) {
        byId(id)?.let { putTombstone(SyncKeys.BOOKMARK, SyncKeys.bookmark(it.contentHash, it.characters)) }
        deleteRow(id)
    }

    /** With the book, and without a marker: each phone keeps its own library, so the other phones keep theirs. */
    @Query("DELETE FROM bookmark WHERE content_hash = :contentHash")
    suspend fun deleteForBook(contentHash: String)
}
