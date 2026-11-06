package app.honmani.reader.data.library

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import app.honmani.reader.diagnostics.logWarning

/**
 * One entry of a folder in a [DocumentTree]: [id] is the document id inside the tree. [size] and
 * [modified] are what the provider reports (-1 / 0 if it does not), to tell a changed file apart.
 */
data class TreeEntry(
    val id: String,
    val name: String,
    val isFolder: Boolean,
    val size: Long = -1,
    val modified: Long = 0,
)

/**
 * A folder the user picked, with its subfolders: what the folder import and the library export
 * need, so they can be tested with a fake. All calls block; call them off the main thread.
 */
interface DocumentTree {
    val rootId: String

    /** The entries of folder [folderId]; null if it cannot be read. */
    fun children(folderId: String): List<TreeEntry>?

    /** The address to open [id] with. */
    fun uriOf(id: String): Uri

    /** Makes a new file in folder [folderId] (the provider may change the name); null if it cannot. */
    fun createFile(folderId: String, mimeType: String, name: String): Uri?

    /** Deletes a file made by [createFile], e.g. a half-written one. Never throws. */
    fun delete(uri: Uri)
}

/** A [DocumentTree] on a tree address from `OpenDocumentTree`; needs no permission beyond the pick. */
class ResolverDocumentTree(private val resolver: ContentResolver, private val tree: Uri) : DocumentTree {
    override val rootId: String = DocumentsContract.getTreeDocumentId(tree)

    override fun children(folderId: String): List<TreeEntry>? = try {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, folderId)
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        resolver.query(uri, columns, null, null, null)?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val id = cursor.getString(0) ?: continue
                    add(
                        TreeEntry(
                            id = id,
                            name = cursor.getString(1).orEmpty(),
                            isFolder = cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR,
                            size = if (cursor.isNull(3)) -1 else cursor.getLong(3),
                            modified = if (cursor.isNull(4)) 0 else cursor.getLong(4),
                        ),
                    )
                }
            }
        }
    } catch (e: Exception) {
        // SecurityException, or whatever the other app's provider throws.
        logWarning("Honmani.library", "Could not list a folder", e)
        null
    }

    override fun uriOf(id: String): Uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)

    override fun createFile(folderId: String, mimeType: String, name: String): Uri? = try {
        DocumentsContract.createDocument(resolver, uriOf(folderId), mimeType, name)
    } catch (e: Exception) {
        logWarning("Honmani.library", "Could not create a file in the chosen folder", e)
        null
    }

    override fun delete(uri: Uri) {
        try {
            DocumentsContract.deleteDocument(resolver, uri)
        } catch (_: Exception) {
            // Best effort.
        }
    }
}
