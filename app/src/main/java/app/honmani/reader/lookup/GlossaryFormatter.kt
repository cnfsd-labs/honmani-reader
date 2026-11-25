package app.honmani.reader.lookup

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** One meaning of an entry: its glosses ("to eat", "to live on") in order. */
data class Sense(val glosses: List<String>)

/**
 * Turns a Yomitan glossary (a JSON array of strings, text objects, structured content and
 * images) into plain senses, for Anki cards and the word list. The panel draws the full
 * definitions instead ([StructuredContent], [GlossLayout]).
 *
 * Structured content marks gloss lists with `data-content="glossary"` (JMdict and Jitendex both
 * do); each such list becomes a sense and everything else (examples, notes) is left out.
 * Wiktionary dictionaries (wty) mark one list of all senses with `data-content="glosses"`;
 * each item becomes a sense, without its labels and examples. Content without those marks is
 * flattened to text. Furigana and images are dropped.
 */
object GlossaryFormatter {
    private val json = Json { ignoreUnknownKeys = true }
    private val blockTags = setOf("div", "p", "li", "ul", "ol", "br", "table", "tr", "details", "summary")
    private val skippedTags = setOf("rt", "rp", "img")
    private val blankLines = Regex("\n{2,}")

    /** Parts of a wty sense item that are not its meaning: labels, examples, links. */
    private val skippedInSense = setOf("tags", "tag", "backlink", "related-words", "synonyms", "antonyms")

    /** The definitions in [glossary]; none if it is no JSON array. */
    fun definitions(glossary: String): JsonArray =
        try {
            json.parseToJsonElement(glossary) as? JsonArray
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        } ?: JsonArray(emptyList())

    fun format(glossary: String): List<Sense> = format(definitions(glossary))

    fun format(definitions: JsonArray): List<Sense> =
        definitions.flatMap { senses(it) }.filter { it.glosses.isNotEmpty() }

    private fun senses(definition: JsonElement): List<Sense> = when (definition) {
        is JsonPrimitive -> listOf(Sense(listOfNotNull(definition.contentOrNullIfBlank())))
        // [uninflected term, [rules]]: this entry is an inflected form of another term.
        is JsonArray -> listOfNotNull(
            (definition.firstOrNull() as? JsonPrimitive)?.contentOrNullIfBlank()?.let { Sense(listOf("→ $it")) },
        )
        is JsonObject -> when (definition.string("type")) {
            "text" -> listOf(Sense(listOfNotNull(definition.string("text")?.trim()?.ifEmpty { null })))
            "structured-content" -> structuredSenses(definition["content"])
            else -> emptyList() // images
        }
    }

    private fun structuredSenses(content: JsonElement?): List<Sense> {
        val glossaryLists = mutableListOf<JsonObject>()
        collectGlossaryLists(content, glossaryLists)
        if (glossaryLists.isNotEmpty()) {
            return glossaryLists.map { list -> Sense(items(list).map { flatten(it) }.filter { it.isNotEmpty() }) }
        }
        val senseLists = mutableListOf<JsonObject>()
        collectGlossaryLists(content, senseLists, role = "glosses")
        if (senseLists.isNotEmpty()) {
            return senseLists.flatMap { list ->
                items(list).map { item ->
                    val text = flatten(item, skipDetails = true).lines().joinToString("") { it.trim() }
                    Sense(listOf(text).filter { it.isNotEmpty() })
                }
            }
        }
        val text = flatten(content)
        return listOf(Sense(text.lines().map { it.trim() }.filter { it.isNotEmpty() }))
    }

    private fun collectGlossaryLists(node: JsonElement?, into: MutableList<JsonObject>, role: String = "glossary") {
        when (node) {
            is JsonArray -> node.forEach { collectGlossaryLists(it, into, role) }
            is JsonObject -> {
                val data = node["data"] as? JsonObject
                if (data?.string("content") == role) into += node else collectGlossaryLists(node["content"], into, role)
            }
            else -> Unit
        }
    }

    /** The `li` children of a list, or the list itself if it has none. */
    private fun items(list: JsonObject): List<JsonElement> {
        val children = when (val content = list["content"]) {
            is JsonArray -> content.toList()
            null -> emptyList()
            else -> listOf(content)
        }
        val listItems = children.filter { (it as? JsonObject)?.string("tag") == "li" }
        return listItems.ifEmpty { listOf(list) }
    }

    /** [skipDetails]: leave out examples (`details`) and the parts in [skippedInSense]. */
    private fun flatten(node: JsonElement?, skipDetails: Boolean = false): String =
        buildString { appendText(node, skipDetails) }.replace(blankLines, "\n").trim()

    private fun StringBuilder.appendText(node: JsonElement?, skipDetails: Boolean) {
        when (node) {
            is JsonPrimitive -> if (node.isString) append(node.content)
            is JsonArray -> node.forEach { appendText(it, skipDetails) }
            is JsonObject -> {
                val tag = node.string("tag")
                if (tag in skippedTags) return
                if (skipDetails && (tag == "details" || (node["data"] as? JsonObject)?.string("content") in skippedInSense)) return
                val block = tag in blockTags
                if (block && isNotEmpty() && last() != '\n') append('\n')
                appendText(node["content"], skipDetails)
                if (block && isNotEmpty() && last() != '\n') append('\n')
            }
            else -> Unit
        }
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonPrimitive.contentOrNullIfBlank(): String? =
        if (isString) content.trim().ifEmpty { null } else null
}
