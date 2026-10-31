package app.honmani.reader.data.dictionary

import app.honmani.reader.diagnostics.NetworkLog
import app.honmani.reader.diagnostics.NetworkPurpose
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** A download failed; [message] says why in a way that can be shown to the user. */
class DownloadException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** Downloads dictionary files over HTTPS, following redirects (GitHub release links redirect). */
class DictionaryDownloader(
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 30_000,
) {
    /**
     * Downloads [url] into [destination], reporting bytes received and the total (-1 if the
     * server does not say). Cancelling the coroutine stops the download.
     */
    suspend fun download(
        url: String,
        destination: File,
        onProgress: suspend (bytesDone: Long, bytesTotal: Long) -> Unit = { _, _ -> },
    ) = withContext(Dispatchers.IO) {
        val connection = connect(url)
        try {
            val total = connection.contentLengthLong
            var done = 0L
            var reported = -1L
            try {
                connection.inputStream.use { input ->
                    destination.outputStream().use { output ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        while (true) {
                            ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            done += read
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
                throw DownloadException("The download was interrupted: ${e.message}", e)
            }
            if (total > 0 && done != total) {
                throw DownloadException("The download was incomplete ($done of $total bytes)")
            }
            onProgress(done, total)
        } finally {
            connection.disconnect()
        }
    }

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
                throw DownloadException("The download was interrupted: ${e.message}", e)
            }
            bytes.toString(Charsets.UTF_8)
        } finally {
            connection.disconnect()
        }
    }

    /** Opens [url] and checks the answer is 200 OK; the caller disconnects. */
    private fun connect(url: String): HttpURLConnection {
        val connection = try {
            (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", USER_AGENT)
            }
        } catch (e: IOException) {
            throw DownloadException("Could not connect: ${e.message}", e)
        }
        val status = try {
            connection.responseCode
        } catch (e: IOException) {
            NetworkLog.shared.failed(NetworkPurpose.DICTIONARY, url, e)
            connection.disconnect()
            throw DownloadException("Could not connect: ${e.message}", e)
        }
        NetworkLog.shared.answered(NetworkPurpose.DICTIONARY, url, status, connection.contentLengthLong)
        if (status != HttpURLConnection.HTTP_OK) {
            connection.disconnect()
            throw DownloadException("The server answered $status for $url")
        }
        return connection
    }

    private companion object {
        const val MAX_TEXT_BYTES = 256 * 1024
        const val BUFFER_SIZE = 64 * 1024
        const val USER_AGENT = "Honmani (Android; dictionary download)"
    }
}
