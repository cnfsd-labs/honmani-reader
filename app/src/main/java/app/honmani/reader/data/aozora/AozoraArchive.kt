package app.honmani.reader.data.aozora

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

/**
 * Takes the text out of a downloaded Aozora file: a ZIP with one `.txt` in it, or the `.txt`
 * itself. Everything stays in memory; nothing is written under an archive's names, and the
 * sizes are limited because the archive comes from the internet.
 */
object AozoraArchive {
    /** The text, or all entries together, may not be bigger than this once unpacked. */
    const val MAX_BYTES = 30 * 1024 * 1024

    /** An archive with more entries than this is not an Aozora book. */
    const val MAX_ENTRIES = 20

    /**
     * The text file in [bytes] (a ZIP, recognised by its first bytes, or plain text). Throws an
     * [AozoraException] with [AozoraException.Reason.BAD_DATA] for a ZIP that is damaged, has no or
     * several text files, has an entry name with a path (`..`, a drive or a leading slash), or too
     * many entries; [AozoraException.Reason.TOO_LARGE] when it unpacks to more than [MAX_BYTES].
     */
    fun text(bytes: ByteArray): ByteArray {
        if (!isZip(bytes)) {
            if (bytes.size > MAX_BYTES) throw AozoraException(AozoraException.Reason.TOO_LARGE, "The text is too large")
            return bytes
        }
        try {
            return unzip(ByteArrayInputStream(bytes))
        } catch (e: ZipException) {
            throw AozoraException(AozoraException.Reason.BAD_DATA, "The ZIP file is damaged", e)
        } catch (e: IOException) {
            if (e is AozoraException) throw e
            throw AozoraException(AozoraException.Reason.BAD_DATA, "The ZIP file is damaged", e)
        }
    }

    private fun isZip(bytes: ByteArray): Boolean =
        bytes.size >= 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte() &&
            bytes[2] == 3.toByte() && bytes[3] == 4.toByte()

    private fun unzip(input: InputStream): ByteArray {
        var text: ByteArray? = null
        var entries = 0
        var total = 0L
        // Latin-1 reads any name; a name that is not valid UTF-8 would otherwise throw.
        ZipInputStream(input, Charsets.ISO_8859_1).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (++entries > MAX_ENTRIES) throw AozoraException(AozoraException.Reason.BAD_DATA, "Too many files in the ZIP")
                if (unsafeName(entry.name)) throw AozoraException(AozoraException.Reason.BAD_DATA, "A file in the ZIP has an unsafe name")
                if (entry.isDirectory) continue
                val isText = entry.name.endsWith(".txt", ignoreCase = true)
                val out = if (isText) ByteArrayOutputStream() else null
                val buffer = ByteArray(BUFFER)
                while (true) {
                    val read = zip.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_BYTES) throw AozoraException(AozoraException.Reason.TOO_LARGE, "The ZIP unpacks to too much")
                    out?.write(buffer, 0, read)
                }
                if (out != null) {
                    if (text != null) throw AozoraException(AozoraException.Reason.BAD_DATA, "More than one text file in the ZIP")
                    text = out.toByteArray()
                }
            }
        }
        return text?.takeIf { it.isNotEmpty() }
            ?: throw AozoraException(AozoraException.Reason.BAD_DATA, "No text file in the ZIP")
    }

    private fun unsafeName(name: String): Boolean =
        name.startsWith("/") || name.startsWith("\\") || name.contains(':') || name.contains('\u0000') ||
            name.split('/', '\\').any { it == ".." }

    private const val BUFFER = 64 * 1024
}
