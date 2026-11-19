package app.honmani.reader.data.dictionary

import app.honmani.reader.data.net.USER_AGENT
import app.honmani.reader.diagnostics.NetworkLog
import app.honmani.reader.diagnostics.NetworkPurpose
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URISyntaxException
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** A download failed; [message] says why in a way that can be shown to the user. */
class DownloadException(
    message: String,
    cause: Throwable? = null,
    val reason: FailureReason = FailureReason.OTHER,
) : IOException(message, cause)

/**
 * Downloads dictionary files over HTTPS. GitHub release and Hugging Face links redirect to a CDN, so
 * redirects are followed, by hand: only to https addresses, and only to the host that was asked or
 * to a host of a publisher in [redirectHosts] (a host name, or any subdomain of it). A redirect
 * anywhere else is refused, so a hijacked link cannot send the download to an arbitrary server.
 *
 * [allowHttp] and [redirectHosts] are for tests with a local server.
 */
class DictionaryDownloader(
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 30_000,
    private val maxDownloadBytes: Long = MAX_DOWNLOAD_BYTES,
    private val allowHttp: Boolean = false,
    private val redirectHosts: Set<String> = REDIRECT_HOSTS,
) {
    /**
     * Downloads [url] into [destination], reporting bytes received and the total (-1 if the
     * server does not say). Cancelling the coroutine stops the download.
     *
     * With a [partial] file (see [partialFile]) the bytes go there first and an interrupted
     * download is kept: the next call asks only for the rest (`Range`, with `If-Range` so a
     * changed file comes whole) and appends it when the server answers 206; a 200 starts over.
     * The finished file is moved to [destination].
     */
    suspend fun download(
        url: String,
        destination: File,
        purpose: NetworkPurpose = NetworkPurpose.DICTIONARY,
        partial: File? = null,
        onProgress: suspend (bytesDone: Long, bytesTotal: Long) -> Unit = { _, _ -> },
    ) = withContext(Dispatchers.IO) {
        val target = partial ?: destination
        val validatorFile = partial?.let(::validatorFileOf)
        val validator = validatorFile?.takeIf { target.length() > 0 && it.isFile }
            ?.let { runCatching { it.readText() }.getOrNull() }?.takeIf { it.isNotBlank() }
        val offset = if (validator != null) target.length() else 0L
        var connection = connect(url, purpose, validator?.let { Resume(offset, it) })
        try {
            val status = connection.responseCode
            var resumed = status == HTTP_PARTIAL && contentRangeStart(connection) == offset
            if (validator != null && status != HttpURLConnection.HTTP_OK && !resumed) {
                // 416, or a range that does not continue the file: start over.
                connection.disconnect()
                connection = connect(url, purpose)
                resumed = false
            }
            val start = if (resumed) offset else 0L
            if (validatorFile != null) {
                val next = if (resumed) validator else validatorOf(connection)
                if (next != null) validatorFile.writeText(next) else validatorFile.delete()
            }
            val length = connection.contentLengthLong
            val total = if (length >= 0) start + length else -1L
            if (total > maxDownloadBytes) throw tooLarge(partial)
            var done = start
            var reported = -1L
            try {
                connection.inputStream.use { input ->
                    FileOutputStream(target, resumed).use { output ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        while (true) {
                            ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            done += read
                            if (done > maxDownloadBytes) throw tooLarge(partial)
                            output.write(buffer, 0, read)
                            // Report about every 1% (or every MB when the size is unknown).
                            val step = if (total > 0) total / 100 else 1L shl 20
                            if (done - reported >= step) {
                                reported = done
                                onProgress(done, total)
                            }
                        }
                    }
                }
            } catch (e: DownloadException) {
                throw e
            } catch (e: IOException) {
                throw DownloadException(
                    "The download was interrupted: ${e.message}",
                    e,
                    FailureReason.ofStorage(e, otherwise = FailureReason.OFFLINE),
                )
            }
            if (total > 0 && done != total) {
                // A shorter file is resumed next time; a longer one is wrong and goes.
                if (done > total && partial != null) discardPartial(partial)
                throw DownloadException("The download was incomplete ($done of $total bytes)", reason = FailureReason.OFFLINE)
            }
            if (partial != null) {
                if (!partial.renameTo(destination)) {
                    partial.copyTo(destination, overwrite = true)
                }
                discardPartial(partial)
            }
            onProgress(done, total)
        } finally {
            connection.disconnect()
        }
    }

    /** Where an interrupted download stops and resumes from: the start, or [offset] if [validator] still matches. */
    private class Resume(val offset: Long, val validator: String)

    /**
     * The text at [url], such as a dictionary's `index.json` for an update check; at most
     * [maxBytes], or a [DownloadException].
     */
    suspend fun fetchText(url: String, maxBytes: Int = MAX_TEXT_BYTES): String = withContext(Dispatchers.IO) {
        val connection = connect(url)
        try {
            val bytes = try {
                connection.inputStream.use { input ->
                    val buffer = ByteArrayOutputStream()
                    val chunk = ByteArray(8 * 1024)
                    while (true) {
                        ensureActive()
                        val read = input.read(chunk)
                        if (read < 0) break
                        buffer.write(chunk, 0, read)
                        if (buffer.size() > maxBytes) throw DownloadException("$url is larger than expected")
                    }
                    buffer.toByteArray()
                }
            } catch (e: DownloadException) {
                throw e
            } catch (e: IOException) {
                throw DownloadException("The download was interrupted: ${e.message}", e, FailureReason.OFFLINE)
            }
            bytes.toString(Charsets.UTF_8)
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Opens [url], following redirects by hand, and checks the answer is 200 OK (or, when
     * resuming, 206 or 416); the caller disconnects.
     */
    private fun connect(
        url: String,
        purpose: NetworkPurpose = NetworkPurpose.DICTIONARY,
        resume: Resume? = null,
    ): HttpURLConnection {
        val first = parse(url)
        var current = first
        var redirects = 0
        while (true) {
            if (!allowed(current)) throw DownloadException("Only secure (https) addresses are downloaded: $current")
            if (!hostAllowed(first, current)) {
                throw DownloadException("The server sent the download to another site (${current.host}), which Honmani refuses.")
            }
            val connection = try {
                (current.toURL().openConnection() as HttpURLConnection).apply {
                    connectTimeout = connectTimeoutMs
                    readTimeout = readTimeoutMs
                    instanceFollowRedirects = false
                    setRequestProperty("User-Agent", USER_AGENT)
                    if (resume != null) {
                        setRequestProperty("Range", "bytes=${resume.offset}-")
                        setRequestProperty("If-Range", resume.validator)
                    }
                }
            } catch (e: IOException) {
                throw DownloadException("Could not connect: ${e.message}", e, FailureReason.OFFLINE)
            }
            val status = try {
                connection.responseCode
            } catch (e: IOException) {
                NetworkLog.shared.failed(purpose, current.toString(), e)
                connection.disconnect()
                throw DownloadException("Could not connect: ${e.message}", e, FailureReason.OFFLINE)
            }
            NetworkLog.shared.answered(purpose, current.toString(), status, connection.contentLengthLong)
            when {
                status == HttpURLConnection.HTTP_OK -> return connection
                resume != null && (status == HTTP_PARTIAL || status == HTTP_RANGE_NOT_SATISFIABLE) -> return connection
                status in REDIRECTS -> {
                    val location = connection.getHeaderField("Location")
                    connection.disconnect()
                    if (++redirects > MAX_REDIRECTS) throw DownloadException("The server redirected too many times", reason = FailureReason.SERVER)
                    current = try {
                        current.resolve(location ?: throw DownloadException("The server redirected without saying where", reason = FailureReason.SERVER))
                    } catch (_: IllegalArgumentException) {
                        throw DownloadException("The server sent a bad redirect", reason = FailureReason.SERVER)
                    }
                }
                else -> {
                    connection.disconnect()
                    throw DownloadException("The server answered $status for $url", reason = FailureReason.SERVER)
                }
            }
        }
    }

    private fun parse(url: String): URI = try {
        URI(url)
    } catch (e: URISyntaxException) {
        throw DownloadException("Not a valid address: $url", e)
    }

    private fun allowed(uri: URI): Boolean {
        val scheme = uri.scheme?.lowercase()
        return uri.host != null && (scheme == "https" || (allowHttp && scheme == "http"))
    }

    /** The host asked for first, or a publisher host from [redirectHosts] (or a subdomain of one). */
    private fun hostAllowed(first: URI, uri: URI): Boolean {
        val host = uri.host.lowercase()
        if (host == first.host.lowercase()) return true
        return isListed(host, redirectHosts)
    }

    private fun URI.toURL(): URL = URL(toString())

    private fun tooLarge(partial: File? = null): DownloadException {
        if (partial != null) discardPartial(partial)
        return DownloadException(
            "The file is larger than ${(maxDownloadBytes + MB - 1) / MB} MB, which is more than Honmani will download.",
        )
    }

    /** Where the range of a 206 answer starts (`Content-Range: bytes 100-299/300`), or -1. */
    private fun contentRangeStart(connection: HttpURLConnection): Long =
        connection.getHeaderField("Content-Range")?.trim()
            ?.removePrefix("bytes")?.trim()?.substringBefore('-')?.toLongOrNull() ?: -1L

    /** What tells a later request whether the file is still the same: a strong ETag, else Last-Modified. */
    private fun validatorOf(connection: HttpURLConnection): String? {
        val etag = connection.getHeaderField("ETag")?.trim()
        if (!etag.isNullOrEmpty() && !etag.startsWith("W/")) return etag
        return connection.getHeaderField("Last-Modified")?.trim()?.takeIf { it.isNotEmpty() }
    }

    companion object {
        /** The file an interrupted download of [url] is kept in until it resumes, in [directory]. */
        fun partialFile(directory: File, url: String): File {
            val hash = MessageDigest.getInstance("SHA-256").digest(url.toByteArray())
                .take(12).joinToString("") { "%02x".format(it) }
            return File(directory, "dictionary-download-$hash.part")
        }

        /** Deletes an interrupted download and what it was resumed against. */
        fun discardPartial(partial: File) {
            partial.delete()
            validatorFileOf(partial).delete()
        }

        private fun validatorFileOf(partial: File) = File(partial.path + ".validator")

        private const val HTTP_PARTIAL = 206
        private const val HTTP_RANGE_NOT_SATISFIABLE = 416

        /** The biggest dictionary is some 100 MB; a font or the example sentences are smaller. */
        const val MAX_DOWNLOAD_BYTES = 500L * 1024 * 1024

        /**
         * Hosts a redirect of a built-in source may lead to: GitHub's release assets
         * (release-assets.githubusercontent.com, formerly objects.), Hugging Face's CDN (hf.co).
         */
        val REDIRECT_HOSTS = setOf("github.com", "githubusercontent.com", "huggingface.co", "hf.co")

        /** Whether [host] is one of [hosts] or a subdomain of one ("evilgithub.com" is not). */
        internal fun isListed(host: String, hosts: Set<String>): Boolean =
            hosts.any { host.equals(it, ignoreCase = true) || host.endsWith(".$it", ignoreCase = true) }

        private const val MAX_REDIRECTS = 5
        private val REDIRECTS = setOf(301, 302, 303, 307, 308)
        private const val MB = 1024L * 1024
        private const val MAX_TEXT_BYTES = 256 * 1024
        private const val BUFFER_SIZE = 64 * 1024
    }
}
