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

/** The longest passage text kept with a highlight (for the list; the range itself is the position). */
const val HIGHLIGHT_TEXT_MAX = 200

/** How many colours a highlight can have: yellow, green, blue, pink (an index into them). */
const val HIGHLIGHT_COLORS = 4

/**
 * A passage the reader marked in a book. Keyed by the book's content hash like bookmarks, so a
 * backup brings it back once the same file is in the library; it goes when the book is removed.
 * The range is in ttu's character counting (see EpubPositions), so it survives any change to
 * font, size, theme, orientation or custom CSS.
 */
@Entity(tableName = "highlight", indices = [Index(value = ["content_hash", "start"])])
data class HighlightEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "content_hash") val contentHash: String,
    /** ttu's character count before the passage. */
    val start: Long,
    /** ttu's character count after the passage (exclusive). */
    @ColumnInfo(name = "end_position") val end: Long,
    /** 0 yellow, 1 green, 2 blue, 3 pink. */
    val color: Int,
    /** The start of the passage's text, for the list. */
    val text: String,
    /** The characters in the whole book, so the place can be shown as a percentage without opening it. */
    val total: Long,
    @ColumnInfo(name = "created_at") val createdAt: Long,
) {
    /** Where in the book the passage starts, 0 to 100. */
    val percent: Int get() = if (total <= 0) 0 else (start * 100 / total).toInt().coerceIn(0, 100)
}

@Dao
interface HighlightDao {
    /** The book's highlights in reading order. */
    @Query("SELECT * FROM highlight WHERE content_hash = :contentHash ORDER BY start, id")
    fun observe(contentHash: String): Flow<List<HighlightEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(highlight: HighlightEntity): Long

    @Query("UPDATE highlight SET color = :color WHERE id = :id")
    suspend fun setColor(id: Long, color: Int)

    @Query("DELETE FROM highlight WHERE id = :id")
    suspend fun delete(id: Long)

    /** With the book. */
    @Query("DELETE FROM highlight WHERE content_hash = :contentHash")
    suspend fun deleteForBook(contentHash: String)
}
