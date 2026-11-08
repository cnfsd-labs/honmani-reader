package app.honmani.reader.data.backup

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * The edit and deletion times in another phone's sync file. [SyncMerge] lets the later time win, so a
 * phone with a wrong clock (years ahead) would beat every later edit on every phone for good. A time
 * more than [DAY_MS] past now is therefore never taken as it is: [SyncFolder] asks the reader first
 * ([latest]) and [SyncMerge] sets such times back to the ceiling ([clamp]).
 *
 * The times are found by walking the file's JSON tree: the keys in [KEYS], the times of the folders
 * and of the synced settings. Lookup and review moments are history, never compared as an edit.
 */
object SyncStamps {
    const val DAY_MS = 24L * 60 * 60 * 1000

    /** The latest time anything in this file would be trusted at (now plus a day for clocks that differ). */
    fun ceiling(now: Long) = now + DAY_MS

    private val KEYS = setOf("updatedAt", "deletedAt", "lastLookedUpAt", "finishedAt", "addedAt", "lastOpenedAt", "exportedAt")

    /** The largest time in [file], or 0. */
    fun latest(file: BackupFile): Long {
        var max = 0L
        walk(BackupCodec.toTree(file)) { max = maxOf(max, it); it }
        return max
    }

    /** [file] with every time above [ceiling] set to it; the same instance if there was none. */
    fun clamp(file: BackupFile, ceiling: Long): BackupFile {
        var changed = false
        val tree = walk(BackupCodec.toTree(file)) {
            if (it > ceiling) {
                changed = true
                ceiling
            } else {
                it
            }
        }
        return if (changed) BackupCodec.fromTree(tree) else file
    }

    private fun walk(element: JsonElement, parent: String? = null, grandparent: String? = null, change: (Long) -> Long): JsonElement = when (element) {
        is JsonObject -> JsonObject(
            element.mapValues { (key, value) ->
                val isStamp = key in KEYS || parent == "folderTimes" || (key == "at" && grandparent == "syncSettings")
                val number = (value as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
                if (isStamp && number != null) JsonPrimitive(change(number)) else walk(value, key, parent, change)
            },
        )
        is JsonArray -> JsonArray(element.map { walk(it, parent, grandparent, change) })
        else -> element
    }
}
