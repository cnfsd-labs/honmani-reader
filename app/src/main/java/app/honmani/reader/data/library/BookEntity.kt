package app.honmani.reader.data.library

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A book in the Honmani library. Honmani keeps the original file; ッツ Ebook Reader (ttu) renders a
 * copy it imports into its own IndexedDB, which [ttuId] points to.
 *
 * [contentHash] (SHA-256 of the file) plus [source] / [sourceId] is the book's stable identity,
 * for duplicate detection now and reading-progress sync later.
 */
@Entity(
    tableName = "book",
    indices = [Index(value = ["content_hash"], unique = true), Index(value = ["folder_id"])],
    foreignKeys = [
        ForeignKey(
            entity = FolderEntity::class,
            parentColumns = ["id"],
            childColumns = ["folder_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
)
data class BookEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "content_hash") val contentHash: String,
    val title: String,
    val author: String? = null,
    val format: BookFormat,
    /** Name of the book file in the library's book directory. */
    @ColumnInfo(name = "file_name") val fileName: String,
    /** Name of the cover image in the library's cover directory, if the book has one. */
    @ColumnInfo(name = "cover_file_name") val coverFileName: String? = null,
    val source: BookSource = BookSource.FILE,
    /** Identifier within [source], such as an OPDS entry id; null for files. */
    @ColumnInfo(name = "source_id") val sourceId: String? = null,
    @ColumnInfo(name = "added_at") val addedAt: Long,
    @ColumnInfo(name = "last_opened_at") val lastOpenedAt: Long? = null,
    /** The book's id in ttu's IndexedDB, once ttu has imported it. */
    @ColumnInfo(name = "ttu_id") val ttuId: Long? = null,
    /** ttu's title for the book; checked with [ttuId] because ttu's ids restart after a reset. */
    @ColumnInfo(name = "ttu_title") val ttuTitle: String? = null,
    /** Reading progress from ttu's bookmark, 0 to 1. */
    val progress: Float = 0f,
    /** Characters read up to the reading position, from ttu's bookmark (for statistics). */
    val characters: Long = 0,
    /** When the reader marked the book as finished; null if they didn't. */
    @ColumnInfo(name = "finished_at") val finishedAt: Long? = null,
    /** Hidden by the reader: only shown on the library's hidden shelf. */
    val hidden: Boolean = false,
    /** The folder the reader put the book in; null for none. Goes back to null if the folder is deleted. */
    @ColumnInfo(name = "folder_id") val folderId: Long? = null,
    /** When folder, hidden or finished_at last changed here; the reading position merges by furthest, not by time. */
    @ColumnInfo(name = "updated_at", defaultValue = "0") val updatedAt: Long = 0,
)

/**
 * A catalogue book that left the library (removed by the reader, or unusable), so automatic
 * imports don't bring it back.
 */
@Entity(tableName = "removed_book", primaryKeys = ["source", "source_id"])
data class RemovedBookEntity(
    val source: BookSource,
    @ColumnInfo(name = "source_id") val sourceId: String,
    @ColumnInfo(name = "removed_at") val removedAt: Long,
)

enum class BookFormat(val extension: String) {
    EPUB("epub"),
    TXT("txt"),
}

enum class BookSource {
    FILE,

    /** Downloaded from a book catalogue; the source id is the OPDS entry id. */
    OPDS,
}
