package app.honmani.reader.ui.reader

import android.webkit.WebResourceResponse
import androidx.webkit.WebViewAssetLoader
import app.honmani.reader.data.fonts.FontCatalog
import java.io.ByteArrayInputStream
import java.io.File

/**
 * Serves downloaded reader fonts at [TtuLibrary.FONT_FILES_PATH] on the reader origin, where
 * ttu's `@font-face` rules for them point. Only catalog file names are served.
 */
class FontPathHandler(private val fontsDir: File) : WebViewAssetLoader.PathHandler {
    override fun handle(path: String): WebResourceResponse {
        val file = FontCatalog.byFileName(path)?.let { File(fontsDir, it.fileName) }
        if (file == null || !file.isFile) {
            return WebResourceResponse("text/plain", "utf-8", 404, "Not Found", BookPathHandler.NOSNIFF, ByteArrayInputStream(ByteArray(0)))
        }
        return WebResourceResponse("font/ttf", null, 200, "OK", BookPathHandler.NOSNIFF, file.inputStream())
    }
}
