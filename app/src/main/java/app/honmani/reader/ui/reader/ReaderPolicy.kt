package app.honmani.reader.ui.reader

import java.security.MessageDigest
import java.util.Base64

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
            "object-src 'none'",
            "frame-src 'none'",
            "base-uri 'self'",
            "form-action 'none'",
        ).joinToString("; ")
    }

    private fun sha256(text: String): String =
        Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(text.toByteArray()))
}
