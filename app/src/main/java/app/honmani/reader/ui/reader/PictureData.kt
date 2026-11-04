package app.honmani.reader.ui.reader

import java.util.Base64

/** The picture in a `data:` URL from `tap-bridge.js`. */
internal object PictureData {
    private val TYPES = listOf("image/jpeg", "image/png", "image/webp", "image/gif")

    /** The bytes of a base64 data URL of a PNG, JPEG, WebP or GIF; null for anything else. */
    fun decode(dataUrl: String): ByteArray? {
        if (!dataUrl.startsWith("data:")) return null
        val comma = dataUrl.indexOf(',')
        if (comma < 0) return null
        val header = dataUrl.substring("data:".length, comma).split(';')
        if (header.firstOrNull() !in TYPES || "base64" !in header) return null
        return try {
            Base64.getDecoder().decode(dataUrl.substring(comma + 1)).takeIf { it.isNotEmpty() }
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
