package app.honmani.reader.lookup

/** Something inside a line of a definition. */
sealed interface GlossItem {
    data class Text(
        val text: String,
        val bold: Boolean = false,
        /** The word itself in an example sentence. */
        val keyword: Boolean = false,
        /** Another word to look up when tapped. */
        val query: String? = null,
    ) : GlossItem

    /** Kanji with furigana. */
    data class Ruby(val base: String, val reading: String, val keyword: Boolean = false, val query: String? = null) : GlossItem

    /** A label such as "noun", "transitive" or "abbr.". */
    data class Tag(val text: String) : GlossItem {
        companion object {
            /** Labels next to each other as drawn in one chip: "cop · aux-v", one separator each, no stray spaces. */
            fun join(tags: List<Tag>): String = tags.map { it.text.trim() }.filter { it.isNotEmpty() }.joinToString(" · ")
        }
    }
}

enum class GlossStyle {
    /** Meanings and everything without a special role. */
    GLOSS,

    /** An example sentence in Japanese. */
    EXAMPLE,

    /** Its translation. */
    TRANSLATION,

    /** Notes, word origin, cross-references, antonyms. */
    NOTE,
}

/** One line of a definition as drawn: its items, how it looks, and its list marker and depth. */
data class GlossLine(
    val items: List<GlossItem>,
    val style: GlossStyle = GlossStyle.GLOSS,
    /** List nesting, for indentation. */
    val depth: Int = 0,
    /** "①", "1." or "•", on the first line of a list item. */
    val marker: String? = null,
) {
    val hasRuby: Boolean get() = items.any { it is GlossItem.Ruby }

    /**
     * The line with its leading labels ("noun", "transitive") moved after the text, as drawn:
     * readers look for the meaning first. A line of labels only stays as it is.
     */
    fun tagsLast(): GlossLine {
        val leading = items.takeWhile { it is GlossItem.Tag || (it is GlossItem.Text && it.text.isBlank()) }
        val rest = items.drop(leading.size)
        val tags = leading.filterIsInstance<GlossItem.Tag>()
        if (tags.isEmpty() || rest.isEmpty()) return this
        return copy(items = rest + tags)
    }
}

/**
 * Lays out [GlossNode] trees as [GlossLine]s: block elements start lines, inline content fills
 * them. Glossary lists become one line ("to eat; to live on"), labels such as "Note" lead the
 * line that follows them, and examples, notes and references get their own styles.
 */
object GlossLayout {
    private val noteRoles = setOf(
        "xref", "sense-note", "info-gloss", "lang-source", "antonym", "redirect-glossary",
        // Wiktionary (wty): where an example sentence is from.
        "example-sentence-c",
    )

    /** Wiktionary's (wty) word lists, labelled by its stylesheet; drawn as a line of words to tap. */
    private val wordLists = mapOf("synonyms-list" to "類義語", "antonyms-list" to "対義語")

    fun lines(definitions: List<GlossNode>): List<GlossLine> {
        val out = Builder()
        definitions.forEach { out.block(it, Context()) }
        out.flush()
        val lines = out.lines.filter { line -> line.items.any { it !is GlossItem.Text || it.text.isNotBlank() } }
        return mergeTagLines(lines)
    }

