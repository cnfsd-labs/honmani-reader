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

/** The longest text to replace, and the longest reading it is replaced with. */
const val PRONUNCIATION_TEXT_MAX = 50
const val PRONUNCIATION_READING_MAX = 100

/**
 * "Read this text as that" for Read aloud in one book: a name the phone voice misreads gets its
 * kana reading. Keyed by the book's content hash like bookmarks, so a backup brings it back once
 * the same file is in the library; it goes when the book is removed. One reading per text.
 */
@Entity(tableName = "pronunciation", indices = [Index(value = ["content_hash", "text"], unique = true)])
data class PronunciationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "content_hash") val contentHash: String,
    /** What the book says (a name in kanji, say). */
    val text: String,
    /** What the voice is given instead, in kana. */
    val reading: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    /** When the reading last changed here (epoch ms; 0 = never stamped); the merge keeps the later edit. */
    @ColumnInfo(name = "updated_at", defaultValue = "0") val updatedAt: Long = 0,
)

@Dao
interface PronunciationDao : TombstoneWriter {
    /** The book's replacements, oldest first. */
    @Query("SELECT * FROM pronunciation WHERE content_hash = :contentHash ORDER BY created_at, id")
    fun observe(contentHash: String): Flow<List<PronunciationEntity>>

    /** A new reading for a text replaces the old one. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putRow(pronunciation: PronunciationEntity): Long

    /** Stamped now; removes the marker of a deleted reading of the same text. */
    @Transaction
    suspend fun put(pronunciation: PronunciationEntity): Long {
        clearTombstone(SyncKeys.PRONUNCIATION, SyncKeys.pronunciation(pronunciation.contentHash, pronunciation.text))
        return putRow(pronunciation.copy(updatedAt = UpdateClock.now()))
    }

    @Query("SELECT * FROM pronunciation WHERE id = :id")
    suspend fun byId(id: Long): PronunciationEntity?

    @Query("SELECT * FROM pronunciation WHERE content_hash = :contentHash")
    suspend fun forBook(contentHash: String): List<PronunciationEntity>

    @Query("DELETE FROM pronunciation WHERE id = :id")
    suspend fun deleteRow(id: Long)

    /** Deletes the reading and leaves a marker, in one transaction. */
    @Transaction
    suspend fun delete(id: Long) {
        byId(id)?.let { putTombstone(SyncKeys.PRONUNCIATION, SyncKeys.pronunciation(it.contentHash, it.text)) }
        deleteRow(id)
    }

    /** With the book, and without a marker: each phone keeps its own library, so the other phones keep theirs. */
    @Query("DELETE FROM pronunciation WHERE content_hash = :contentHash")
    suspend fun deleteForBook(contentHash: String)
}
