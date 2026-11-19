package app.honmani.reader.ui.reader

/**
 * Where the reader starts again after its WebView's renderer died: the book that was open, so the
 * reader doesn't jump to the book list (ttu reopens the book at its saved place). If the renderer
 * dies again on the same book soon after, the book list instead, so a book that brings the
 * renderer down can't do so in a loop.
 */
internal class RendererRestart(private val repeatWindowMs: Long = REPEAT_WINDOW_MS) {
    private var lastBookId: Long? = null
    private var lastLossAtMs = 0L

    /** The URL for the new WebView, given the ttu book open when the renderer died (null: none). */
    fun startUrl(bookId: Long?, nowMs: Long, libraryUrl: String): String {
        val repeated = bookId != null && bookId == lastBookId && nowMs - lastLossAtMs < repeatWindowMs
        lastBookId = bookId
        lastLossAtMs = nowMs
        return if (bookId == null || repeated) libraryUrl else "$READER_ORIGIN${TtuLibrary.BOOK_PATH}?id=$bookId"
    }

    companion object {
        const val REPEAT_WINDOW_MS = 60_000L
    }
}
