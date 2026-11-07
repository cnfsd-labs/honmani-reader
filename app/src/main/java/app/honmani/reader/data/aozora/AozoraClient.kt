package app.honmani.reader.data.aozora

import app.honmani.reader.diagnostics.NetworkLog
import app.honmani.reader.diagnostics.NetworkPurpose
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URISyntaxException
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** The two things the free books catalogue fetches; a fake stands in for it in tests. */
interface AozoraSource {
    /**
     * Downloads the work list to [target], unless the server says it is still [etag] (then
     * [ListFetch.NotModified]; [etag] null asks for the list unconditionally).
     */
    suspend fun list(target: File, etag: String?): ListFetch

    /** The text of the work at [file] (`<card>/<file>`), with [onProgress] (bytes, total or -1). */
    suspend fun book(file: String, onProgress: (done: Long, total: Long) -> Unit = { _, _ -> }): ByteArray
}

sealed interface ListFetch {
    data object NotModified : ListFetch

    data class Downloaded(val etag: String?) : ListFetch
}

/**
 * Fetches the work list from GitHub Pages and the books from Aozora Bunko, over https only.
 * Redirects are followed by hand and only within the host that was asked (a redirect to another
 * host or to plain http is refused). Nothing but the request itself is sent.
 *
 * [allowHttp] and the addresses are for tests with a local server.
 */
