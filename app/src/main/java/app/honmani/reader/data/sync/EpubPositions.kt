package app.honmani.reader.data.sync

import app.honmani.reader.data.readBytesAtMost
import java.io.File
import java.io.IOException
import java.net.URLDecoder
import java.nio.charset.Charset
import java.util.BitSet
import java.util.zip.ZipFile

/** Text with the characters that have furigana (ruby) over them. */
class RubyText(val text: String, val ruby: BitSet = BitSet())

/**
 * Reading positions in an EPUB, translated between KOReader and ッツ Ebook Reader (ttu):
 * - KOReader saves an XPointer into its CREngine DOM, where each spine document is a
 *   `DocFragment` (`/body/DocFragment[3]/body/div/p[7]/text().12`: the 3rd spine document, its
 *   7th `p`, 12 characters into the text).
 * - ttu saves the number of characters before the reading point, counted its way
 *   (`get-character-count.ts`, `get-paragraph-nodes.ts`: no ruby readings, nothing hidden, only
 *   Japanese letters, digits and Latin letters).
 *
 * Both are worked out from the book file Honmani keeps, so positions match to the character
 * where both readers see the same document. A position whose elements don't exist in this file
 * gives null (the caller falls back to the percentage).
 */
class EpubPositions private constructor(
    private val file: File,
    /** Zip entry names of the spine documents, in reading order. */
    private val spine: List<String>,
    /** Characters before each spine document. */
    private val starts: IntArray,
    /** Characters in the whole book. */
    val total: Int,
) {
    /** ttu's character count at [xpointer], or null if it doesn't point into this book. */
    fun charactersAt(xpointer: String): Int? {
        val match = DOC_FRAGMENT.find(xpointer) ?: return null
        val index = (match.groupValues[1].toIntOrNull() ?: return null) - 1
        if (index !in spine.indices) return null
        val body = document(index) ?: return null
        val steps = xpointer.substring(match.range.last + 1).split('/').filter { it.isNotEmpty() }
        var node: XNode = body
        var offset: Int? = null
        for ((i, step) in steps.withIndex()) {
            // A path that no longer fits this file: the percentage is closer than a guess.
            val parsed = STEP.matchEntire(step) ?: return null
            val name = parsed.groupValues[1]
            val position = parsed.groupValues[2].toIntOrNull() ?: 1
            val point = parsed.groupValues[3].toIntOrNull()
            // The step into the document's own <body>.
            if (i == 0 && name == "body") {
                offset = point
                continue
            }
            val candidates = if (name == "text()") {
                node.children.filter { it.isText }
            } else {
                node.children.filter { it.name == name.lowercase() }
            }
            // Text nodes can split differently (CREngine joins some): then the element's start.
            val next = candidates.getOrNull(position - 1) ?: if (name == "text()") break else return null
            node = next
            offset = point
        }
        return starts[index] + charactersBefore(body, node, offset)
    }

    /** An XPointer KOReader resolves to ttu's reading point after [characters], or null for an empty book. */
    fun xpointerAt(characters: Int): String? {
        if (total == 0) return null
        val target = characters.coerceIn(0, total)
        // The document holding that character (the last one with text for the very end).
        val index = spine.indices.lastOrNull { starts[it] <= target && sizeOf(it) > 0 && (target < starts[it] + sizeOf(it) || target == total) }
            ?: return null
        val body = document(index) ?: return null
        var remaining = target - starts[index]
        var found: XNode? = null
        var offset = 0
        var last: XNode? = null
        walk(body) { node ->
            val count = count(node.text!!)
            if (count == 0) return@walk true
            last = node
            if (remaining < count) {
                found = node
                offset = offsetOf(node.text, remaining)
                return@walk false
            }
            remaining -= count
            true
        }
        val node = found ?: last?.also { offset = it.text!!.codePointCount(0, it.text.length) } ?: return null
        return "/body/DocFragment[${index + 1}]" + path(body, node) + ".$offset"
    }

    private fun sizeOf(index: Int): Int = (if (index + 1 < starts.size) starts[index + 1] else total) - starts[index]

    private fun document(index: Int): XNode? = try {
        ZipFile(file).use { zip -> zip.getEntry(spine[index])?.let { zip.getInputStream(it).use { input -> input.readBytesAtMost(MAX_DOCUMENT_BYTES) } } }
            ?.let { bodyOf(Xhtml.parse(decodeText(it))) }
    } catch (_: IOException) {
        null
    }

    /** "/body/div[1]/p[7]/text()[1]" from [body] down to [node]. */
    private fun path(body: XNode, node: XNode): String {
        val segments = mutableListOf<String>()
        var current: XNode? = node
        while (current != null && current !== body) {
            val parent = current.parent ?: break
            segments += if (current.isText) {
                "text()[${parent.children.filter { it.isText }.indexOf(current) + 1}]"
            } else {
                "${current.name}[${parent.children.filter { it.name == current!!.name }.indexOf(current) + 1}]"
            }
            current = parent
        }
        return "/body" + segments.reversed().joinToString("") { "/$it" }
    }

    companion object {
        /** A chapter is a few hundred KB, a whole novel in one file a few MB; a zip entry can inflate to far more. */
        internal const val MAX_DOCUMENT_BYTES = 32 * 1024 * 1024
        private val DOC_FRAGMENT = Regex("""^/body/DocFragment\[(\d+)]""")
        private val STEP = Regex("""^([^\[\].]+)(?:\[(\d+)])?(?:\.(\d+))?$""")
        private const val BYTE_ORDER_MARK = '\uFEFF'
        private val ENCODING = Regex("""^<\?xml[^>]*encoding\s*=\s*["']([^"']+)["']""")

        /** Reads the book's spine and counts its characters; null if it is not a readable EPUB. */
        fun read(file: File): EpubPositions? = try {
            ZipFile(file).use { zip ->
                val spine = spineOf(zip)
                if (spine.isEmpty()) return null
                var total = 0
                val starts = IntArray(spine.size)
                for ((i, name) in spine.withIndex()) {
                    starts[i] = total
                    val body = zip.text(name)?.let { bodyOf(Xhtml.parse(it)) } ?: continue
                    walk(body) { node ->
                        total += count(node.text!!)
                        true
                    }
                }
                EpubPositions(file, spine, starts, total)
            }
        } catch (_: IOException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }

        /**
         * The text ttu shows, one per spine document in reading order, with the characters that
         * have furigana marked and a line break after each paragraph. Ruby readings are left out,
         * so 漢字 with furigana stays one word. Null if it is not a readable EPUB.
         */
        fun texts(file: File): List<RubyText>? = try {
            ZipFile(file).use { zip ->
                val spine = spineOf(zip)
                if (spine.isEmpty()) return null
                spine.mapNotNull { name ->
                    val body = zip.text(name)?.let { bodyOf(Xhtml.parse(it)) } ?: return@mapNotNull null
                    val text = StringBuilder()
                    val ruby = BitSet()
                    fun visit(node: XNode, inRuby: Boolean) {
                        for (child in node.children) {
                            if (child.isText) {
                                if (inRuby) ruby.set(text.length, text.length + child.text!!.length)
                                text.append(child.text)
                            } else if (!skipped(child)) {
                                visit(child, inRuby || child.name == "ruby")
                                // A paragraph ends a sentence even without 。 (「そうか」 on a line of its own).
                                if (child.name in BLOCKS) text.append('\n')
                            }
                        }
                    }
                    visit(body, inRuby = false)
                    RubyText(text.toString(), ruby)
                }
            }
        } catch (_: IOException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }

        /** Elements that end a line; [texts] marks their end with a line break. */
        private val BLOCKS = setOf("p", "div", "br", "h1", "h2", "h3", "h4", "h5", "h6", "li", "blockquote", "tr", "section", "article")

        private fun ZipFile.text(name: String): String? = getEntry(name)?.let { decodeText(getInputStream(it).use { input -> input.readBytesAtMost(MAX_DOCUMENT_BYTES) }) }

        /** The spine's documents in reading order, as paths in the zip; empty if there is none. */
        private fun spineOf(zip: ZipFile): List<String> {
            val container = zip.text("META-INF/container.xml")?.let(Xhtml::parse) ?: return emptyList()
            val opfPath = container.find("rootfile")?.attributes?.get("full-path") ?: return emptyList()
            val opf = zip.text(opfPath)?.let(Xhtml::parse) ?: return emptyList()
            val base = opfPath.substringBeforeLast('/', "")
            val manifest = opf.findAll("item").mapNotNull { item ->
                val id = item.attributes["id"] ?: return@mapNotNull null
                val href = item.attributes["href"] ?: return@mapNotNull null
                id to resolve(base, href)
            }.toMap()
            return opf.findAll("itemref").mapNotNull { manifest[it.attributes["idref"]] }.filter { zip.getEntry(it) != null }
        }

        private fun bodyOf(document: XNode): XNode = document.find("body") ?: document

        /**
         * Visits the text nodes ttu counts, in reading order, until [visit] returns false: not
         * inside ruby readings (<rt>) or hidden elements.
         */
        private fun walk(node: XNode, visit: (XNode) -> Boolean): Boolean {
            for (child in node.children) {
                if (child.isText) {
                    if (!visit(child)) return false
                } else if (!skipped(child)) {
                    if (!walk(child, visit)) return false
                }
            }
            return true
        }

        private fun skipped(element: XNode) =
            element.name == "rt" || "hidden" in element.attributes || "aria-hidden" in element.attributes

        /** ttu's characters before [target] (and [offset] characters into it, for text). */
        private fun charactersBefore(body: XNode, target: XNode, offset: Int?): Int {
            var count = 0
            fun visit(node: XNode, counted: Boolean): Boolean {
                if (node === target) {
                    if (node.isText) {
                        if (counted) count += count(prefix(node.text!!, offset ?: 0))
                    } else if (offset != null) {
                        // A point between children: count the children before it.
                        node.children.take(offset).forEach { child -> add(child, counted && !skippedNode(child)) { count += it } }
                    }
                    return false
                }
                if (node.isText) {
                    if (counted) count += count(node.text!!)
                    return true
                }
                for (child in node.children) {
                    if (!visit(child, counted && !skippedNode(child))) return false
                }
                return true
            }
            visit(body, true)
            return count
        }

        private fun skippedNode(node: XNode) = !node.isText && skipped(node)

        private fun add(node: XNode, counted: Boolean, sink: (Int) -> Unit) {
            if (!counted) return
            if (node.isText) sink(count(node.text!!)) else node.children.forEach { add(it, !skippedNode(it), sink) }
        }

        /** How many characters ttu counts in [text]. */
        internal fun count(text: String): Int {
            var n = 0
            var i = 0
            while (i < text.length) {
                val cp = text.codePointAt(i)
                if (isCounted(cp)) n++
                i += Character.charCount(cp)
            }
            return n
        }

        /** The first [codePoints] characters of [text] (KOReader's offsets count code points). */
        private fun prefix(text: String, codePoints: Int): String {
            val n = codePoints.coerceIn(0, text.codePointCount(0, text.length))
            return text.substring(0, text.offsetByCodePoints(0, n))
        }

        /** The index in [text] (in code points) of the counted character that has [before] counted ones before it. */
        private fun offsetOf(text: String, before: Int): Int {
            var seen = 0
            var i = 0
            while (i < text.length) {
                val cp = text.codePointAt(i)
                if (isCounted(cp)) {
                    if (seen == before) return text.codePointCount(0, i)
                    seen++
                }
                i += Character.charCount(cp)
            }
            return text.codePointCount(0, text.length)
        }

        /**
         * ttu's `isNotJapaneseRegex`, inverted: digits, Latin letters (either case), ○◯, 々〆〇〻,
         * kana, the long vowel mark, full-width digits and letters, half-width katakana, CJK
         * radicals and ideographs.
         */
        internal fun isCounted(cp: Int): Boolean = when (cp) {
            in '0'.code..'9'.code, in 'A'.code..'Z'.code, in 'a'.code..'z'.code -> true
            0x25CB, 0x25EF, in 0x3005..0x3007, 0x303B -> true
            in 0x3041..0x3096, in 0x309D..0x309E, in 0x30A1..0x30FA, 0x30FC -> true
            in 0xFF10..0xFF19, in 0xFF21..0xFF3A, in 0xFF41..0xFF5A, in 0xFF66..0xFF9D -> true
            in 0x2E80..0x2E99, in 0x2E9B..0x2EF3, in 0x2F00..0x2FD5 -> true
            else -> Character.isIdeographic(cp) && Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN
        }

        private fun resolve(base: String, href: String): String {
            val path = URLDecoder.decode(href.substringBefore('#').replace("+", "%2B"), "UTF-8")
            val parts = (if (base.isEmpty()) emptyList() else base.split('/')).toMutableList()
            for (part in path.split('/')) {
                when (part) {
                    "", "." -> Unit
                    ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
                    else -> parts += part
                }
            }
            return parts.joinToString("/")
        }

        /** UTF-8 unless the XML declaration names another encoding (Shift_JIS in older Japanese books). */
        private fun decodeText(bytes: ByteArray): String {
            val head = String(bytes, 0, minOf(bytes.size, 200), Charsets.ISO_8859_1)
            val name = ENCODING.find(head)?.groupValues?.get(1)
            val charset = name?.let { runCatching { Charset.forName(it) }.getOrNull() } ?: Charsets.UTF_8
            return String(bytes, charset).trimStart(BYTE_ORDER_MARK)
        }
    }
}
