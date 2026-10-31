package app.honmani.reader.data.opds

import app.honmani.reader.data.net.LoginGate
import app.honmani.reader.data.net.originOf
import app.honmani.reader.diagnostics.NetworkLog
import app.honmani.reader.diagnostics.NetworkPurpose
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.net.UnknownHostException
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** A catalogue's address and login. The login is only sent to the catalogue's own origin. */
data class OpdsServer(val url: String, val username: String? = null, val password: String? = null) {
    override fun toString() = "OpdsServer(url=$url, username=$username)"
}

/** Why a catalogue request failed, for a message the user can act on. */
class OpdsException(val reason: Reason, message: String, cause: Throwable? = null) : IOException(message, cause) {
    enum class Reason {
        /** 401/403: missing or wrong user name or password. */
        UNAUTHORIZED,

        /** 404, or a page that is not an OPDS feed. */
        NOT_A_CATALOGUE,

        /** Another HTTP error. */
        SERVER,

        /** No connection, unknown host, timeout. */
        NETWORK,

        /** TLS failed, usually a self-signed certificate. */
        CERTIFICATE,

        /** The server asked for the login over http on a hotspot with a login page: not sent. */
        HOTSPOT,

        TOO_LARGE,
    }
}

/**
 * HTTP for OPDS catalogues: feeds, cover thumbnails and book downloads. Logins go as Basic auth
 * until a server asks for Digest (Calibre's content server); its challenge is then remembered.
 * Over plain http the login waits until the server asks for it ([LoginGate]).
 */
