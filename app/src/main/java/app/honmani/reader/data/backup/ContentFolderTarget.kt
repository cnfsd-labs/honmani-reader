package app.honmani.reader.data.backup

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** [FolderTarget] on Android's document providers; [folder] is the tree address `OpenDocumentTree` returned. */
class ContentFolderTarget(private val resolver: ContentResolver) : FolderTarget {
    override fun grant(folder: String) {
        resolver.takePersistableUriPermission(
            Uri.parse(folder),
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
    }

    override fun release(folder: String) {
        try {
            resolver.releasePersistableUriPermission(
                Uri.parse(folder),
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        } catch (_: Exception) {
            // Already gone.
        }
    }

    override fun find(folder: String, name: String): String? {
        val tree = Uri.parse(folder)
        val parentId = DocumentsContract.getTreeDocumentId(tree)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        resolver.query(
            children,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                if (cursor.getString(1) == name) {
                    return DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0)).toString()
                }
            }
        }
        return null
    }

    override fun create(folder: String, name: String): String {
        val tree = Uri.parse(folder)
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val created = DocumentsContract.createDocument(resolver, parent, "application/zip", name)
            ?: throw FileNotFoundException("Cannot create $name in $folder")
        return created.toString()
    }

    override fun open(file: String): OutputStream =
        resolver.openOutputStream(Uri.parse(file), "wt") ?: throw IOException("Cannot write $file")

    override fun delete(file: String): Boolean = try {
        DocumentsContract.deleteDocument(resolver, Uri.parse(file))
    } catch (_: FileNotFoundException) {
        true
    }

    override fun list(folder: String): List<FolderFile> {
        val tree = Uri.parse(folder)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val files = ArrayList<FolderFile>()
        resolver.query(
            children,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            ),
            null,
            null,
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(1) ?: continue
                files += FolderFile(
                    DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0)).toString(),
                    name,
                    if (cursor.isNull(2)) 0 else cursor.getLong(2),
                    if (cursor.isNull(3)) 0 else cursor.getLong(3),
                )
            }
        } ?: throw IOException("Cannot list $folder")
        return files
    }

    override fun read(file: String): InputStream =
        resolver.openInputStream(Uri.parse(file)) ?: throw IOException("Cannot read $file")

    override fun canRename(file: String): Boolean =
        resolver.query(Uri.parse(file), arrayOf(DocumentsContract.Document.COLUMN_FLAGS), null, null, null)
            ?.use { it.moveToFirst() && it.getInt(0) and DocumentsContract.Document.FLAG_SUPPORTS_RENAME != 0 }
            ?: false

    override fun rename(file: String, name: String): String =
        DocumentsContract.renameDocument(resolver, Uri.parse(file), name)?.toString()
            ?: throw IOException("Cannot rename $file to $name")

    override fun displayName(folder: String): String? = try {
        val tree = Uri.parse(folder)
        val document = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        resolver.query(document, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
    } catch (_: Exception) {
        null
    }
}