class AozoraClient(
    private val listUrl: String = LIST_URL,
    private val booksUrl: String = BOOKS_URL,
    private val allowHttp: Boolean = false,
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 30_000,
) : AozoraSource {

    override suspend fun list(target: File, etag: String?): ListFetch = withContext(Dispatchers.IO) {
        val connection = open(listUrl, NetworkPurpose.FREE_BOOKS_LIST, etag, acceptNotModified = true)
        try {
            if (connection.responseCode == HttpURLConnection.HTTP_NOT_MODIFIED) return@withContext ListFetch.NotModified
            val total = connection.contentLengthLong
            if (total > MAX_LIST_BYTES) throw AozoraException(AozoraException.Reason.TOO_LARGE, "The work list is too large")
            var done = 0L
            try {
                connection.inputStream.use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(BUFFER)
                        while (true) {
                            ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            done += read
                            if (done > MAX_LIST_BYTES) throw AozoraException(AozoraException.Reason.TOO_LARGE, "The work list is too large")
                            output.write(buffer, 0, read)
                        }
                    }
                }
            } catch (e: AozoraException) {
                throw e
            } catch (e: IOException) {
                throw AozoraException(AozoraException.Reason.NETWORK, "The download was interrupted", e)
            }
            ListFetch.Downloaded(connection.getHeaderField("ETag"))
        } finally {
            connection.disconnect()
        }
    }

    override suspend fun book(file: String, onProgress: (done: Long, total: Long) -> Unit): ByteArray =
        withContext(Dispatchers.IO) {
            val connection = open(bookUrl(file), NetworkPurpose.FREE_BOOKS_BOOK, null, acceptNotModified = false)
            try {
                val total = connection.contentLengthLong
                if (total > MAX_BOOK_BYTES) throw AozoraException(AozoraException.Reason.TOO_LARGE, "The file is too large")
                val bytes = ByteArrayOutputStream(if (total in 1..MAX_BOOK_BYTES) total.toInt() else BUFFER)
                try {
                    connection.inputStream.use { input ->
                        val buffer = ByteArray(BUFFER)
                        while (true) {
                            ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            if (bytes.size() + read > MAX_BOOK_BYTES) {
                                throw AozoraException(AozoraException.Reason.TOO_LARGE, "The file is too large")
                            }
                            bytes.write(buffer, 0, read)
                            onProgress(bytes.size().toLong(), total)
                        }
                    }
                } catch (e: AozoraException) {
                    throw e
                } catch (e: IOException) {
                    throw AozoraException(AozoraException.Reason.NETWORK, "The download was interrupted", e)
                }
                if (total > 0 && bytes.size().toLong() != total) {
                    throw AozoraException(AozoraException.Reason.NETWORK, "The download was incomplete")
                }
                AozoraArchive.text(bytes.toByteArray())
            } finally {
                connection.disconnect()
            }
        }

    /** The address of a work's text file: `<card>/<file>` is at `<books>/<card>/files/<file>`. */
    fun bookUrl(file: String): String {
        val card = file.substringBefore('/')
        val name = file.substringAfter('/')
        if (card.isEmpty() || name.isEmpty() || name.contains('/') || file.contains("..")) {
            throw AozoraException(AozoraException.Reason.BAD_DATA, "Not a book file: $file")
        }
        return "${booksUrl.trimEnd('/')}/$card/files/$name"
    }

    /**
     * Opens [url] and returns the connection with its answer ready: status 200 (or 304 if
     * [acceptNotModified]); the caller disconnects. Redirects stay on the first address's host.
     */
    private fun open(url: String, purpose: NetworkPurpose, etag: String?, acceptNotModified: Boolean): HttpURLConnection {
        val first = parse(url)
        var current = first
        var redirects = 0
        while (true) {
            if (!allowed(current)) throw AozoraException(AozoraException.Reason.REFUSED, "Not a secure address")
            if (!sameHost(first, current)) throw AozoraException(AozoraException.Reason.REFUSED, "Redirect to another host")
            val connection = try {
                (current.toURL().openConnection() as HttpURLConnection).apply {
                    connectTimeout = connectTimeoutMs
                    readTimeout = readTimeoutMs
                    instanceFollowRedirects = false
                    setRequestProperty("User-Agent", USER_AGENT)
                    if (etag != null) setRequestProperty("If-None-Match", etag)
                }
            } catch (e: IOException) {
                throw AozoraException(AozoraException.Reason.NETWORK, "Could not connect", e)
            }
            val status = try {
                connection.responseCode
            } catch (e: IOException) {
                NetworkLog.shared.failed(purpose, current.toString(), e)
                connection.disconnect()
                throw AozoraException(AozoraException.Reason.NETWORK, "Could not connect", e)
            }
            NetworkLog.shared.answered(purpose, current.toString(), status, connection.contentLengthLong)
            when {
                status == HttpURLConnection.HTTP_OK -> return connection
                status == HttpURLConnection.HTTP_NOT_MODIFIED && acceptNotModified -> return connection
                status in REDIRECTS -> {
                    val location = connection.getHeaderField("Location")
                    connection.disconnect()
                    if (++redirects > MAX_REDIRECTS) throw AozoraException(AozoraException.Reason.SERVER, "Too many redirects")
                    current = try {
                        current.resolve(location ?: throw AozoraException(AozoraException.Reason.SERVER, "Redirect without a location"))
                    } catch (_: URISyntaxException) {
                        throw AozoraException(AozoraException.Reason.SERVER, "Bad redirect")
                    } catch (_: IllegalArgumentException) {
                        throw AozoraException(AozoraException.Reason.SERVER, "Bad redirect")
                    }
                }
                else -> {
                    connection.disconnect()
                    throw AozoraException(AozoraException.Reason.SERVER, "The server answered $status")
                }
            }
        }
    }

    private fun parse(url: String): URI = try {
        URI(url)
    } catch (e: URISyntaxException) {
        throw AozoraException(AozoraException.Reason.BAD_DATA, "Bad address", e)
    }

    private fun allowed(uri: URI): Boolean {
        val scheme = uri.scheme?.lowercase()
        return uri.host != null && (scheme == "https" || (allowHttp && scheme == "http"))
    }

    private fun sameHost(a: URI, b: URI): Boolean =
        a.host.equals(b.host, ignoreCase = true) && port(a) == port(b)

    private fun port(uri: URI): Int = if (uri.port != -1) uri.port else if (uri.scheme.equals("https", true)) 443 else 80

    private fun URI.toURL(): URL = URL(toString())

    companion object {
        const val LIST_URL = "https://cnfsd-labs.github.io/honmani-reader/aozora/index.json"
        const val BOOKS_URL = "https://www.aozora.gr.jp/cards"

        /** The list is about 2.5 MB; more than this is not our list. */
        const val MAX_LIST_BYTES = 20L * 1024 * 1024

        /** The file as downloaded (a ZIP is smaller than its text). */
        const val MAX_BOOK_BYTES = 30L * 1024 * 1024

        private const val MAX_REDIRECTS = 5
        private val REDIRECTS = setOf(301, 302, 303, 307, 308)
        private const val BUFFER = 64 * 1024
        private const val USER_AGENT = "Honmani (Android; free books)"
    }
}
