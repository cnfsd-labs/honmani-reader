package app.honmani.reader.data.library

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
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
) {
    /** Where in the book the place is, 0 to 100. */
    val percent: Int get() = if (total <= 0) 0 else (characters * 100 / total).toInt().coerceIn(0, 100)
}

@Dao
interface BookmarkDao {
    /** The book's bookmarks in reading order. */
    @Query("SELECT * FROM bookmark WHERE content_hash = :contentHash ORDER BY characters, id")
    fun observe(contentHash: String): Flow<List<BookmarkEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(bookmark: BookmarkEntity): Long

    @Query("UPDATE bookmark SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    @Query("DELETE FROM bookmark WHERE id = :id")
    suspend fun delete(id: Long)

    /** With the book. */
    @Query("DELETE FROM bookmark WHERE content_hash = :contentHash")
    suspend fun deleteForBook(contentHash: String)
}