class OpdsClient(
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 30_000,
    private val logins: LoginGate = LoginGate(),
) {
    /** The latest Digest challenge per origin, so only the first request is answered twice. */
    private val digests = ConcurrentHashMap<String, DigestChallenge>()

    suspend fun feed(server: OpdsServer, url: String): OpdsFeed {
        val bytes = bytes(server, url, MAX_FEED_BYTES)
        // Off the main thread: a feed can be megabytes of XML.
        return withContext(Dispatchers.Default) {
            try {
                OpdsParser.parseFeed(bytes, url)
            } catch (e: NotAFeedException) {
                throw OpdsException(OpdsException.Reason.NOT_A_CATALOGUE, "$url is not an OPDS feed", e)
            }
        }
    }

    /** The URL of the results for [terms]; reads the OpenSearch description if needed. */
    suspend fun searchUrl(server: OpdsServer, search: OpdsSearch, terms: String): String {
        val template = when (search) {
            is OpdsSearch.Template -> search
            is OpdsSearch.Description -> try {
                val bytes = bytes(server, search.url, MAX_FEED_BYTES)
                withContext(Dispatchers.Default) { OpdsParser.parseSearchDescription(bytes, search.url) }
            } catch (e: NotAFeedException) {
                throw OpdsException(OpdsException.Reason.NOT_A_CATALOGUE, "Bad search description", e)
            } ?: throw OpdsException(OpdsException.Reason.NOT_A_CATALOGUE, "No search template")
        }
        return template.urlFor(terms)
    }

    suspend fun bytes(server: OpdsServer, url: String, limit: Int): ByteArray = request(server, url) { connection ->
        if (connection.contentLengthLong > limit) tooLarge(url)
        connection.inputStream.use { it.readUpTo(limit) ?: tooLarge(url) }
    }

    /**
     * Downloads [url] into [destination], reporting bytes received and the total (-1 if unknown).
     * Returns the file name the server suggests, if any.
     */
    suspend fun download(
        server: OpdsServer,
        url: String,
        destination: File,
        onProgress: (bytesDone: Long, bytesTotal: Long) -> Unit,
    ): String? = request(server, url) { connection ->
        val total = connection.contentLengthLong
        var done = 0L
        connection.inputStream.use { input ->
            destination.outputStream().use { output ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    done += read
                    if (done > MAX_BOOK_BYTES) tooLarge(url)
                    onProgress(done, total)
                }
            }
        }
        if (total > 0 && done != total) {
            throw OpdsException(OpdsException.Reason.NETWORK, "Incomplete download ($done of $total bytes)")
        }
        connection.getHeaderField("Content-Disposition")?.let(::fileNameFromDisposition)
    }

    /** Opens [url] (following redirects itself, so the login never leaves the server) and reads it. */
    private suspend fun <T> request(
        server: OpdsServer,
        url: String,
        read: suspend (HttpURLConnection) -> T,
    ): T = withContext(Dispatchers.IO) {
        var current = url
        var redirects = 0
        var digestTries = 0
        var loginAsked = false
        while (redirects <= MAX_REDIRECTS) {
            // Only web addresses: another scheme (ftp:, file:) gives no HTTP connection.
            if (!isWeb(current)) {
                throw OpdsException(OpdsException.Reason.NOT_A_CATALOGUE, "Not a web address: $current")
            }
            val login = login(server, current)
            val connection = try {
                (URL(current).openConnection() as HttpURLConnection).apply {
                    connectTimeout = connectTimeoutMs
                    readTimeout = readTimeoutMs
                    instanceFollowRedirects = false
                    setRequestProperty("User-Agent", USER_AGENT)
                    setRequestProperty("Accept", ACCEPT)
                    login?.let { setRequestProperty("Authorization", it) }
                }
            } catch (e: IOException) {
                throw networkError(e)
            } catch (e: IllegalArgumentException) {
                throw OpdsException(OpdsException.Reason.NOT_A_CATALOGUE, "Bad address $current", e)
            }
            try {
                val status = try {
                    connection.responseCode
                } catch (e: IOException) {
                    NetworkLog.shared.failed(NetworkPurpose.BOOK_SERVER, current, e)
                    throw networkError(e)
                }
                NetworkLog.shared.answered(NetworkPurpose.BOOK_SERVER, current, status, connection.contentLengthLong)
                when (status) {
                    in 200..299 -> return@withContext try {
                        read(connection)
                    } catch (e: OpdsException) {
                        throw e
                    } catch (e: IOException) {
                        throw networkError(e)
                    }
                    301, 302, 303, 307, 308 -> {
                        current = connection.getHeaderField("Location")
                            ?.let { location -> resolve(current, location) }
                            ?: throw OpdsException(OpdsException.Reason.SERVER, "Redirect without a location")
                        redirects++
                        digestTries = 0
                    }
                    401 -> {
                        val unauthorized = OpdsException(OpdsException.Reason.UNAUTHORIZED, "The server answered 401")
                        if (authorization(server, current) == null) throw unauthorized
                        val challenge = DigestChallenge.from(connection.headerFields["WWW-Authenticate"].orEmpty())
                        if (login == null) {
                            // Over http the login waited for this: send it now (not on a hotspot).
                            if (logins.blocked(current)) {
                                throw OpdsException(OpdsException.Reason.HOTSPOT, "No login over http on a hotspot")
                            }
                            if (loginAsked) throw unauthorized
                            logins.asked(current)
                            loginAsked = true
                        } else if (challenge == null || digestTries >= (if (challenge.stale) 2 else 1)) {
                            // A server that wants Digest: answer its challenge (again once if the
                            // nonce went stale), then the login was wrong.
                            throw unauthorized
                        }
                        if (challenge != null) {
                            digests[originOf(current)!!] = challenge
                            digestTries++
                        }
                    }
                    403 -> throw OpdsException(OpdsException.Reason.UNAUTHORIZED, "The server answered $status")
                    404, 410 -> throw OpdsException(OpdsException.Reason.NOT_A_CATALOGUE, "The server answered $status")
                    else -> throw OpdsException(OpdsException.Reason.SERVER, "The server answered $status")
                }
            } finally {
                connection.disconnect()
            }
        }
        throw OpdsException(OpdsException.Reason.SERVER, "Too many redirects")
    }

    /**
     * The Authorization header for [url]: Digest once the origin asked for it, else Basic; none
     * over http until the server has asked.
     */
    private fun login(server: OpdsServer, url: String): String? {
        val basic = authorization(server, url) ?: return null
        if (!logins.maySend(url)) return null
        val digest = digests[originOf(url)] ?: return basic
        val uri = URI(url)
        val target = (uri.rawPath?.ifEmpty { null } ?: "/") + (uri.rawQuery?.let { "?$it" } ?: "")
        return digest.authorization(server.username!!, server.password.orEmpty(), "GET", target)
    }

    private fun tooLarge(url: String): Nothing =
        throw OpdsException(OpdsException.Reason.TOO_LARGE, "$url is too large")

    companion object {
        private const val MAX_REDIRECTS = 5
        private const val MAX_FEED_BYTES = 8 shl 20
        const val MAX_THUMBNAIL_BYTES = 2 shl 20
        private const val MAX_BOOK_BYTES = 500L shl 20
        private const val BUFFER_SIZE = 64 * 1024
        private const val USER_AGENT = "Honmani (Android; OPDS)"
        private const val ACCEPT = "application/atom+xml, application/xml;q=0.9, */*;q=0.5"

        /** Basic auth for requests to the catalogue's own scheme, host and port only. */
        internal fun authorization(server: OpdsServer, url: String): String? {
            val username = server.username?.takeIf { it.isNotEmpty() } ?: return null
            if (originOf(url) == null || originOf(url) != originOf(server.url)) return null
            val token = "$username:${server.password.orEmpty()}".toByteArray(Charsets.UTF_8)
            return "Basic " + Base64.getEncoder().encodeToString(token)
        }

        private fun isWeb(url: String): Boolean {
            val scheme = url.substringBefore("://", "").lowercase()
            return scheme == "http" || scheme == "https"
        }

        private fun resolve(base: String, location: String): String? = OpdsParser.resolve(base, location)

        /** The file name from a Content-Disposition header (RFC 6266), preferring `filename*`. */
        internal fun fileNameFromDisposition(header: String): String? {
            Regex("filename\\*\\s*=\\s*([^']*)'[^']*'([^;]+)", RegexOption.IGNORE_CASE).find(header)?.let { match ->
                val charset = match.groupValues[1].ifBlank { "UTF-8" }
                val name = try {
                    URLDecoder.decode(match.groupValues[2].trim().replace("+", "%2B"), charset)
                } catch (_: Exception) {
                    null
                }
                name?.let(::safeFileName)?.let { return it }
            }
            val plain = Regex("filename\\s*=\\s*(\"([^\"]*)\"|[^;]+)", RegexOption.IGNORE_CASE).find(header)
                ?: return null
            val name = plain.groupValues[2].ifEmpty { plain.groupValues[1] }.trim()
            return safeFileName(name)
        }

        /** Only the last path segment; never a path. */
        private fun safeFileName(name: String): String? =
            name.substringAfterLast('/').substringAfterLast('\\').trim().takeIf { it.isNotEmpty() && it != "." && it != ".." }

        private fun networkError(e: IOException): OpdsException = when (e) {
            is SSLException -> OpdsException(OpdsException.Reason.CERTIFICATE, e.message ?: "TLS failed", e)
            is UnknownHostException, is ConnectException, is InterruptedIOException ->
                OpdsException(OpdsException.Reason.NETWORK, e.message ?: "No connection", e)
            else -> OpdsException(OpdsException.Reason.NETWORK, e.message ?: "Network error", e)
        }

        /** Reads at most [limit] bytes; null if there is more. */
        private fun InputStream.readUpTo(limit: Int): ByteArray? {
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = read(buffer)
                if (read < 0) return out.toByteArray()
                out.write(buffer, 0, read)
                if (out.size() > limit) return null
            }
        }
    }
}
