package app.honmani.reader.ui.reader

import android.webkit.WebResourceResponse
import androidx.webkit.WebViewAssetLoader
import java.io.ByteArrayInputStream
import java.io.File

/**
 * Serves library book files to `library-bridge.js` at [TtuLibrary.BOOK_FILES_PATH] on the
 * reader origin. Only file names the library itself creates (content hash plus extension) are
 * served.
 */
class BookPathHandler(private val booksDir: File) : WebViewAssetLoader.PathHandler {
    override fun handle(path: String): WebResourceResponse {
        val file = path.takeIf(::isLibraryFileName)?.let { File(booksDir, it) }
        if (file == null || !file.isFile) {
            return WebResourceResponse(
                "text/plain",
                "utf-8",
                404,
                "Not Found",
                NOSNIFF,
                ByteArrayInputStream(ByteArray(0)),
            )
        }
        return WebResourceResponse("application/octet-stream", null, 200, "OK", NOSNIFF, file.inputStream())
    }

    companion object {
        /** Books are data: the WebView must never guess a type and run one as a page. */
        val NOSNIFF = mapOf("X-Content-Type-Options" to "nosniff")

        private val LIBRARY_FILE_NAME = Regex("[0-9a-f]{64}\\.(epub|txt)")

        fun isLibraryFileName(name: String): Boolean = LIBRARY_FILE_NAME.matches(name)
    }
}
