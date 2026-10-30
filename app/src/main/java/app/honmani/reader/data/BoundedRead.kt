package app.honmani.reader.data

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/** A file or archive entry is larger than Honmani is willing to hold in memory. */
class TooLargeException(val limit: Long) : IOException("More than $limit bytes")

/**
 * Reads the rest of the stream, at most [limit] bytes. Files from outside (backups, books) can
 * claim any size, and a zip entry can inflate to gigabytes: running out of memory is an `Error`
 * that no `catch (e: Exception)` stops, so reads of such input go through here.
 *
 * @throws TooLargeException if there is more than [limit] bytes
 */
fun InputStream.readBytesAtMost(limit: Int): ByteArray {
    val out = ByteArrayOutputStream(minOf(limit, 64 * 1024))
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var remaining = limit
    while (true) {
        val read = read(buffer, 0, minOf(buffer.size, remaining + 1))
        if (read < 0) break
        if (read > remaining) throw TooLargeException(limit.toLong())
        out.write(buffer, 0, read)
        remaining -= read
    }
    return out.toByteArray()
}
