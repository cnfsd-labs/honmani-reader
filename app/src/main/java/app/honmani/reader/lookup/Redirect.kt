package app.honmani.reader.lookup

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.URLDecoder

/**
 * Pointer entries, which only name another entry of the same dictionary:
 * - Yomitan's "form of" entries, whose glossary is only `["生れる", ["past …"]]` items: the
 *   Japanese Wiktionary (wty) has one for most conjugated forms (生れた, 食べた).
 * - Jitendex and JMdict for Yomitan list rare spellings (生れる) as entries whose sequence number
 *   is the usual spelling's, negated, and whose glossary is only a link to it ("⟶ 生まれる";
 *   Jitendex adds a form-of item).
 */
object Redirect {
    private const val QUERY = "?query="

    /** Words in wty's notes on conjugated forms ("past completive continuative"), not on other spellings ("transliteration"). */
    private val CONJUGATIONS = setOf("imperfective", "continuative", "hypothetical", "imperative", "conclusive", "attributive", "evidential")

    /** The written form [word] an entry points to; [conjugated] if as the lemma of a conjugated form. */
    data class Target(val word: String, val conjugated: Boolean)

    /** What an entry with [sequence] and [glossary] (JSON) points to; null if it is a real entry. */
    fun target(sequence: Long?, glossary: String): Target? {
        // Most entries are neither, and reading all their definitions is most of a lookup's work.
        if ((sequence == null || sequence >= 0) && !opensWithFormOf(glossary)) return null
        val items = try {
            Json.parseToJsonElement(glossary) as? JsonArray
        } catch (_: SerializationException) {
            null
        } ?: return null
        val formOf = items.map(::formOfTarget)
        if (items.isNotEmpty() && formOf.all { it != null }) return Target(formOf.first()!!, items.all(::isConjugation))
        if (sequence == null || sequence >= 0) return null
        val word = formOf.firstNotNullOfOrNull { it } ?: items.firstNotNullOfOrNull(::linkTarget)
        return word?.let { Target(it, conjugated = false) }
    }

    /** Whether the first definition in [glossary] is an array, as a form-of item is. */
    private fun opensWithFormOf(glossary: String): Boolean {
        var i = 0
        repeat(2) {
            while (i < glossary.length && glossary[i].isWhitespace()) i++
            if (i >= glossary.length || glossary[i++] != '[') return false
        }
        return true
    }

    /** Whether a form-of item's notes say the form is a conjugated one. */
    private fun isConjugation(item: JsonElement): Boolean = ((item as JsonArray)[1] as JsonArray)
        .any { note -> (note as? JsonPrimitive)?.content.orEmpty().split(' ').any(CONJUGATIONS::contains) }

    /** The term of a form-of item: [term, [rules or notes]]. */
    private fun formOfTarget(item: JsonElement): String? =
        (item as? JsonArray)?.takeIf { it.size == 2 && it[1] is JsonArray }
            ?.let { (it[0] as? JsonPrimitive)?.takeIf { term -> term.isString }?.content }
            ?.takeIf { it.isNotBlank() }

    /** The query of the first `?query=` link in structured content. */
    private fun linkTarget(element: JsonElement): String? = when (element) {
        is JsonArray -> element.firstNotNullOfOrNull(::linkTarget)
        is JsonObject -> {
            val href = (element["href"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            if ((element["tag"] as? JsonPrimitive)?.content == "a" && href?.startsWith(QUERY) == true) {
                URLDecoder.decode(href.removePrefix(QUERY).substringBefore('&'), "UTF-8").takeIf { it.isNotBlank() }
            } else {
                element["content"]?.let(::linkTarget)
            }
        }
        else -> null
    }
}