    /**
     * A line of tags only ("① derogatory") followed by an unmarked line of the same depth (its
     * meanings) becomes one line, so labels sit in front of what they describe.
     */
    private fun mergeTagLines(lines: List<GlossLine>): List<GlossLine> {
        val merged = mutableListOf<GlossLine>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val next = lines.getOrNull(i + 1)
            if (next != null && line.items.all { it is GlossItem.Tag } && next.marker == null &&
                next.depth == line.depth && next.style == line.style
            ) {
                merged += line.copy(items = line.items + next.items)
                i += 2
            } else {
                merged += line
                i++
            }
        }
        return merged
    }

    private data class Context(
        val style: GlossStyle = GlossStyle.GLOSS,
        val depth: Int = 0,
        val keyword: Boolean = false,
        val bold: Boolean = false,
        val query: String? = null,
    )

    private class Builder {
        val lines = mutableListOf<GlossLine>()
        private var items = mutableListOf<GlossItem>()
        private var style = GlossStyle.GLOSS
        private var depth = 0
        private var marker: String? = null

        /** Text of a "…-label" block, put in front of the next line. */
        private var label: String? = null

        fun flush() {
            if (items.isNotEmpty()) {
                lines += GlossLine(items.trimmed(), style, depth, marker)
                marker = null
            }
            items = mutableListOf()
        }

        private fun start(context: Context, newMarker: String? = null) {
            flush()
            style = context.style
            depth = context.depth
            if (newMarker != null) marker = newMarker
            label?.let {
                items += GlossItem.Text("$it: ", bold = true)
                label = null
            }
        }

        private fun add(item: GlossItem, context: Context) {
            if (items.isEmpty() && (style != context.style || depth != context.depth || label != null)) {
                start(context)
            }
            items += item
        }

        fun block(node: GlossNode, parent: Context) {
            when (node) {
                is GlossNode.Text -> add(GlossItem.Text(node.text, parent.bold, parent.keyword, parent.query), parent)
                is GlossNode.Element -> element(node, parent)
            }
        }

        private fun element(node: GlossNode.Element, parent: Context) {
            val context = contextOf(node, parent)
            val role = node.role.orEmpty()
            when {
                node.tag == "br" -> flush()
                role.endsWith("-label") && node.isBlock -> {
                    flush()
                    label = textOf(node).trim().ifEmpty { null }
                }
                role == "glossary" -> {
                    start(context)
                    glossary(node, context)
                    flush()
                }
                node.tag == "ruby" -> add(ruby(node, context), context)
                node.isTag -> add(GlossItem.Tag(textOf(node).trim()), context)
                role in wordLists -> wordList(node, wordLists.getValue(role), context)
                // "See also", "Antonym": followed by the word.
                role == "reference-label" -> add(GlossItem.Text(textOf(node).trim() + " ", bold = true), context)
                node.tag == "ul" || node.tag == "ol" -> list(node, context)
                node.isBlock -> {
                    start(context)
                    node.children.forEach { block(it, context) }
                    flush()
                }
                else -> node.children.forEach { block(it, context) }
            }
        }

        /** Items of a glossary list on one line, separated by semicolons. */
        private fun glossary(node: GlossNode.Element, context: Context) {
            val entries = if (node.tag == "ul" || node.tag == "ol") {
                node.children.filterIsInstance<GlossNode.Element>().filter { it.tag == "li" }
            } else {
                listOf(node)
            }
            entries.forEachIndexed { i, entry ->
                if (i > 0) items += GlossItem.Text("; ")
                entry.children.forEach { inline(it, context) }
            }
        }

        /** Inline content only: a block inside is read as text. */
        private fun inline(node: GlossNode, context: Context) {
            when (node) {
                is GlossNode.Text -> items += GlossItem.Text(node.text, context.bold, context.keyword, context.query)
                is GlossNode.Element -> {
                    val inner = contextOf(node, context)
                    when {
                        node.tag == "ruby" -> items += ruby(node, inner)
                        node.isTag -> items += GlossItem.Tag(textOf(node).trim())
                        else -> node.children.forEach { inline(it, inner) }
                    }
                }
            }
        }

        /** "類義語: 蒼、碧" on a line of its own, each word looked up when tapped. */
        private fun wordList(node: GlossNode.Element, label: String, context: Context) {
            val words = node.children.filterIsInstance<GlossNode.Element>()
                .map { textOf(it).trim() }
                .filter { it.isNotEmpty() }
            if (words.isEmpty()) return
            start(context.copy(style = GlossStyle.NOTE))
            items += GlossItem.Text("$label: ", bold = true)
            words.forEachIndexed { i, word ->
                if (i > 0) items += GlossItem.Text("、")
                items += GlossItem.Text(word, query = word)
            }
            flush()
        }

        private fun list(node: GlossNode.Element, context: Context) {
            flush()
            var number = 0
            node.children.forEach { child ->
                if (child is GlossNode.Element && child.tag == "li") {
                    number++
                    val marker = child.marker ?: when {
                        node.tag == "ol" -> "$number."
                        child.role == null -> "•"
                        else -> null
                    }
                    val itemContext = contextOf(child, context).copy(depth = context.depth + if (marker != null) 1 else 0)
                    start(itemContext, marker)
                    child.children.forEach { block(it, itemContext) }
                    flush()
                } else {
                    block(child, context)
                }
            }
        }

        private fun ruby(node: GlossNode.Element, context: Context): GlossItem.Ruby {
            val base = StringBuilder()
            val reading = StringBuilder()
            node.children.forEach { child ->
                if (child is GlossNode.Element && child.tag == "rt") {
                    reading.append(child.children.joinToString("") { textOf(it) })
                } else {
                    base.append(textOf(child))
                }
            }
            return GlossItem.Ruby(base.toString(), reading.toString(), context.keyword, context.query)
        }

        private fun contextOf(node: GlossNode.Element, parent: Context): Context {
            val role = node.role
            return parent.copy(
                style = when {
                    role == "example-sentence-a" -> GlossStyle.EXAMPLE
                    role == "example-sentence-b" -> GlossStyle.TRANSLATION
                    role in noteRoles -> GlossStyle.NOTE
                    else -> parent.style
                },
                keyword = parent.keyword || role == "example-keyword",
                bold = parent.bold || node.tag == "b" || node.tag == "strong" || role == "bold-text",
                query = node.query ?: parent.query,
            )
        }

        private fun List<GlossItem>.trimmed(): List<GlossItem> {
            val list = toMutableList()
            (list.firstOrNull() as? GlossItem.Text)?.let { list[0] = it.copy(text = it.text.trimStart()) }
            (list.lastOrNull() as? GlossItem.Text)?.let { list[list.lastIndex] = it.copy(text = it.text.trimEnd()) }
            return list
        }
    }

    /** A label: `data-class="tag"` (Jitendex, JMdict) or `data-content="tag"` (Wiktionary). */
    private val GlossNode.Element.isTag: Boolean get() = cls == "tag" || (role == "tag" && !isBlock)

    /** All text under [node], without furigana. */
    private fun textOf(node: GlossNode): String = when (node) {
        is GlossNode.Text -> node.text
        is GlossNode.Element -> if (node.tag == "rt") "" else node.children.joinToString("") { textOf(it) }
    }
}
