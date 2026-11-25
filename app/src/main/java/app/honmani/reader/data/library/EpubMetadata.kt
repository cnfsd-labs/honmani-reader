package app.honmani.reader.data.library

import app.honmani.reader.data.sync.EpubFiles
import app.honmani.reader.data.sync.EpubPositions
import app.honmani.reader.data.sync.XNode
import app.honmani.reader.data.sync.Xhtml
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile

/** Title, author and cover of an EPUB, read from its package document (OPF). */
data class EpubMetadata(
    val title: String?,
    val author: String?,
    val cover: Cover?,
) {
    class Cover(val bytes: ByteArray, val extension: String)

    companion object {
        /** Covers larger than this are skipped; the library shows the title instead. */
        const val MAX_COVER_BYTES = 10 * 1024 * 1024
        private const val MAX_XML_BYTES = 4 * 1024 * 1024
        private const val CONTAINER_PATH = "META-INF/container.xml"

        /**
         * Reads [file], or throws [InvalidEpubException] if it is not a readable EPUB. A missing
         * title, author or cover is not an error.
         */
        fun read(file: File): EpubMetadata = try {
            ZipFile(file).use { zip -> read(zip) }
        } catch (e: ZipException) {
            throw InvalidEpubException("Not a zip file", e)
        } catch (e: IOException) {
            throw InvalidEpubException("Unreadable file", e)
        }

        private fun read(zip: ZipFile): EpubMetadata {
            val files = EpubFiles(zip)
            val container = files.find("", CONTAINER_PATH)?.let { zip.xml(it) }
                ?: throw InvalidEpubException("No $CONTAINER_PATH")
            val opfEntry = container.findAll("rootfile").firstNotNullOfOrNull { root ->
                root.attributes["full-path"]?.let { files.find("", it) }
            } ?: throw InvalidEpubException("No package document")
            val opf = zip.xml(opfEntry) ?: throw InvalidEpubException("Unreadable ${opfEntry.name}")

            val title = opf.findAll("title").firstNotNullOfOrNull { it.trimmedText() }
            val author = opf.findAll("creator").firstNotNullOfOrNull { it.trimmedText() }
            val cover = coverHref(opf)
                ?.let { files.find(opfEntry.name.substringBeforeLast('/', ""), it) }
                ?.let { zip.bytes(it, MAX_COVER_BYTES) }
                ?.let { bytes -> imageExtension(bytes)?.let { Cover(bytes, it) } }
            return EpubMetadata(title, author, cover)
        }

        /** EPUB 3 `properties="cover-image"`, else the EPUB 2 `<meta name="cover">` item. */
        private fun coverHref(opf: XNode): String? {
            val items = opf.findAll("item")
            items.firstOrNull { "cover-image" in it.attributes["properties"].orEmpty().split(' ') }
                ?.let { return it.attributes["href"] }
            val coverId = opf.findAll("meta")
                .firstOrNull { it.attributes["name"] == "cover" }
                ?.attributes?.get("content")
                ?: return null
            return items.firstOrNull { it.attributes["id"] == coverId }
                ?.takeIf { it.attributes["media-type"].orEmpty().startsWith("image/") }
                ?.attributes?.get("href")
        }

        private fun imageExtension(bytes: ByteArray): String? = when {
            bytes.startsWith(0xFF, 0xD8, 0xFF) -> "jpg"
            bytes.startsWith(0x89, 'P'.code, 'N'.code, 'G'.code) -> "png"
            bytes.startsWith('G'.code, 'I'.code, 'F'.code) -> "gif"
            bytes.size > 12 && bytes.startsWith('R'.code, 'I'.code, 'F'.code, 'F'.code) &&
                String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP" -> "webp"
            else -> null
        }

        private fun ByteArray.startsWith(vararg prefix: Int): Boolean =
            size >= prefix.size && prefix.indices.all { this[it].toInt() and 0xFF == prefix[it] }

        private fun ZipFile.bytes(entry: ZipEntry, limit: Int): ByteArray? {
            if (entry.size > limit) return null
            return getInputStream(entry).use { input ->
                val bytes = input.readUpTo(limit + 1)
                bytes.takeIf { it.size <= limit }
            }
        }

        private fun InputStream.readUpTo(limit: Int): ByteArray {
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (out.size() < limit) {
                val read = read(buffer, 0, minOf(buffer.size, limit - out.size()))
                if (read < 0) break
                out.write(buffer, 0, read)
            }
            return out.toByteArray()
        }

        /** Leniently, as ttu reads it: a stray & in a title must not refuse the book. */
        private fun ZipFile.xml(entry: ZipEntry): XNode? =
            bytes(entry, MAX_XML_BYTES)?.let { Xhtml.parse(EpubPositions.decodeText(it)) }

        private fun XNode.trimmedText(): String? =
            textContent().replace(Regex("\\s+"), " ").trim().ifEmpty { null }
    }
}

class InvalidEpubException(message: String, cause: Throwable? = null) : Exception(message, cause)
