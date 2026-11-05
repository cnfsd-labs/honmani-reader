package app.honmani.reader.anki

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** A note type of AnkiDroid as the settings and the exporter see it. */
data class AnkiNoteTypeInfo(val id: Long, val name: String, val fields: List<String>)

/**
 * What Honmani can put into a field of the reader's own note type ([CardStyle.CUSTOM]). Each
 * source is the text of one of Honmani's own fields ([AnkiCard], [SentenceCard]), so a card
 * reads the same whichever note type it lands in.
 */
enum class CardSource {
    /** The word as written. */
    WORD,

    /** The reading in kana. */
    READING,

    /** The word with `<ruby>` furigana. */
    FURIGANA,

    /** The word with Anki's furigana brackets (`漢字[かんじ]`). */
    FURIGANA_PLAIN,

    /** The meanings, a numbered list if there are several. */
    MEANING,

    /** The sentence with the looked-up text in `<b>`. */
    SENTENCE_MARKED,

    /** The sentence as it is, or the word if none was stored. */
    SENTENCE_PLAIN,

    BOOK,
    WORD_AUDIO,
    SENTENCE_AUDIO,

    /** Pitch accent as HTML. */
    PITCH,
    FREQUENCY,
    ;

    /** This source's text for [content]; the audio sources are the strings given. */
    fun render(content: CardContent, wordAudio: String = "", sentenceAudio: String = ""): String =
        renderAll(content, wordAudio, sentenceAudio).getValue(this)

    companion object {
        /** Every source's text for [content], worked out once. */
        fun renderAll(content: CardContent, wordAudio: String = "", sentenceAudio: String = ""): Map<CardSource, String> {
            val honmani = AnkiCard.FIELDS.zip(AnkiCard.fields(content, wordAudio, sentenceAudio)).toMap()
            return mapOf(
                WORD to honmani.getValue("Word"),
                READING to honmani.getValue("Reading"),
                FURIGANA to honmani.getValue("Furigana"),
                FURIGANA_PLAIN to honmani.getValue(AnkiCard.PLAIN_FURIGANA),
                MEANING to honmani.getValue("Meaning"),
                SENTENCE_MARKED to honmani.getValue("Sentence"),
                SENTENCE_PLAIN to SentenceCard.sentence(content),
                BOOK to honmani.getValue("Book"),
                WORD_AUDIO to wordAudio,
                SENTENCE_AUDIO to sentenceAudio,
                PITCH to honmani.getValue("Pitch"),
                FREQUENCY to honmani.getValue("Frequency"),
            )
        }

        fun fromName(name: String?): CardSource? = entries.firstOrNull { it.name == name }
    }
}

/**
 * Which [CardSource] fills which field of the reader's note type: field name to source. A field
 * has at most one source, a source may fill several fields, and fields left out stay empty.
 */
data class FieldMapping(val sources: Map<String, CardSource>) {
    /** The field the word goes into; every card needs one. */
    fun wordField(fieldNames: List<String>): String? = fieldFor(CardSource.WORD, fieldNames)

    /** The first of [fieldNames] that [source] fills. */
    fun fieldFor(source: CardSource, fieldNames: List<String>): String? =
        fieldNames.firstOrNull { sources[it] == source }

    fun uses(source: CardSource, fieldNames: List<String>): Boolean = fieldFor(source, fieldNames) != null

    /** The same mapping without fields that [fieldNames] doesn't have (a note type that changed). */
    fun restrictedTo(fieldNames: List<String>): FieldMapping = FieldMapping(sources.filterKeys { it in fieldNames })

    /** The field values for [fieldNames], in their order; unmapped fields are empty. */
    fun fill(fieldNames: List<String>, content: CardContent, wordAudio: String, sentenceAudio: String): List<String> {
        val rendered = CardSource.renderAll(content, wordAudio, sentenceAudio)
        return fieldNames.map { name -> sources[name]?.let(rendered::getValue).orEmpty() }
    }

