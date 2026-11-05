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
)

@Dao
interface PronunciationDao {
    /** The book's replacements, oldest first. */
    @Query("SELECT * FROM pronunciation WHERE content_hash = :contentHash ORDER BY created_at, id")
    fun observe(contentHash: String): Flow<List<PronunciationEntity>>

    /** A new reading for a text replaces the old one. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(pronunciation: PronunciationEntity): Long

    @Query("DELETE FROM pronunciation WHERE id = :id")
    suspend fun delete(id: Long)

    /** With the book. */
    @Query("DELETE FROM pronunciation WHERE content_hash = :contentHash")
    suspend fun deleteForBook(contentHash: String)
}
