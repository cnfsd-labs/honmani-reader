package app.honmani.reader.data.net

import java.net.URI
import java.net.URISyntaxException

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
    val host = uri.host?.lowercase() ?: return null
    val port = if (uri.port != -1) uri.port else if (scheme == "https") 443 else 80
    return "$scheme://$host:$port"
}