    companion object {
        val EMPTY = FieldMapping(emptyMap())

        /** The mappings of several note types as stored in the settings: `{"Note type":{"Field":"SOURCE"}}`. */
        fun toJson(byNoteType: Map<String, FieldMapping>): String = JsonObject(
            byNoteType.mapValues { (_, mapping) ->
                JsonObject(mapping.sources.mapValues { JsonPrimitive(it.value.name) })
            },
        ).toString()

        /** Reads [toJson]'s text; anything unreadable gives nothing, unknown sources are dropped. */
        fun fromJson(text: String?): Map<String, FieldMapping> {
            if (text.isNullOrBlank()) return emptyMap()
            return try {
                Json.parseToJsonElement(text).jsonObject.mapValues { (_, fields) ->
                    FieldMapping(
                        fields.jsonObject.mapNotNull { (field, source) ->
                            CardSource.fromName(source.jsonPrimitive.contentOrNull)?.let { field to it }
                        }.toMap(),
                    )
                }
            } catch (_: IllegalArgumentException) {
                // Not JSON or not the shape we wrote (a damaged backup): start with no mappings.
                emptyMap()
            }
        }
    }
}

/** The note types [MappingPresets] knows by their field names. */
enum class PresetName(val label: String) {
    LAPIS("Lapis"),
    JPMN("Marvin’s Mining Note"),
}

/** A guessed [mapping]; [preset] is set if the note type looks like a well-known one. */
data class PresetGuess(val mapping: FieldMapping, val preset: PresetName?)

/** Guesses a [FieldMapping] from a note type's field names, so most readers only press Save. */
object MappingPresets {
    private val LAPIS = mapOf(
        "expression" to CardSource.WORD,
        "expressionreading" to CardSource.READING,
        // Lapis writes its furigana in Anki's bracket notation.
        "expressionfurigana" to CardSource.FURIGANA_PLAIN,
        "sentence" to CardSource.SENTENCE_MARKED,
        "sentencefurigana" to CardSource.SENTENCE_PLAIN,
        "maindefinition" to CardSource.MEANING,
        "expressionaudio" to CardSource.WORD_AUDIO,
        "sentenceaudio" to CardSource.SENTENCE_AUDIO,
        "miscinfo" to CardSource.BOOK,
        "frequency" to CardSource.FREQUENCY,
    )

    private val JPMN = mapOf(
        "word" to CardSource.WORD,
        "wordreading" to CardSource.FURIGANA_PLAIN,
        "wordreadinghiragana" to CardSource.READING,
        "wordaudio" to CardSource.WORD_AUDIO,
        "sentence" to CardSource.SENTENCE_MARKED,
        "sentencereading" to CardSource.SENTENCE_PLAIN,
        "sentenceaudio" to CardSource.SENTENCE_AUDIO,
        "primarydefinition" to CardSource.MEANING,
        "frequency" to CardSource.FREQUENCY,
    )

    private val GENERIC = mapOf(
        "word" to CardSource.WORD,
        "expression" to CardSource.WORD,
        "front" to CardSource.WORD,
        "vocab" to CardSource.WORD,
        "vocabulary" to CardSource.WORD,
        "term" to CardSource.WORD,
        "単語" to CardSource.WORD,
        "表現" to CardSource.WORD,
        "reading" to CardSource.READING,
        "expressionreading" to CardSource.READING,
        "kana" to CardSource.READING,
        "読み" to CardSource.READING,
        "よみ" to CardSource.READING,
        "meaning" to CardSource.MEANING,
        "meanings" to CardSource.MEANING,
        "definition" to CardSource.MEANING,
        "definitions" to CardSource.MEANING,
        "translation" to CardSource.MEANING,
        "back" to CardSource.MEANING,
        "sentence" to CardSource.SENTENCE_MARKED,
        "example" to CardSource.SENTENCE_MARKED,
        "例文" to CardSource.SENTENCE_MARKED,
        "book" to CardSource.BOOK,
        "source" to CardSource.BOOK,
        "audio" to CardSource.WORD_AUDIO,
        "wordaudio" to CardSource.WORD_AUDIO,
        "expressionaudio" to CardSource.WORD_AUDIO,
        "sentenceaudio" to CardSource.SENTENCE_AUDIO,
        "pitch" to CardSource.PITCH,
        "pitchaccent" to CardSource.PITCH,
        "frequency" to CardSource.FREQUENCY,
    )

    fun guess(fieldNames: List<String>): FieldMapping = detect(fieldNames).mapping

