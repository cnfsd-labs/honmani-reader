package app.honmani.reader.data.library

import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Writes an [AozoraBook] as an EPUB 3, so ッツ Ebook Reader shows its furigana as ruby (its text
 * importer can't) and its chapters in the table of contents. Right-to-left page order, Japanese.
 */
object AozoraEpub {
    fun write(book: AozoraBook, identifier: String, target: File) {
        val chapters = book.chapters.ifEmpty { listOf(AozoraChapter(null, emptyList())) }
        ZipOutputStream(target.outputStream().buffered()).use { zip ->
            // The mimetype comes first and uncompressed, as EPUB requires.
            val mimetype = "application/epub+zip".toByteArray()
            zip.putNextEntry(
                ZipEntry("mimetype").apply {
                    method = ZipEntry.STORED
                    size = mimetype.size.toLong()
                    compressedSize = mimetype.size.toLong()
                    crc = CRC32().apply { update(mimetype) }.value
                },
            )
            zip.write(mimetype)
            zip.closeEntry()

            zip.add("META-INF/container.xml", CONTAINER)
            zip.add("OEBPS/content.opf", packageDocument(book, identifier, chapters.size))
            zip.add("OEBPS/nav.xhtml", navigation(book, chapters))
            zip.add("OEBPS/style.css", STYLE)
            chapters.forEachIndexed { i, chapter ->
                zip.add("OEBPS/${chapterFile(i)}", chapterDocument(book.title, chapter))
            }
        }
    }

    private fun ZipOutputStream.add(name: String, text: String) {
        putNextEntry(ZipEntry(name))
        write(text.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private fun chapterFile(index: Int) = "chapter-${index + 1}.xhtml"

    private fun packageDocument(book: AozoraBook, identifier: String, chapterCount: Int) = buildString {
        append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        append("""<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id" xml:lang="ja">""")
        append("<metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\">")
        append("<dc:identifier id=\"id\">").append(escape(identifier)).append("</dc:identifier>")
        append("<dc:title>").append(escape(book.title)).append("</dc:title>")
        book.author?.let { append("<dc:creator>").append(escape(it)).append("</dc:creator>") }
        append("<dc:language>ja</dc:language>")
        append("<meta property=\"dcterms:modified\">2000-01-01T00:00:00Z</meta>")
        append("</metadata><manifest>")
        append("<item id=\"nav\" href=\"nav.xhtml\" media-type=\"application/xhtml+xml\" properties=\"nav\"/>")
        append("<item id=\"style\" href=\"style.css\" media-type=\"text/css\"/>")
        for (i in 0 until chapterCount) {
            append("<item id=\"c${i + 1}\" href=\"${chapterFile(i)}\" media-type=\"application/xhtml+xml\"/>")
        }
        append("</manifest><spine page-progression-direction=\"rtl\">")
        for (i in 0 until chapterCount) append("<itemref idref=\"c${i + 1}\"/>")
        append("</spine></package>")
    }

    private fun navigation(book: AozoraBook, chapters: List<AozoraChapter>) = buildString {
        append(XHTML_START).append("<head><title>").append(escape(book.title)).append("</title></head>")
        append("<body><nav epub:type=\"toc\" id=\"toc\"><ol>")
        chapters.forEachIndexed { i, chapter ->
            val title = chapter.title ?: if (i == 0) book.title else "${i + 1}"
            append("<li><a href=\"${chapterFile(i)}\">").append(escape(title)).append("</a></li>")
        }
        append("</ol></nav></body></html>")
    }

    private fun chapterDocument(bookTitle: String, chapter: AozoraChapter) = buildString {
        append(XHTML_START).append("<head><title>").append(escape(chapter.title ?: bookTitle)).append("</title>")
        append("<link rel=\"stylesheet\" type=\"text/css\" href=\"style.css\"/></head><body>")
        for (block in chapter.blocks) {
            when (block) {
                AozoraBlock.Blank -> append("<p><br/></p>")
                is AozoraBlock.Paragraph -> append("<p").appendLayout(block.layout).append(">")
                    .appendInlines(block.inlines).append("</p>")
                is AozoraBlock.Heading -> {
                    val tag = "h${block.level + 1}"
                    append("<$tag>").appendInlines(block.inlines).append("</$tag>")
                }
            }
            append('\n')
        }
        append("</body></html>")
    }

    private fun StringBuilder.appendInlines(inlines: List<AozoraInline>): StringBuilder {
        for (inline in inlines) {
            when (inline) {
                is AozoraInline.Text -> append(escape(inline.text))
                is AozoraInline.Ruby -> append("<ruby>").append(escape(inline.base))
                    .append("<rt>").append(escape(inline.reading)).append("</rt></ruby>")
                is AozoraInline.Styled -> {
                    val tag = if (inline.style.isEmphasis) "em" else "span"
                    append("<$tag class=\"${inline.style.cssClass}\">").appendInlines(inline.inlines).append("</$tag>")
                }
            }
        }
        return this
    }

    /** The paragraph's indentation and alignment as a style attribute, in characters (em). */
    private fun StringBuilder.appendLayout(layout: AozoraLayout): StringBuilder {
        val style = buildList {
            val hanging = layout.hanging
            if (hanging != null) {
                if (hanging > 0) add("padding-inline-start: ${hanging}em")
                if (layout.indent != hanging) add("text-indent: ${layout.indent - hanging}em")
            } else if (layout.indent > 0) {
                add("padding-inline-start: ${layout.indent}em")
            }
            if (layout.bottom) add("text-align: end")
            if (layout.bottomGap > 0) add("padding-inline-end: ${layout.bottomGap}em")
        }
        if (style.isNotEmpty()) append(" style=\"").append(style.joinToString("; ")).append('"')
        return this
    }

    private val AozoraStyle.isEmphasis get() = this != AozoraStyle.BOLD && this != AozoraStyle.ITALIC && this != AozoraStyle.UPRIGHT

    private val AozoraStyle.cssClass get() = name.lowercase().replace('_', '-')

    /** Escapes markup and drops what XML 1.0 cannot hold (control characters, U+FFFE/U+FFFF, lone surrogates). */
    private fun escape(text: String) = withoutForbiddenXml(text)
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    private fun withoutForbiddenXml(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                Character.isHighSurrogate(c) && i + 1 < text.length && Character.isLowSurrogate(text[i + 1]) -> {
                    out.append(c).append(text[i + 1])
                    i++
                }
                Character.isSurrogate(c) || c == '\uFFFE' || c == '\uFFFF' -> {}
                c < ' ' && c != '\t' && c != '\n' && c != '\r' -> {}
                else -> out.append(c)
            }
            i++
        }
        return out.toString()
    }

    private const val XHTML_START = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<!DOCTYPE html>\n" +
        "<html xmlns=\"http://www.w3.org/1999/xhtml\" xmlns:epub=\"http://www.idpf.org/2007/ops\" " +
        "xml:lang=\"ja\" lang=\"ja\">"

    private const val CONTAINER = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
        "<container version=\"1.0\" xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\">" +
        "<rootfiles><rootfile full-path=\"OEBPS/content.opf\" media-type=\"application/oebps-package+xml\"/>" +
        "</rootfiles></container>"

    /** Marks beside the text in vertical writing (over it in horizontal), as Aozora Bunko shows them. */
    private val STYLE = buildString {
        append("em { font-style: normal; }\n")
        for ((style, mark) in listOf(
            AozoraStyle.SESAME to "filled sesame",
            AozoraStyle.OPEN_SESAME to "open sesame",
            AozoraStyle.CIRCLE to "filled circle",
            AozoraStyle.OPEN_CIRCLE to "open circle",
            AozoraStyle.TRIANGLE to "filled triangle",
            AozoraStyle.OPEN_TRIANGLE to "open triangle",
            AozoraStyle.DOUBLE_CIRCLE to "open double-circle",
            AozoraStyle.BULLSEYE to "filled double-circle",
            AozoraStyle.CROSS to "\"\u00D7\"",
        )) {
            append("em.${style.cssClass} { -webkit-text-emphasis-style: $mark; text-emphasis-style: $mark; }\n")
        }
        for ((style, line) in listOf(
            AozoraStyle.LINE to "solid",
            AozoraStyle.DOUBLE_LINE to "double",
            AozoraStyle.DOTTED_LINE to "dotted",
            AozoraStyle.DASHED_LINE to "dashed",
            AozoraStyle.WAVY_LINE to "wavy",
        )) {
            append("em.${style.cssClass} { text-decoration: underline $line; text-underline-position: right; }\n")
        }
        append("span.bold { font-weight: bold; }\n")
        append("span.italic { font-style: italic; }\n")
        append("span.upright { -webkit-text-combine: horizontal; text-combine-upright: all; }\n")
    }
}
