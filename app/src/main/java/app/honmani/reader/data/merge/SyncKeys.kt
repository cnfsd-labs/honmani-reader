package app.honmani.reader.data.merge

/**
 * The natural key of each synced row: what stays the same on every phone, while the local `id`s
 * differ. Parts are joined by U+0000. These are the keys of [TombstoneEntity] and the ones a merge
 * matches rows by (as `Backup.restore` already does).
 */
object SyncKeys {
    const val WORD = "word"
    const val WORD_LOOKUP = "word_lookup"
    const val REVIEW_CARD = "review_card"
    const val REVIEW_LOG = "review_log"
    const val BOOKMARK = "bookmark"
    const val HIGHLIGHT = "highlight"
    const val PRONUNCIATION = "pronunciation"
    const val FOLDER = "folder"
    const val KNOWN_BATCH = "known_batch"
    const val FINISHED_BOOK = "finished_book"

    private const val SEPARATOR = "\u0000"

    private fun join(vararg parts: Any?) = parts.joinToString(SEPARATOR)

    fun word(expression: String, reading: String) = join(expression, reading)

    /** A lookup has no identity of its own: the word, the moment and the text as written. */
    fun wordLookup(expression: String, reading: String, lookedUpAt: Long, surface: String) =
        join(expression, reading, lookedUpAt, surface)

    /** The card belongs to its word. */
    fun reviewCard(expression: String, reading: String) = word(expression, reading)

    fun reviewLog(expression: String, reading: String, at: Long, grade: Int) = join(expression, reading, at, grade)

    fun bookmark(contentHash: String, characters: Long) = join(contentHash, characters)

    fun highlight(contentHash: String, start: Long, end: Long) = join(contentHash, start, end)

    fun pronunciation(contentHash: String, text: String) = join(contentHash, text)

    /** Folder names are unique ignoring case. */
    fun folder(name: String) = name.lowercase()

    fun knownBatch(source: String, detail: String, createdAt: Long) = join(source, detail, createdAt)

    fun finishedBook(contentHash: String) = contentHash
}
