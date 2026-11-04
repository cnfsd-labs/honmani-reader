package app.honmani.reader.data.library

import android.database.sqlite.SQLiteConstraintException
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/** The longest folder name kept. */
const val FOLDER_NAME_MAX = 60

/**
 * A folder the reader sorts books into; a book is in at most one ([BookEntity.folderId]). The name
 * is unique ignoring case. Deleting a folder leaves its books in the library, in no folder.
 */
@Entity(tableName = "folder", indices = [Index(value = ["name"], unique = true)])
data class FolderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(collate = ColumnInfo.NOCASE) val name: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

@Dao
interface FolderDao {
    @Query("SELECT * FROM folder ORDER BY name COLLATE NOCASE, id")
    fun observeAll(): Flow<List<FolderEntity>>

    @Query("SELECT * FROM folder ORDER BY id")
    suspend fun all(): List<FolderEntity>

    /** The folder called [name], ignoring case. */
    @Query("SELECT * FROM folder WHERE name = :name")
    suspend fun findByName(name: String): FolderEntity?

    /** Returns the new row id. Throws if a folder with the same name exists. */
    @Insert
    suspend fun insert(folder: FolderEntity): Long

    @Query("UPDATE folder SET name = :name WHERE id = :id")
    suspend fun setName(id: Long, name: String)

    /** The books stay: their folder link goes with the folder (ON DELETE SET NULL). */
    @Query("DELETE FROM folder WHERE id = :id")
    suspend fun delete(id: Long)

    /** Moves the book into the folder; null takes it out of any. */
    @Query("UPDATE book SET folder_id = :folderId WHERE id = :bookId")
    suspend fun setBookFolder(bookId: Long, folderId: Long?)

    @Transaction
    suspend fun createAndMove(bookId: Long, folder: FolderEntity): Long =
        insert(folder).also { setBookFolder(bookId, it) }
}

/** What came of a folder name the reader typed. */
sealed interface FolderResult {
    data class Done(val id: Long) : FolderResult

    data object Blank : FolderResult

    data object Duplicate : FolderResult
}

/** Folder names are trimmed and refused when blank or taken (ignoring case). */
class Folders(private val dao: FolderDao, private val now: () -> Long = System::currentTimeMillis) {
    fun observeAll(): Flow<List<FolderEntity>> = dao.observeAll()

    suspend fun create(name: String): FolderResult = write(name, id = 0, bookId = null)

    /** Creates the folder and puts the book in it. */
    suspend fun createFor(bookId: Long, name: String): FolderResult = write(name, id = 0, bookId = bookId)

    suspend fun rename(id: Long, name: String): FolderResult = write(name, id = id, bookId = null)

    suspend fun delete(id: Long) = dao.delete(id)

    suspend fun move(bookId: Long, folderId: Long?) = dao.setBookFolder(bookId, folderId)

    private suspend fun write(name: String, id: Long, bookId: Long?): FolderResult {
        val clean = name.trim().take(FOLDER_NAME_MAX).trim()
        if (clean.isEmpty()) return FolderResult.Blank
        val same = dao.findByName(clean)
        if (same != null && same.id != id) return FolderResult.Duplicate
        return try {
            when {
                id != 0L -> id.also { dao.setName(it, clean) }
                bookId != null -> dao.createAndMove(bookId, FolderEntity(name = clean, createdAt = now()))
                else -> dao.insert(FolderEntity(name = clean, createdAt = now()))
            }.let { FolderResult.Done(it) }
        } catch (_: SQLiteConstraintException) {
            FolderResult.Duplicate
        }
    }
}
