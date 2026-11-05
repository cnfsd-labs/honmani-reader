package app.honmani.reader.data.merge

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Query

/**
 * That a row was deleted here, at [deletedAt], so a merge with another phone's copy removes it there
 * too instead of bringing it back. [kind] is one of the [SyncKeys] kinds, [key] its natural key. Written
 * in the same transaction as the delete; a local insert of the same key removes the marker.
 */
@Entity(tableName = "tombstone", primaryKeys = ["kind", "key"])
data class TombstoneEntity(
    val kind: String,
    val key: String,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long,
)

/** The marker writes a DAO needs inside its own delete and insert transactions. */
interface TombstoneWriter {
    @Query("INSERT OR REPLACE INTO tombstone (kind, `key`, deleted_at) VALUES (:kind, :key, :deletedAt)")
    suspend fun putTombstone(kind: String, key: String, deletedAt: Long = UpdateClock.now())

    @Query("DELETE FROM tombstone WHERE kind = :kind AND `key` = :key")
    suspend fun clearTombstone(kind: String, key: String)
}

@Dao
interface TombstoneDao : TombstoneWriter {
    @Query("SELECT * FROM tombstone ORDER BY kind, `key`")
    suspend fun all(): List<TombstoneEntity>

    /** Markers older than [before] have reached every phone by now. */
    @Query("DELETE FROM tombstone WHERE deleted_at < :before")
    suspend fun deleteOlderThan(before: Long)
}
