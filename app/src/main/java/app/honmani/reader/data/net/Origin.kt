package app.honmani.reader.data.net

import java.net.MalformedURLException
import java.net.URI
import java.net.URISyntaxException
import java.net.URL

/**
 * "HTTP://NAS/opds" becomes "http://nas:80": scheme, host and port (default ports spelled out), so
 * two URLs of one server compare equal. Null for anything that is not an absolute URL with a host.
 */
fun originOf(url: String): String? {
    val uri = try {
        URI(url.trim())
    } catch (_: URISyntaxException) {
        return null
    }
    val scheme = uri.scheme?.lowercase() ?: return null
    var host = uri.host
    var port = uri.port
    if (host == null) {
        // java.net.URI has no host for names with an underscore ("calibre_web", common in Docker).
        val parsed = try {
            URL(url.trim())
        } catch (_: MalformedURLException) {
            return null
        }
        host = parsed.host.takeIf { it.isNotEmpty() } ?: return null
        port = parsed.port
    }
    host = host.lowercase()
    if (port == -1) port = if (scheme == "https") 443 else 80
    return "$scheme://$host:$port"
}
