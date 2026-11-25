package app.honmani.reader.data.backup

/**
 * The edit and deletion times in another phone's sync file. [SyncMerge] lets the later time win, so a
 * phone with a wrong clock (years ahead) would beat every later edit on every phone for good. A time
 * more than [DAY_MS] past now is therefore never taken as it is: [SyncFolder] asks the reader first
 * ([latest]) and [SyncMerge] sets such times back to the ceiling ([clamp]).
 *
 * Lookup and review moments are history, never compared as an edit, so they are left alone.
 * `SyncStampsTest` fails if the file gets a time field that is not handled in [change].
 */
object SyncStamps {
    const val DAY_MS = 24L * 60 * 60 * 1000

    /** The latest time anything in this file would be trusted at (now plus a day for clocks that differ). */
    fun ceiling(now: Long) = now + DAY_MS

    /** The largest time in [file], or 0. */
    fun latest(file: BackupFile): Long {
        var max = 0L
        change(file) { max = maxOf(max, it); it }
        return max
    }

    /** [file] with every time above [ceiling] set to it; the same instance if there was none. */
    fun clamp(file: BackupFile, ceiling: Long): BackupFile =
        if (latest(file) <= ceiling) file else change(file) { minOf(it, ceiling) }

    private fun change(file: BackupFile, time: (Long) -> Long) = file.copy(
        exportedAt = time(file.exportedAt),
        books = file.books.map { entry ->
            entry.copy(
                book = entry.book?.let {
                    it.copy(
                        addedAt = time(it.addedAt),
                        lastOpenedAt = it.lastOpenedAt?.let(time),
                        finishedAt = it.finishedAt?.let(time),
                        updatedAt = time(it.updatedAt),
                    )
                },
            )
        },
        finishedBooks = file.finishedBooks.map { it.copy(finishedAt = it.finishedAt?.let(time)) },
        bookmarks = file.bookmarks.map { it.copy(updatedAt = time(it.updatedAt)) },
        highlights = file.highlights.map { it.copy(updatedAt = time(it.updatedAt)) },
        pronunciations = file.pronunciations.map { it.copy(updatedAt = time(it.updatedAt)) },
        words = file.words.map { word ->
            word.copy(
                lastLookedUpAt = time(word.lastLookedUpAt),
                updatedAt = time(word.updatedAt),
                card = word.card?.let { it.copy(addedAt = time(it.addedAt), updatedAt = time(it.updatedAt)) },
            )
        },
        tombstones = file.tombstones.map { it.copy(deletedAt = time(it.deletedAt)) },
        folderTimes = file.folderTimes.mapValues { time(it.value) },
        syncSettings = file.syncSettings.mapValues { (_, setting) -> setting.copy(at = time(setting.at)) },
    )
}
