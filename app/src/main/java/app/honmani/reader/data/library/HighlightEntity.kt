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
    /** When the highlight last changed here (epoch ms; 0 = never stamped); the merge keeps the later edit. */
    @ColumnInfo(name = "updated_at", defaultValue = "0") val updatedAt: Long = 0,
) {
    /** Where in the book the passage starts, 0 to 100. */
    val percent: Int get() = if (total <= 0) 0 else (start * 100 / total).toInt().coerceIn(0, 100)
}

@Dao
interface HighlightDao : TombstoneWriter {
    /** The book's highlights in reading order. */
    @Query("SELECT * FROM highlight WHERE content_hash = :contentHash ORDER BY start, id")
    fun observe(contentHash: String): Flow<List<HighlightEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putRow(highlight: HighlightEntity): Long

    /** Stamped now; removes the marker of a deleted highlight over the same range. */
    @Transaction
    suspend fun put(highlight: HighlightEntity): Long {
        clearTombstone(SyncKeys.HIGHLIGHT, SyncKeys.highlight(highlight.contentHash, highlight.start, highlight.end))
        return putRow(highlight.copy(updatedAt = UpdateClock.now()))
    }

    /** The book's highlights that share at least one character with [start, end). */
    @Query("SELECT * FROM highlight WHERE content_hash = :contentHash AND start < :end AND end_position > :start")
    suspend fun overlapping(contentHash: String, start: Long, end: Long): List<HighlightEntity>

    @Query("UPDATE highlight SET color = :color, updated_at = :now WHERE id = :id")
    suspend fun setColor(id: Long, color: Int, now: Long = UpdateClock.now())

    @Query("SELECT * FROM highlight WHERE id = :id")
    suspend fun byId(id: Long): HighlightEntity?

    @Query("SELECT * FROM highlight WHERE content_hash = :contentHash")
    suspend fun forBook(contentHash: String): List<HighlightEntity>

    @Query("DELETE FROM highlight WHERE id = :id")
    suspend fun deleteRow(id: Long)

    /** Deletes the highlight and leaves a marker, in one transaction. */
    @Transaction
    suspend fun delete(id: Long) {
        byId(id)?.let { putTombstone(SyncKeys.HIGHLIGHT, SyncKeys.highlight(it.contentHash, it.start, it.end)) }
        deleteRow(id)
    }

    /** With the book, and without a marker: each phone keeps its own library, so the other phones keep theirs. */
    @Query("DELETE FROM highlight WHERE content_hash = :contentHash")
    suspend fun deleteForBook(contentHash: String)
}

/** A highlight just saved, with the ones it replaced (so Undo can bring them back). */
data class SavedHighlight(val id: Long, val replaced: List<HighlightEntity>)

/**
 * Saves a new highlight. A highlight that overlaps older ones of the same book replaces them all,
 * whatever their colour: the newest wins, and a reader never has to untangle layered marks.
 */
suspend fun HighlightDao.saveReplacingOverlaps(highlight: HighlightEntity): SavedHighlight {
    val replaced = overlapping(highlight.contentHash, highlight.start, highlight.end)
    for (old in replaced) delete(old.id)
    return SavedHighlight(put(highlight), replaced)
}

/** Takes a saved highlight back and restores what it replaced, with their ids. */
suspend fun HighlightDao.undoSave(saved: SavedHighlight) {
    delete(saved.id)
    for (old in saved.replaced) put(old)
}
