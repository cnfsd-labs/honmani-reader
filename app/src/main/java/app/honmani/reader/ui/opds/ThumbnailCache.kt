package app.honmani.reader.ui.opds

import app.honmani.reader.data.opds.OpdsException

/**
 * The cover thumbnails of a catalogue screen: the last [size] loaded, and the last [size] that
 * failed (missing, refused, not an image), so scrolling back does not request them again with the
 * login. A connection failure is not remembered: the cover is tried again once the network is back.
 */
internal class ThumbnailCache<T : Any>(private val size: Int) {
    private val loaded = lru<T>()
    private val failed = lru<Unit>()

    suspend fun get(url: String, load: suspend () -> T?): T? {
        synchronized(this) {
            loaded[url]?.let { return it }
            if (url in failed) return null
        }
        val result = try {
            load()
        } catch (e: OpdsException) {
            if (e.reason != OpdsException.Reason.NETWORK) synchronized(this) { failed[url] = Unit }
            return null
        }
        synchronized(this) { if (result != null) loaded[url] = result else failed[url] = Unit }
        return result
    }

    private fun <V> lru() = object : LinkedHashMap<String, V>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, V>?) = this.size > this@ThumbnailCache.size
    }
}
