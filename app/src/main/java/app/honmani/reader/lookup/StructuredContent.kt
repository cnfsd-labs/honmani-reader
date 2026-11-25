package app.honmani.reader.lookup

import java.net.URLDecoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** A piece of a dictionary definition, ready to draw: text, or an element with children. */
sealed interface GlossNode {
    data class Text(val text: String) : GlossNode

    data class Element(
        val tag: String,
        val children: List<GlossNode>,
        /** Yomitan's `data-content`, which says what the element is ("sense", "example-keyword"). */
        val role: String? = null,
        /** `data-class`, such as "tag" for part-of-speech and usage labels. */
        val cls: String? = null,
        /** For lists: the marker the dictionary chose for this item ("①"), if any. */
        val marker: String? = null,
        /** For links within the dictionary: the word to look up. */
        val query: String? = null,
    ) : GlossNode {
        val isBlock: Boolean get() = tag in BLOCK_TAGS
    }

    companion object {
        val BLOCK_TAGS = setOf("div", "p", "ul", "ol", "li", "table", "tr", "details", "summary", "br")
    }
}

/**
 * Reads a Yomitan glossary (a JSON array of definitions) into [GlossNode] trees, one per
 * definition. Plain strings and text objects become text; structured content keeps its
 * elements, with what Honmani draws differently (roles, tags, list markers, links to other
 * words). Left out: images (dictionary media isn't imported yet), source attributions,
 * Jitendex's spelling tables, which repeat what the headword shows, and from Wiktionary
 * dictionaries (wty) the word origin, the links to the web pages and the "2 例" summaries
 * (the examples themselves stay).
 */
object StructuredContent {
    /** Roles of parts that are not shown. */
    private val skippedRoles = setOf(
        "attribution", "attribution-footnote", "forms", "graphic", "backlink", "preamble", "summary-entry",
    )
    private val skippedTags = setOf("img", "rp", "script", "style")

    fun parse(glossary: String): List<GlossNode> = parse(GlossaryFormatter.definitions(glossary))

    fun parse(definitions: JsonArray): List<GlossNode> = definitions.mapNotNull(::definition)

    private fun definition(element: JsonElement): GlossNode? = when (element) {
        is JsonPrimitive -> element.text()?.let { block(GlossNode.Text(it)) }
        // [uninflected term, [rules]]: an inflected form of another word.
        is JsonArray -> (element.firstOrNull() as? JsonPrimitive)?.text()?.let { word ->
            block(GlossNode.Text("⟶ "), GlossNode.Element("a", listOf(GlossNode.Text(word)), query = word))
        }
        is JsonObject -> when (element.string("type")) {
            "text" -> element.string("text")?.trim()?.ifEmpty { null }?.let { block(GlossNode.Text(it)) }
            "structured-content" -> nodes(element["content"]).takeIf { it.isNotEmpty() }?.let {
                GlossNode.Element("div", it)
            }
            else -> null
        }
    }

    private fun block(vararg children: GlossNode) = GlossNode.Element("div", children.toList())

    private fun nodes(content: JsonElement?): List<GlossNode> = when (content) {
        null -> emptyList()
        is JsonPrimitive -> listOfNotNull(content.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() }?.let(GlossNode::Text))
        is JsonArray -> content.flatMap(::nodes)
        is JsonObject -> listOfNotNull(element(content))
    }

    private fun element(obj: JsonObject): GlossNode? {
        val tag = obj.string("tag") ?: return null
        if (tag in skippedTags) return null
        val data = obj["data"] as? JsonObject
        val role = data?.string("content")
        if (role in skippedRoles) return null
        val children = nodes(obj["content"])
        if (children.isEmpty() && tag != "br") return null
        return GlossNode.Element(
            tag = tag,
            children = children,
            role = role,
            cls = data?.string("class"),
            marker = ((obj["style"] as? JsonObject)?.string("listStyleType"))?.let(::marker),
            query = obj.string("href")?.let(::query),
        )
    }

    /** CSS `listStyleType` such as `"①"` (a string in quotes) as the marker text; null for keywords. */
    private fun marker(style: String): String? =
        style.trim().takeIf { it.length >= 2 && it.first() == '"' && it.last() == '"' }?.drop(1)?.dropLast(1)

    /** The word a dictionary link looks up (`?query=猫&…`); null for links to web pages. */
    internal fun query(href: String): String? {
        if (!href.startsWith("?")) return null
        val value = href.removePrefix("?").split('&')
            .firstOrNull { it.startsWith("query=") }
            ?.removePrefix("query=")
            ?: return null
        return try {
            URLDecoder.decode(value, "UTF-8").trim().ifEmpty { null }
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonPrimitive.text(): String? = if (isString) content.trim().ifEmpty { null } else null
}
