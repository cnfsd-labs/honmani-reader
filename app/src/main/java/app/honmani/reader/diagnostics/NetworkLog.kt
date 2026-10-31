package app.honmani.reader.diagnostics

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** What a network request was for. */
enum class NetworkPurpose {
    DICTIONARY,
    BOOK_SERVER,
    PROGRESS_SYNC,
    GOOGLE_VOICE,
}

/**
 * One request in the [NetworkLog]. Only the host is kept, never the path, query, login or key,
 * and an error is only its class (its message could name an address).
 */
data class NetworkEntry(
    val time: Long,
    val purpose: NetworkPurpose,
    val host: String,
    /** "HTTP 200", or the class of the exception, such as "SocketTimeoutException". */
    val result: String,
    /** The size the server announced, if it did. */
    val bytes: Long? = null,
) {
    /** One line for the diagnostics report. */
    fun line(): String = buildString {
        append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date(time)))
        append("  ").append(purpose.name.lowercase(Locale.ROOT)).append("  ").append(host).append("  ").append(result)
        if (bytes != null) append("  ").append(bytes).append(" bytes")
    }
}

/**
 * The latest network requests the app made, in memory only (gone when the app stops, and never
 * sent anywhere): a list for the reader to see what Honmani contacts, matching the privacy
 * policy. At most [limit] entries are kept.
 */
class NetworkLog(private val limit: Int = MAX_ENTRIES, private val now: () -> Long = System::currentTimeMillis) {
    private val entries = ArrayDeque<NetworkEntry>()

    /** A request that was answered with HTTP [status]. */
    fun answered(purpose: NetworkPurpose, url: String, status: Int, bytes: Long? = null) =
        add(purpose, url, "HTTP $status", bytes)

    /** A request that failed with [error]. */
    fun failed(purpose: NetworkPurpose, url: String, error: Throwable) =
        add(purpose, url, error.javaClass.simpleName.ifEmpty { "Error" }, null)

    @Synchronized
    private fun add(purpose: NetworkPurpose, url: String, result: String, bytes: Long?) {
        entries.addFirst(NetworkEntry(now(), purpose, hostOf(url), result, bytes?.takeIf { it >= 0 }))
        while (entries.size > limit) entries.removeLast()
    }

    /** The requests, newest first. */
    @Synchronized
    fun entries(): List<NetworkEntry> = entries.toList()

    @Synchronized
    fun clear() = entries.clear()

    companion object {
        const val MAX_ENTRIES = 200

        /** The app's log, written by the classes that open connections. */
        val shared = NetworkLog()

        /**
         * The host of [url] and nothing else: no scheme, login ("user:password@"), port, path,
         * query or fragment. "unknown" if there is none.
         */
        fun hostOf(url: String): String {
            val authority = url.substringAfter("://", "").takeWhile { it != '/' && it != '?' && it != '#' && it != '\\' }
            val hostAndPort = authority.substringAfterLast('@')
            val host = if (hostAndPort.startsWith("[")) {
                hostAndPort.substringBefore(']') + "]"
            } else {
                hostAndPort.substringBefore(':')
            }
            return host.lowercase(Locale.ROOT).ifBlank { "unknown" }
        }
    }
}
