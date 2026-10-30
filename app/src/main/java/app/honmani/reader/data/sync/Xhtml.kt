package app.honmani.reader.data.sync

/**
 * A node of a leniently parsed XHTML (or OPF) document: an element ([name] in lower case, without
 * a namespace prefix) or a text node ([text], entities decoded).
 */
internal class XNode(val name: String?, val text: String?, val attributes: Map<String, String> = emptyMap()) {
    var parent: XNode? = null
        private set
    val children = mutableListOf<XNode>()

    val isText: Boolean get() = name == null

    /** Elements above this one; the document is 0. */
    var depth: Int = 0
        private set

    fun add(child: XNode) {
        child.parent = this
        child.depth = depth + 1
        children += child
    }

    /** The first element named [name] here or below, depth first. */
    fun find(name: String): XNode? {
        if (this.name == name) return this
        for (child in children) child.find(name)?.let { return it }
        return null
    }

    fun findAll(name: String): List<XNode> = buildList {
        fun visit(node: XNode) {
            if (node.name == name) add(node)
            node.children.forEach(::visit)
        }
        visit(this@XNode)
    }
}

/**
 * A forgiving XHTML reader for book pages: EPUB pages often carry a DOCTYPE or HTML entities
 * that a strict XML parser rejects. It keeps what KOReader's positions need (elements, their
 * nesting, text) and never fetches anything.
 *
 * Whitespace-only text between blocks (line breaks from formatting) is dropped, as KOReader's
 * CREngine drops it, so text node indexes match its XPointers.
 */
internal object Xhtml {
    /**
     * The deepest element nesting kept. Books nest a few dozen levels; a hostile page with
     * 100,000 open divs would overflow the stack of every recursive walk over the tree.
     */
    internal const val MAX_DEPTH = 256

    private val voidElements = setOf(
        "area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "param", "source", "track", "wbr",
    )
    private val blockElements = setOf(
        "html", "head", "body", "div", "section", "article", "aside", "nav", "header", "footer", "main", "p",
        "h1", "h2", "h3", "h4", "h5", "h6", "ul", "ol", "li", "dl", "dt", "dd", "table", "thead", "tbody",
        "tfoot", "tr", "td", "th", "blockquote", "figure", "figcaption", "hr", "pre", "package", "manifest",
        "spine", "metadata", "container", "rootfiles",
    )
    private val attribute = Regex("""([^\s=/>]+)\s*(?:=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+)))?""")
    private val entity = Regex("""&(#[0-9]+|#[xX][0-9a-fA-F]+|[A-Za-z][A-Za-z0-9]*);""")
    private val namedEntities = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
        "ensp" to " ", "emsp" to " ", "thinsp" to " ", "hellip" to "…", "mdash" to "—",
        "ndash" to "–", "lsquo" to "‘", "rsquo" to "’", "ldquo" to "“", "rdquo" to "”", "middot" to "·",
        "copy" to "©", "times" to "×",
    )

    fun parse(source: String): XNode {
        val root = XNode("#document", null)
        var current = root
        val text = StringBuilder()
        var i = 0

        fun flushText() {
            if (text.isEmpty()) return
            val raw = text.toString()
            text.clear()
            val blank = raw.all { it == ' ' || it == '\t' || it == '\n' || it == '\r' || it == '\u000c' }
            if (blank && (current.name in blockElements || '\n' in raw || current === root)) return
            val decoded = decode(raw)
            val last = current.children.lastOrNull()
            if (last != null && last.isText) {
                current.children.removeAt(current.children.lastIndex)
                current.add(XNode(null, last.text + decoded))
            } else {
                current.add(XNode(null, decoded))
            }
        }

        while (i < source.length) {
            val c = source[i]
            if (c != '<') {
                text.append(c)
                i++
                continue
            }
            when {
                source.startsWith("<!--", i) -> {
                    flushText()
                    i = source.indexOf("-->", i + 4).let { if (it < 0) source.length else it + 3 }
                }
                source.startsWith("<![CDATA[", i) -> {
                    val end = source.indexOf("]]>", i + 9).let { if (it < 0) source.length else it }
                    text.append(source, i + 9, end)
                    i = if (end >= source.length) end else end + 3
                }
                source.startsWith("<!", i) || source.startsWith("<?", i) -> {
                    flushText()
                    i = tagEnd(source, i).let { if (it < 0) source.length else it + 1 }
                }
                source.startsWith("</", i) -> {
                    flushText()
                    val end = source.indexOf('>', i).let { if (it < 0) source.length else it }
                    val name = localName(source.substring(i + 2, end).trim())
                    var node: XNode? = current
                    while (node != null && node.name != name) node = node.parent
                    if (node != null && node !== root) current = node.parent ?: root
                    i = if (end >= source.length) end else end + 1
                }
                else -> {
                    val end = tagEnd(source, i)
                    if (end < 0) {
                        text.append(c)
                        i++
                        continue
                    }
                    flushText()
                    val inside = source.substring(i + 1, end)
                    val selfClosing = inside.endsWith("/")
                    val body = inside.removeSuffix("/")
                    val nameEnd = body.indexOfFirst { it.isWhitespace() }.let { if (it < 0) body.length else it }
                    val name = localName(body.substring(0, nameEnd))
                    if (name.isEmpty()) {
                        text.append(c)
                        i++
                        continue
                    }
                    val attributes = attribute.findAll(body.substring(nameEnd)).associate { match ->
                        val value = match.groupValues[2].ifEmpty { match.groupValues[3] }.ifEmpty { match.groupValues[4] }
                        localName(match.groupValues[1]) to decode(value)
                    }
                    val element = XNode(name, null, attributes)
                    current.add(element)
                    // Past MAX_DEPTH the element stays a leaf: the tree walks recurse once per level.
                    if (!selfClosing && name !in voidElements && element.depth < MAX_DEPTH) current = element
                    i = end + 1
                }
            }
        }
        flushText()
        return root
    }

    /** The `>` closing the tag that starts at [start], skipping quoted attribute values; -1 if none. */
    private fun tagEnd(source: String, start: Int): Int {
        var quote: Char? = null
        var i = start + 1
        while (i < source.length) {
            val c = source[i]
            when {
                quote != null -> if (c == quote) quote = null
                c == '"' || c == '\'' -> quote = c
                c == '>' -> return i
            }
            i++
        }
        return -1
    }

    private fun localName(name: String): String = name.substringAfter(':').lowercase()

    fun decode(text: String): String = if ('&' !in text) {
        text
    } else {
        entity.replace(text) { match ->
            val name = match.groupValues[1]
            when {
                name.startsWith("#x") || name.startsWith("#X") -> name.substring(2).toIntOrNull(16)?.let(::codePoint)
                name.startsWith("#") -> name.substring(1).toIntOrNull()?.let(::codePoint)
                else -> namedEntities[name]
            } ?: match.value
        }
    }

    private fun codePoint(value: Int): String? =
        if (Character.isValidCodePoint(value)) String(Character.toChars(value)) else null
}
