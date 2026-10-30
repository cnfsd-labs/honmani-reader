package app.honmani.reader.ui.reader

import android.content.res.AssetManager
import android.webkit.WebResourceResponse
import androidx.webkit.WebViewAssetLoader
import java.io.ByteArrayInputStream
import java.io.FileNotFoundException

/**
 * Serves the bundled ッツ Ebook Reader build. Missing files get an explicit 404 so that no
 * request on the reader origin ever falls through to the network. Pages carry
 * [ReaderPolicy.contentSecurityPolicy].
 */
class TtuPathHandler(private val assets: AssetManager) : WebViewAssetLoader.PathHandler {
    override fun handle(path: String): WebResourceResponse {
        val resolved = TtuAssetResolver.resolve(path) ?: return notFound()
        return try {
            if (resolved.mimeType == "text/html") page(resolved.assetPath)
            else WebResourceResponse(resolved.mimeType, null, 200, "OK", BookPathHandler.NOSNIFF, assets.open(resolved.assetPath))
        } catch (_: FileNotFoundException) {
            notFound()
        }
    }

    private fun page(assetPath: String): WebResourceResponse {
        val bytes = assets.open(assetPath).use { it.readBytes() }
        return WebResourceResponse(
            "text/html",
            "utf-8",
            200,
            "OK",
            BookPathHandler.NOSNIFF + ("Content-Security-Policy" to ReaderPolicy.contentSecurityPolicy(bytes.decodeToString())),
            ByteArrayInputStream(bytes),
        )
    }

    private fun notFound() = WebResourceResponse(
        "text/plain",
        "utf-8",
        404,
        "Not Found",
        BookPathHandler.NOSNIFF,
        ByteArrayInputStream(ByteArray(0)),
    )
}
