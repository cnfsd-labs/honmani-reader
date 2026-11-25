package app.honmani.reader.data.library

import app.honmani.reader.data.readBytesAtMost
import app.honmani.reader.data.sync.EpubFiles
import app.honmani.reader.data.sync.EpubPositions
import app.honmani.reader.data.sync.Xhtml
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipFile

private const val MAX_ENCRYPTION_BYTES = 4 * 1024 * 1024

/**
 * Whether the pages of the EPUB [file] are encrypted (Adobe ADEPT, Readium LCP, Kobo): listed in
 * `META-INF/encryption.xml` and not markup. Scrambled fonts, which books without copy protection
 * have too, and a list left over in a decrypted book don't count.
 */
internal fun isCopyProtected(file: File): Boolean = try {
    ZipFile(file).use { zip ->
        val files = EpubFiles(zip)
        val encrypted = files.find("", "META-INF/encryption.xml")
            ?.let { entry -> zip.getInputStream(entry).use { it.readBytesAtMost(MAX_ENCRYPTION_BYTES) } }
            ?.let { Xhtml.parse(EpubPositions.decodeText(it)) }
            ?.findAll("cipherreference")
            ?.mapNotNullTo(HashSet()) { reference -> reference.attributes["uri"]?.let { files.find("", it) }?.name }
            .orEmpty()
        if (encrypted.isEmpty()) return false
        val page = EpubPositions.spineOf(zip).firstOrNull { it.isPage && it.name in encrypted } ?: return false
        val head = ByteArray(HEAD_BYTES)
        val length = zip.getInputStream(zip.getEntry(page.name)).use { input -> input.fill(head) }
        !EpubPositions.decodeText(head.copyOf(length)).trimStart().startsWith('<')
    }
} catch (_: IOException) {
    false
}

private const val HEAD_BYTES = 256

/** Fills [buffer] as far as the stream goes; returns how much it was. */
private fun InputStream.fill(buffer: ByteArray): Int {
    var filled = 0
    while (filled < buffer.size) {
        val read = read(buffer, filled, buffer.size - filled)
        if (read < 0) break
        filled += read
    }
    return filled
}
