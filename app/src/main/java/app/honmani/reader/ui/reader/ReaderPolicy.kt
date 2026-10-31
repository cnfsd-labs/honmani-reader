package app.honmani.reader.ui.reader

import java.security.MessageDigest
import java.util.Base64
import androidx.webkit.WebViewAssetLoader

/** What the reader does with a navigation the WebView is about to make. */
enum class Navigation {
    /** The reader's own origin: the WebView loads it. */
    LOAD,

    /** A web link the reader tapped: opened in the user's browser, not in the reader. */
    OPEN_EXTERNALLY,

    /** Any other link the reader tapped (javascript:, intent:, file:, content:, data:, ...). */
    BLOCK,

    /** A navigation the page made on its own, without a tap. */
    BLOCK_WITHOUT_TAP,
}

/**
 * The Content-Security-Policy of ttu's pages. ttu puts a book's HTML into its page as it is, so
 * event handlers and frames in a book would run with the reader's rights (the bridge, ttu's
 * storage). Only the build's own files and each page's inline start-up script (by its hash) may
 * run; Honmani's document-start scripts are injected by WebView and not subject to it.
 * `web-tests/support/ttu-server.js` sends the same policy.
 */
object ReaderPolicy {
    private val scriptTag = Regex("<script(\\s[^>]*)?>(.*?)</script>", RegexOption.DOT_MATCHES_ALL)
    private val srcAttribute = Regex("\\ssrc\\s*=")

    fun contentSecurityPolicy(html: String): String {
        val hashes = scriptTag.findAll(html)
            .filterNot { srcAttribute.containsMatchIn(it.groupValues[1]) }
            .map { "'sha256-${sha256(it.groupValues[2])}'" }
        return listOf(
            "script-src 'self' ${hashes.joinToString(" ")}".trim(),
            // ttu parses an EPUB in a worker it starts from a blob it builds itself: no book can supply one.
            "worker-src 'self' blob:",
            "object-src 'none'",
            "frame-src 'none'",
            "base-uri 'self'",
            "form-action 'none'",
        ).joinToString("; ")
    }

    /** Decides a navigation from its URL's [scheme] and [host] and whether a tap caused it. */
    fun navigation(scheme: String?, host: String?, hasGesture: Boolean): Navigation = when {
        scheme == "https" && host == WebViewAssetLoader.DEFAULT_DOMAIN -> Navigation.LOAD
        !hasGesture -> Navigation.BLOCK_WITHOUT_TAP
        scheme == "http" || scheme == "https" -> Navigation.OPEN_EXTERNALLY
        else -> Navigation.BLOCK
    }

    private fun sha256(text: String): String =
        Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(text.toByteArray()))
}
