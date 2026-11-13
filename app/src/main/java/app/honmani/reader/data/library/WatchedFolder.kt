package app.honmani.reader.data.library

import android.content.SharedPreferences
import android.net.Uri
import app.honmani.reader.data.backup.FolderTarget
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/**
 * The one folder the library looks in for new books each time it opens (optional, off by default).
 * Kept in the "device" preferences, so it is never backed up: the folder and its permission belong
 * to this phone. [seen] lives in the no-backup storage.
 */
class WatchedFolder(
    private val preferences: SharedPreferences,
    seenFile: File,
    private val target: FolderTarget,
    /** Whether the app still holds the persisted permission for a folder. */
    private val permitted: (String) -> Boolean,
) {
    val seen = SeenFiles(seenFile)

    sealed interface State {
        data object None : State

        data class Watching(val name: String) : State

        /** The permission is gone or the folder could not be read at the last scan. */
        data class Unavailable(val name: String) : State
    }

    /** The folder's tree address, or null if none is watched. */
    val folder: String? get() = preferences.getString(KEY_FOLDER, null)

    /** Whether the folder can be read now: the permission is still held. */
    fun isPermitted(): Boolean = folder?.let(permitted) == true

    fun state(): State {
        val folder = folder ?: return State.None
        val name = nameOf(folder)
        return if (!permitted(folder) || preferences.getBoolean(KEY_PROBLEM, false)) State.Unavailable(name) else State.Watching(name)
    }

    /** The last scan found the folder unreadable ([problem]) or fine. */
    fun setProblem(problem: Boolean) {
        if (preferences.getBoolean(KEY_PROBLEM, false) != problem) preferences.edit().putBoolean(KEY_PROBLEM, problem).apply()
    }

    /**
     * Watches [chosen] from now on, taking its permission (the old folder's is given up). Files
     * already in it are looked at on the next scan, as is everything after a change of folder.
     * Returns false if the permission could not be kept.
     */
    fun choose(chosen: String): Boolean {
        val old = folder
        try {
            target.grant(chosen)
        } catch (_: Exception) {
            return false
        }
        if (old != null && old != chosen) target.release(old)
        preferences.edit().putString(KEY_FOLDER, chosen).putBoolean(KEY_PROBLEM, false).apply()
        if (old != chosen) seen.clear()
        return true
    }

    /** Stops watching and gives up the permission. The books already imported stay. */
    fun stop() {
        folder?.let(target::release)
        preferences.edit().remove(KEY_FOLDER).remove(KEY_PROBLEM).apply()
        seen.clear()
    }

    companion object {
        private const val KEY_FOLDER = "watched_folder"
        private const val KEY_PROBLEM = "watched_folder_problem"

        /** The folder's name for the settings row: the last part of its path. */
        fun nameOf(folder: String): String {
            val id = Uri.parse(folder).lastPathSegment.orEmpty()
            return id.substringAfterLast('/').substringAfterLast(':').ifBlank { id }
        }
    }
}

/**
 * Files the watched folder's scan has dealt with, by document id, with the size and modified time
 * they had then: an unchanged one is not read again. A tab-separated text file, one file per line.
 */
class SeenFiles(private val file: File) {
    private var stamps: MutableMap<String, String>? = null

    private fun load(): MutableMap<String, String> = stamps ?: run {
        val map = LinkedHashMap<String, String>()
        try {
            if (file.exists()) {
                for (line in file.readLines()) {
                    val parts = line.split('\t', limit = 2)
                    if (parts.size == 2) map[parts[1]] = parts[0]
                }
            }
        } catch (_: IOException) {
            map.clear()
        }
        map.also { stamps = it }
    }

    private fun stamp(entry: TreeEntry) = "${entry.size}:${entry.modified}"

    fun isUnchanged(entry: TreeEntry): Boolean = load()[entry.id] == stamp(entry)

    fun mark(entry: TreeEntry) {
        load()[entry.id] = stamp(entry)
    }

    fun size(): Int = load().size

    fun clear() {
        stamps = LinkedHashMap()
        file.delete()
    }

    /** Writes the list; a failure only means the next scan looks at those files again. */
    fun save() {
        val map = stamps ?: return
        try {
            file.parentFile?.mkdirs()
            file.writeText(map.entries.joinToString("\n") { "${it.value}\t${it.key.replace('\n', ' ')}" })
        } catch (_: IOException) {
            // Nothing to do.
        }
    }
}

/** What a scan of the watched folder found to add. */
data class WatchedScan(
    val files: List<TreeEntry>,
    /** New files whose content the reader had removed from the library before. */
    val removed: Int,
    val unreadableFolders: Int,
    val incomplete: Boolean,
)

object WatchedFolderScan {
    /** How long a file must stay unmodified before a scan imports it (it may still be copied in). */
    const val SETTLE_MILLIS = 15_000L

    /**
     * Walks [tree] (same limits as the folder import) and returns the book files that are new or
     * changed since [seen]. Those are hashed with [hashOf] (null: could not be read, tried again
     * next time); one whose hash is in [removedHashes] is marked seen and left out. Unchanged
     * files are not read at all. A file modified at or after [modifiedBefore] may still be being
     * copied into the folder: it is left alone (not hashed, not marked) until a later scan.
     */
    suspend fun find(
        tree: DocumentTree,
        seen: SeenFiles,
        removedHashes: Set<String>,
        modifiedBefore: Long = Long.MAX_VALUE,
        hashOf: suspend (TreeEntry) -> String?,
    ): WatchedScan {
        val scan = TreeWalker.findBooks(tree)
        val files = mutableListOf<TreeEntry>()
        var removed = 0
        for (entry in scan.files) {
            coroutineContext.ensureActive()
            if (seen.isUnchanged(entry)) continue
            if (entry.modified >= modifiedBefore) continue
            val hash = hashOf(entry) ?: continue
            if (hash in removedHashes) {
                seen.mark(entry)
                removed++
            } else {
                files += entry
            }
        }
        return WatchedScan(files, removed, scan.unreadableFolders, scan.incomplete)
    }

    /** SHA-256 of [input] as lowercase hex, the same as a book's content hash. */
    fun sha256(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
