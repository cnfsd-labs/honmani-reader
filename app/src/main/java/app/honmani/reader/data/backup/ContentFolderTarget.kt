package app.honmani.reader.data.backup

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import java.io.FileNotFoundException
import java.io.IOException
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

    override fun findOrCreate(folder: String, name: String): String {
        val tree = Uri.parse(folder)
        val parentId = DocumentsContract.getTreeDocumentId(tree)
        // A file from an earlier choice of this folder is reused, so there is only ever the one.
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
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, parentId)
        val created = DocumentsContract.createDocument(resolver, parent, "application/zip", name)
            ?: throw FileNotFoundException("Cannot create $name in $folder")
        return created.toString()
    }

    override fun open(file: String): OutputStream =
        resolver.openOutputStream(Uri.parse(file), "wt") ?: throw IOException("Cannot write $file")
}
