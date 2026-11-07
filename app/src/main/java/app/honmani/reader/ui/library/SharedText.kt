package app.honmani.reader.ui.library

import android.content.Intent

/**
 * Text that becomes a book: shared from another app (a browser's article, a message) or pasted in
 * the library. It goes the way a text file does (the importer names the book after the file), so
 * this only turns the text into a file name and bytes; pure, so it can be tested.
 */
object SharedText {
    /** More than this is not a text anyone reads in one go; the importer reads it twice in memory. */
    const val MAX_BYTES = 5 * 1024 * 1024

    /** Titles are cut here (in characters, not UTF-16 units), with an ellipsis. */
    const val MAX_TITLE_CHARS = 40

    sealed interface Prepared {
        /** [fileName] is "<title>.txt", the name the importer takes the title from. */
        class Ready(val title: String, val fileName: String, val bytes: ByteArray) : Prepared

        /** Nothing but white space. */
        data object Empty : Prepared

        data object TooLarge : Prepared
    }

    /** Text shared with Honmani; [title] is the sender's subject or title, if it gave one. */
    class Shared(val text: String, val title: String?)

    /**
     * The title for [text]: the sender's [requested] one, else the first line with something on
     * it, cleaned for a file name and cut to [MAX_TITLE_CHARS]; [fallback] if neither is usable.
     */
    fun title(requested: String?, text: String, fallback: String): String {
        val candidates = sequence {
            requested?.let { yield(it) }
            yield(text.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty())
        }
        for (candidate in candidates) {
            val clean = clean(candidate)
            if (clean.isNotEmpty()) return truncate(clean)
        }
        return fallback
    }

    /** Characters that don't belong in a file name become spaces; runs of white space become one. */
    private fun clean(raw: String): String =
        raw.map { c -> if (c in UNSAFE || c.isISOControl() || c.isWhitespace()) ' ' else c }
            .joinToString("")
            .split(' ')
            .filter { it.isNotEmpty() }
            .joinToString(" ")
            .trim('.', ' ')

    private fun truncate(title: String): String {
        if (title.codePointCount(0, title.length) <= MAX_TITLE_CHARS) return title
        val end = title.offsetByCodePoints(0, MAX_TITLE_CHARS)
        return title.substring(0, end).trimEnd() + "…"
    }

    /** Checks [text] and turns it into the file the importer takes. */
    fun prepare(text: String?, requestedTitle: String?, fallbackTitle: String): Prepared {
        if (text.isNullOrBlank()) return Prepared.Empty
        // A UTF-16 unit is at least one byte: too long here is too large in bytes as well.
        if (text.length > MAX_BYTES) return Prepared.TooLarge
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_BYTES) return Prepared.TooLarge
        val title = title(requestedTitle, text, fallbackTitle)
        return Prepared.Ready(title, "$title.txt", bytes)
    }

    /**
     * The text of a "Share" intent that carries text and no file (a file goes through
     * [OpenedBooks.bookUris]); null for any other intent. A share with empty text is returned too,
     * so the reader hears that it was empty.
     */
    fun fromIntent(intent: Intent?): Shared? {
        if (intent?.action != Intent.ACTION_SEND) return null
        if (intent.type?.startsWith("text/") != true) return null
        if (intent.hasExtra(Intent.EXTRA_STREAM)) return null
        val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty()
        val title = intent.getStringExtra(Intent.EXTRA_SUBJECT)?.takeIf { it.isNotBlank() }
            ?: intent.getStringExtra(Intent.EXTRA_TITLE)
        return Shared(text, title)
    }

    private val UNSAFE = setOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')
}
