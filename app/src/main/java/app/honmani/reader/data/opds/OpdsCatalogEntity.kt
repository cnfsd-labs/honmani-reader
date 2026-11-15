package app.honmani.reader.data.opds

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** A book catalogue (OPDS feed) the user added, such as their Calibre-Web server. */
@Entity(tableName = "opds_catalog")
data class OpdsCatalogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    /** The catalogue's start feed. */
    val url: String,
    val username: String? = null,
    /** The password, encrypted with a key in the Android Keystore (see [CredentialCipher]). */
    @ColumnInfo(name = "password_cipher") val passwordCipher: String? = null,
    @ColumnInfo(name = "added_at") val addedAt: Long,
    /** Download new Japanese books from this catalogue when the app starts. */
    @ColumnInfo(name = "auto_import") val autoImport: Boolean = true,
    /**
     * Whether a first automatic import ran (it reads only the newest page). Later ones stop at the
     * first page of the newest books that brings nothing new.
     */
    @ColumnInfo(name = "auto_import_complete") val autoImportComplete: Boolean = false,
    /**
     * Whether older books need no offer: the reader's "Import older books" went through the
     * catalogue (up to [OpdsAutoImport.MAX_PAGES] pages), or the first import reached its end.
     */
    @ColumnInfo(name = "older_books_imported") val olderBooksImported: Boolean = false,
)

@Dao
interface OpdsCatalogDao {
    @Query("SELECT * FROM opds_catalog ORDER BY added_at, id")
    fun observeAll(): Flow<List<OpdsCatalogEntity>>

    @Query("SELECT * FROM opds_catalog WHERE id = :id")
    suspend fun find(id: Long): OpdsCatalogEntity?

    @Insert
    suspend fun insert(catalog: OpdsCatalogEntity): Long

    @Update
    suspend fun update(catalog: OpdsCatalogEntity)

    @Query("DELETE FROM opds_catalog WHERE id = :id")
    suspend fun delete(id: Long)
}
