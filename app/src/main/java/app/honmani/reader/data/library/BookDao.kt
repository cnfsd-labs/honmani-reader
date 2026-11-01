package app.honmani.reader.data.library

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {
    /** Most recently opened first, then most recently added. */
    @Query(
        "SELECT * FROM book ORDER BY last_opened_at IS NULL, last_opened_at DESC, added_at DESC, id DESC",
    )
    fun observeAll(): Flow<List<BookEntity>>

    @Query("SELECT * FROM book WHERE id = :id")
    suspend fun find(id: Long): BookEntity?

    @Query("SELECT * FROM book WHERE ttu_id = :ttuId LIMIT 1")
    suspend fun findByTtuId(ttuId: Long): BookEntity?

    @Query("SELECT * FROM book WHERE content_hash = :contentHash")
    suspend fun findByHash(contentHash: String): BookEntity?

    /** The source ids of the books from [source], to mark catalogue entries already downloaded. */
    @Query("SELECT source_id FROM book WHERE source = :source AND source_id IS NOT NULL")
    fun observeSourceIds(source: BookSource): Flow<List<String>>

    /** Returns the new row id. Throws if a book with the same content hash exists. */
    @Insert
    suspend fun insert(book: BookEntity): Long

    @Query("UPDATE book SET ttu_id = :ttuId, ttu_title = :ttuTitle WHERE id = :id")
    suspend fun setTtuBook(id: Long, ttuId: Long, ttuTitle: String)

    /** Clears [ttuId] from every book but [keepId]: ttu now holds a different book under it. */
    @Query("UPDATE book SET ttu_id = NULL, ttu_title = NULL WHERE ttu_id = :ttuId AND id != :keepId")
    suspend fun forgetTtuBookExcept(ttuId: Long, keepId: Long)

    @Query("UPDATE book SET last_opened_at = :openedAt WHERE id = :id")
    suspend fun setLastOpened(id: Long, openedAt: Long)

    /** Only writes if the progress differs, so an unchanged report doesn't make observers re-read the table. */
    @Query("UPDATE book SET progress = :progress WHERE ttu_id = :ttuId AND progress != :progress")
    suspend fun setProgressByTtuId(ttuId: Long, progress: Float)

    /** Sets the progress of every book in [progress] (ttu id to 0..1) in one transaction: observers read once. */
    @Transaction
    suspend fun setProgressByTtuIds(progress: Map<Long, Float>) {
        for ((ttuId, value) in progress) setProgressByTtuId(ttuId, value)
    }

    @Query("UPDATE book SET characters = :characters WHERE ttu_id = :ttuId")
    suspend fun setCharactersByTtuId(ttuId: Long, characters: Long)

    @Query("UPDATE book SET finished_at = :finishedAt WHERE id = :id")
    suspend fun setFinished(id: Long, finishedAt: Long)

    /** Marks the book ttu holds as [ttuId] finished, unless it already is. */
    @Query("UPDATE book SET finished_at = :finishedAt WHERE ttu_id = :ttuId AND finished_at IS NULL")
    suspend fun setFinishedByTtuId(ttuId: Long, finishedAt: Long)

    @Query("UPDATE book SET hidden = :hidden WHERE id = :id")
    suspend fun setHidden(id: Long, hidden: Boolean)

    /** Starting over also takes back "finished". */
    @Query("UPDATE book SET progress = 0, characters = 0, finished_at = NULL WHERE id = :id")
    suspend fun clearProgress(id: Long)

    @Query("SELECT source_id FROM book WHERE source = :source AND source_id IS NOT NULL")
    suspend fun sourceIds(source: BookSource): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRemoved(removed: RemovedBookEntity)

    @Query("SELECT source_id FROM removed_book WHERE source = :source")
    suspend fun removedSourceIds(source: BookSource): List<String>

    @Query("DELETE FROM book WHERE id = :id")
    suspend fun delete(id: Long)
}
