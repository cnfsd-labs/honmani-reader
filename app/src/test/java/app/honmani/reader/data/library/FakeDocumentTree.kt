package app.honmani.reader.data.library

import android.net.Uri

/** A tree of folders in memory: [folders] maps a folder id to its entries. */
class FakeDocumentTree(
    private val folders: Map<String, List<TreeEntry>>,
    override val rootId: String = "root",
) : DocumentTree {
    val created = mutableMapOf<String, Pair<String, ByteArray?>>()
    val deleted = mutableListOf<Uri>()
    val listed = mutableListOf<String>()
    var refuseCreate = false

    override fun children(folderId: String): List<TreeEntry>? {
        listed += folderId
        return folders[folderId]
    }

    override fun uriOf(id: String): Uri = Uri.parse("content://tree/$id")

    override fun createFile(folderId: String, mimeType: String, name: String): Uri? {
        if (refuseCreate) return null
        val uri = uriOf("$folderId/$name")
        created[uri.toString()] = mimeType to null
        return uri
    }

    override fun delete(uri: Uri) {
        deleted += uri
    }

    companion object {
        fun file(id: String) = TreeEntry(id, id.substringAfterLast('/'), isFolder = false)

        fun folder(id: String) = TreeEntry(id, id.substringAfterLast('/'), isFolder = true)
    }
}
