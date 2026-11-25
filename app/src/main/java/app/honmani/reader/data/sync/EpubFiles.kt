package app.honmani.reader.data.sync

import java.net.URLDecoder
import java.text.Normalizer
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * Finds a book's files by the references in it, forgiving the same as ttu's import (patch 0007,
 * `extract-epub.ts`): encoded or not, backslashes, above the root, another case or Unicode form.
 */
internal class EpubFiles(private val zip: ZipFile) {
    private val byLooseName: Map<String, ZipEntry> by lazy {
        zip.entries().asSequence().filter { !it.isDirectory }.associateBy { loose(it.name) }
    }

    /** The file that [href], written in a file of the folder [directory], means; null if there is none. */
    fun find(directory: String, href: String): ZipEntry? {
        val raw = href.substringBefore('#').replace('\\', '/').trim()
        if (raw.isEmpty()) return null
        val names = listOf(resolve(directory, raw), resolve(directory, decode(raw)))
        return names.firstNotNullOfOrNull { name -> zip.getEntry(name)?.takeIf { !it.isDirectory } }
            ?: names.firstNotNullOfOrNull { byLooseName[loose(it)] }
    }

    companion object {
        private fun loose(name: String) = Normalizer.normalize(name, Normalizer.Form.NFC).lowercase()

        private fun decode(href: String): String = try {
            URLDecoder.decode(href.replace("+", "%2B"), "UTF-8")
        } catch (_: IllegalArgumentException) {
            href
        }

        private fun resolve(directory: String, path: String): String {
            val parts = directory.split('/').filterTo(mutableListOf()) { it.isNotEmpty() }
            for (part in path.split('/')) {
                when (part) {
                    "", "." -> Unit
                    ".." -> parts.removeLastOrNull()
                    else -> parts += part
                }
            }
            return parts.joinToString("/")
        }
    }
}

/**
 * A document of the spine: the manifest [id] ttu names its section after (`ttu-<id>`), its [name]
 * in the zip, and whether ttu counts its text ([isPage], and [isSvg] for a picture that can hold some).
 */
internal class SpineDocument(val id: String, val name: String, val isPage: Boolean, val isSvg: Boolean) {
    val hasText: Boolean get() = isPage || isSvg
}

/** The spine's documents in reading order; [text] reads a file of the zip. */
internal fun spineDocuments(zip: ZipFile, text: (String) -> String?): List<SpineDocument> {
    val files = EpubFiles(zip)
    val container = files.find("", "META-INF/container.xml")?.let { text(it.name) }?.let(Xhtml::parse) ?: return emptyList()
    val opfPath = container.find("rootfile")?.attributes?.get("full-path")?.let { files.find("", it) }?.name ?: return emptyList()
    val opf = text(opfPath)?.let(Xhtml::parse) ?: return emptyList()
    val base = opfPath.substringBeforeLast('/', "")
    val items = opf.findAll("item").mapNotNull { item -> item.attributes["id"]?.let { it to item.attributes } }.toMap()

    fun document(id: String): SpineDocument? {
        val item = items[id] ?: return null
        val entry = item["href"]?.let { files.find(base, it) } ?: return null
        val type = mediaType(item["media-type"].orEmpty(), entry.name)
        return SpineDocument(id, entry.name, isPage = type == PAGE, isSvg = type == SVG)
    }

    return opf.findAll("itemref").mapNotNull { ref ->
        val id = ref.attributes["idref"] ?: return@mapNotNull null
        val chain = generateSequence(id) { items[it]?.get("fallback") }.take(MAX_FALLBACKS)
        chain.firstNotNullOfOrNull { candidate -> document(candidate)?.takeIf { it.isPage } } ?: document(id)
    }
}

private const val MAX_FALLBACKS = 8
private const val PAGE = "application/xhtml+xml"
private const val SVG = "image/svg+xml"
private val pageTypes = setOf(PAGE, "text/html", "text/x-oeb1-document")
private val otherTypes = setOf("text/css", "application/x-dtbncx+xml")
private val pageExtensions = setOf("xhtml", "html", "htm", "xht")

/** The declared media type, or the file extension's where that isn't one the reader knows. */
private fun mediaType(declared: String, name: String): String {
    val extension = name.substringAfterLast('/').substringAfterLast('.', "").lowercase()
    return when {
        declared in pageTypes -> PAGE
        declared in otherTypes || declared.startsWith("image/") -> declared
        extension in pageExtensions -> PAGE
        extension == "svg" -> SVG
        else -> declared
    }
}