    fun detect(fieldNames: List<String>): PresetGuess {
        val keys = fieldNames.map { key(it) }.toSet()
        val preset = when {
            "expression" in keys && "expressionreading" in keys && ("maindefinition" in keys || "sentencefurigana" in keys) ->
                PresetName.LAPIS
            "word" in keys && ("wordreadinghiragana" in keys || "primarydefinition" in keys) -> PresetName.JPMN
            else -> null
        }
        val sources = mutableMapOf<String, CardSource>()
        for (name in fieldNames) {
            val k = key(name)
            val source = when (preset) {
                PresetName.LAPIS -> LAPIS[k] ?: if (k == "glossary" && "maindefinition" !in keys) CardSource.MEANING else null
                PresetName.JPMN -> JPMN[k]
                null -> GENERIC[k]
            }
            if (source != null) sources[name] = source
        }
        return PresetGuess(FieldMapping(sources), preset)
    }

    private fun key(name: String) = name.trim().lowercase()
}

/** Why the reader's own note type can't take cards. */
enum class CustomProblem {
    /** [CardStyle.CUSTOM] is on but no note type was ever chosen (for example after a restore). */
    NOT_CHOSEN,

    /** The note type is not in AnkiDroid any more. */
    MISSING,

    /** No field of the note type is set to take the word. */
    NO_WORD_FIELD,
}

/** The reader's own note type in AnkiDroid, with its mapping, ready to take cards. */
class CustomNoteType(val id: Long, val name: String, val fieldNames: List<String>, val mapping: FieldMapping) {
    val wordIndex: Int = fieldNames.indexOfFirst { mapping.sources[it] == CardSource.WORD }
    val readingIndex: Int? = fieldNames.indexOfFirst { mapping.sources[it] == CardSource.READING }.takeIf { it >= 0 }

    val usesWordAudio = mapping.uses(CardSource.WORD_AUDIO, fieldNames)
    val usesSentenceAudio = mapping.uses(CardSource.SENTENCE_AUDIO, fieldNames)

    fun fields(content: CardContent, wordAudio: String, sentenceAudio: String): List<String> =
        mapping.fill(fieldNames, content, wordAudio, sentenceAudio)

    /** What identifies [content] among the notes: the word and, if the type has a reading field, the reading. */
    fun key(expression: String, reading: String): Pair<String, String> =
        AnkiCard.escape(expression) to
            (if (readingIndex != null) AnkiCard.escape(reading.ifEmpty { expression }) else "")

    fun key(content: CardContent): Pair<String, String> = key(content.expression, content.reading)

    /** The key of an existing note's [fields], as [key] makes it. */
    fun key(fields: List<String>): Pair<String, String> =
        fields.getOrNull(wordIndex).orEmpty() to (readingIndex?.let { fields.getOrNull(it) }.orEmpty())
}

/** Finds the reader's own note type; shared by adding cards and checking against Anki. */
sealed interface CustomResolution {
    data class Ready(val type: CustomNoteType) : CustomResolution

    data class Unusable(val name: String, val problem: CustomProblem) : CustomResolution
}

/**
 * The note type chosen in the settings for [CardStyle.CUSTOM]: found by its stored id, else by
 * its name (then the new id is remembered). Nothing here changes the note type in AnkiDroid.
 */
fun resolveCustomNoteType(anki: AnkiApi, settings: AnkiSettings): CustomResolution {
    val name = settings.customNoteTypeName.value
        ?: return CustomResolution.Unusable("", CustomProblem.NOT_CHOSEN)
    val types = anki.noteTypes()
    val storedId = settings.noteTypeId(CardStyle.CUSTOM)
    val found = types.firstOrNull { it.id == storedId } ?: types.firstOrNull { it.name == name }
        ?: return CustomResolution.Unusable(name, CustomProblem.MISSING)
    if (found.id != storedId) settings.setNoteTypeId(CardStyle.CUSTOM, found.id)
    val mapping = settings.fieldMapping(name).restrictedTo(found.fields)
    val type = CustomNoteType(found.id, name, found.fields, mapping)
    if (type.wordIndex < 0) return CustomResolution.Unusable(name, CustomProblem.NO_WORD_FIELD)
    return CustomResolution.Ready(type)
}
