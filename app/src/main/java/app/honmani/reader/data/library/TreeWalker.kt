package app.honmani.reader.data.library

import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/** The book files [TreeWalker] found, and what it could not cover. */
data class TreeScan(
    val files: List<TreeEntry>,
    /** Folders that could not be read. */
    val unreadableFolders: Int,
    /** True if it stopped at [TreeWalker.MAX_FILES] or skipped folders deeper than the depth limit. */
    val incomplete: Boolean,
)

/** Finds the book files in a folder and all its subfolders. */
object TreeWalker {
    const val MAX_DEPTH = 12
    const val MAX_FILES = 2000

    /** Extensions the import takes (the same as a single picked file). */
    fun isBookName(name: String): Boolean {
        val extension = name.substringAfterLast('.', "").lowercase()
        return BookFormat.entries.any { it.extension == extension }
    }

    /**
     * Walks [tree] folder by folder, breadth first, each folder's entries sorted by name. An id met
     * twice is skipped, so a provider with a loop ends. Checks for cancellation between folders.
     */
    suspend fun findBooks(
        tree: DocumentTree,
        maxDepth: Int = MAX_DEPTH,
        maxFiles: Int = MAX_FILES,
        accept: (String) -> Boolean = ::isBookName,
    ): TreeScan {
        val files = mutableListOf<TreeEntry>()
        val seen = mutableSetOf(tree.rootId)
        val queue = ArrayDeque<Pair<String, Int>>()
        queue.addLast(tree.rootId to 0)
        var unreadable = 0
        var incomplete = false
        while (queue.isNotEmpty()) {
            coroutineContext.ensureActive()
            val (folderId, depth) = queue.removeFirst()
            val entries = tree.children(folderId)
            if (entries == null) {
                unreadable++
                continue
            }
            for (entry in entries.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })) {
                if (entry.isFolder) {
                    when {
                        !seen.add(entry.id) -> Unit
                        depth >= maxDepth -> incomplete = true
                        else -> queue.addLast(entry.id to depth + 1)
                    }
                } else if (accept(entry.name) && seen.add(entry.id)) {
                    if (files.size >= maxFiles) return TreeScan(files, unreadable, incomplete = true)
                    files += entry
                }
            }
        }
        return TreeScan(files, unreadable, incomplete)
    }
}
