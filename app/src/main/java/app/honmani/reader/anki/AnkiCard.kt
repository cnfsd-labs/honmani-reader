package app.honmani.reader.anki

import app.honmani.reader.lookup.Furigana
import app.honmani.reader.lookup.PitchAccent
import java.security.MessageDigest

/** What goes on one Anki card: a word, the sentence it was read in, and its meanings. */
data class CardContent(
    val expression: String,
    val reading: String,
    /** Senses of the word, each a list of glosses; from the first dictionary that has it. */
    val senses: List<List<String>>,
    val sentence: String,
    /** The looked-up text is [surfaceStart] until [surfaceEnd] in [sentence]. */
    val surfaceStart: Int,
    val surfaceEnd: Int,
    val bookTitle: String? = null,
    /** The library book the sentence is from, if known; the sentence's audio is cut from its audiobook. */
    val bookId: Long? = null,
    /** Pitch accent downsteps of [reading] (0: none); empty if unknown. */
    val pitches: List<Int> = emptyList(),
    /** How common the word is, as shown on the card ("Jiten #395"). */
    val frequency: String? = null,
    /** The reader's correction of the furigana (`WordEntity.furigana`); empty if none. */
    val furigana: String = "",
)

/** What the dictionaries say about a word, for a card made from a saved word. */
data class WordDetails(
    val senses: List<List<String>> = emptyList(),
    val pitches: List<Int> = emptyList(),
    val frequency: String? = null,
)

/**
 * An Anki note type Honmani makes: its name, fields and card template, and how a [CardContent]
 * fills it. [id] and [guidPrefix] are the fixed ids of the `.apkg` ([AnkiPackage]); [required]
 * are the field indexes a card needs one of. [addedFields] came after the note type's first
 * version: they stand last in [fields], and a note type in AnkiDroid that lacks them gains them
 * ([AnkiExporter]) instead of being replaced by a second one.
 */
class NoteModel(
    val name: String,
    val id: Long,
    val guidPrefix: String,
    val fields: List<String>,
    val cardName: String,
    val front: String,
    val back: String,
    val css: String,
    val required: List<Int>,
    val addedFields: List<String> = emptyList(),
    val build: (content: CardContent, wordAudio: String, sentenceAudio: String) -> List<String>,
)

/** Which note type new cards use; the reader's choice in the Anki settings. */
enum class CardStyle(val model: NoteModel) {
    /** Word and marked sentence on the front, furigana, pitch and meanings on the back. */
    HONMANI(AnkiCard.MODEL),

    /** The whole sentence, unmarked, on the front; word, reading and meaning on the back. */
    SENTENCE(SentenceCard.MODEL),

    /**
     * The reader's own note type in AnkiDroid ([FieldMapping]); Honmani never changes it. Its
     * [model] is only what the `.apkg` and the text file use, as they can't write into it.
     */
    CUSTOM(AnkiCard.MODEL),
    ;

    companion object {
        val DEFAULT = HONMANI

        fun fromName(name: String?): CardStyle = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

/**
 * Honmani's Anki note type and how a [CardContent] fills it. The front shows the word and the
 * book's sentence with the word marked; the back adds furigana, the meanings and the audio.
 */
object AnkiCard {
    const val NOTE_TYPE = "Honmani"
    const val DECK = "Honmani"
    const val TAG = "honmani"

    /** The field with the word in Anki's own furigana notation, for `{{furigana:FuriganaPlain}}`. */
    const val PLAIN_FURIGANA = "FuriganaPlain"

    /** Field names; the first is the sort field and duplicate key, as usual in Anki. */
    val FIELDS = listOf(
        "Word", "Reading", "Furigana", "Meaning", "Sentence", "Book", "WordAudio", "SentenceAudio", "Pitch", "Frequency",
        PLAIN_FURIGANA,
    )

    const val CARD_NAME = "Reading"

    const val FRONT = """<div class="word">{{Word}}</div>
<div class="sentence">{{Sentence}}</div>"""

    const val BACK = """<div class="word">{{Furigana}}</div>
{{#Pitch}}<div class="pitch">{{Pitch}}</div>{{/Pitch}}
<div class="sentence">{{Sentence}}</div>
<hr id="answer">
<div class="meaning">{{Meaning}}</div>
{{#Book}}<div class="book">{{Book}}</div>{{/Book}}
{{#Frequency}}<div class="book">{{Frequency}}</div>{{/Frequency}}
{{WordAudio}}{{SentenceAudio}}"""

    const val CSS = """.card {
  font-family: "Noto Sans CJK JP", "Hiragino Sans", "Yu Gothic", sans-serif;
  font-size: 20px;
  text-align: center;
  color: #1f1f1f;
  background-color: #ffffff;
}
.nightMode.card, .night_mode .card { color: #e6e6e6; background-color: #1f1f1f; }
.word { font-size: 44px; margin: 12px 0; }
.word rt { font-size: 18px; }
.sentence { font-size: 24px; line-height: 1.7; margin: 8px 0; }
.sentence b { color: #8a5cf5; }
.meaning { text-align: left; display: inline-block; max-width: 36em; }
.meaning ol { padding-left: 1.4em; margin: 0; }
.book { font-size: 14px; opacity: 0.6; margin-top: 12px; }
.pitch { font-size: 22px; margin: 4px 0 8px; letter-spacing: 0.05em; }
.pitch .h { border-top: 2px solid #8a5cf5; padding-top: 2px; }
.pitch .d { border-right: 2px solid #8a5cf5; }"""

    val MODEL = NoteModel(
        name = NOTE_TYPE,
        id = 1_726_000_000_001L,
        guidPrefix = "honmani-",
        fields = FIELDS,
        cardName = CARD_NAME,
        front = FRONT,
        back = BACK,
        css = CSS,
        required = listOf(0),
        addedFields = listOf(PLAIN_FURIGANA),
        build = { content, wordAudio, sentenceAudio -> fields(content, wordAudio, sentenceAudio) },
    )

    /** The fields for [content], in [FIELDS] order, with the audio fields as given. */
    fun fields(content: CardContent, wordAudio: String = "", sentenceAudio: String = ""): List<String> {
        val reading = content.reading.ifEmpty { content.expression }
        return listOf(
            escape(content.expression),
            escape(reading),
            furigana(content.expression, reading, content.furigana),
            meaning(content.senses),
            sentence(content.sentence, content.surfaceStart, content.surfaceEnd),
            escape(content.bookTitle.orEmpty()),
            wordAudio,
            sentenceAudio,
            PitchAccent.html(reading, content.pitches),
            escape(content.frequency.orEmpty()),
            plainFurigana(content.expression, reading, content.furigana),
        )
    }

    /** 取り扱う read とりあつかう as `<ruby>取<rt>と</rt></ruby>り<ruby>扱<rt>あつか</rt></ruby>う`. */
    fun furigana(expression: String, reading: String, override: String = ""): String =
        Furigana.shown(expression, reading, override).joinToString("") { segment ->
            val ruby = segment.ruby
            if (ruby == null || ruby == segment.text) {
                escape(segment.text)
            } else {
                "<ruby>${escape(segment.text)}<rt>${escape(ruby)}</rt></ruby>"
            }
        }

    /**
     * 取り扱う read とりあつかう as `取[と]り 扱[あつか]う`, Anki's notation. A reading belongs to the
     * text back to the last space, so each one after the start needs a space before its kanji; the
     * `furigana:` filter drops those spaces.
     */
    fun plainFurigana(expression: String, reading: String, override: String = ""): String = buildString {
        for (segment in Furigana.shown(expression, reading, override)) {
            val ruby = segment.ruby
            if (ruby == null || ruby == segment.text) {
                append(escape(segment.text))
            } else {
                if (isNotEmpty()) append(' ')
                append(escape(segment.text)).append('[').append(escape(ruby)).append(']')
            }
        }
    }

    /** One sense as plain text, several as a numbered list. */
    fun meaning(senses: List<List<String>>): String {
        val texts = senses.map { glosses -> escape(glosses.joinToString("; ")) }.filter { it.isNotEmpty() }
        return when (texts.size) {
            0 -> ""
            1 -> texts.single()
            else -> texts.joinToString("", "<ol>", "</ol>") { "<li>$it</li>" }
        }
    }

    /** The sentence with the looked-up text in bold. */
    fun sentence(sentence: String, start: Int, end: Int): String {
        val from = start.coerceIn(0, sentence.length)
        val to = end.coerceIn(from, sentence.length)
        if (from == to) return escape(sentence)
        return escape(sentence.substring(0, from)) + "<b>" + escape(sentence.substring(from, to)) + "</b>" +
            escape(sentence.substring(to))
    }

    /**
     * Anki's duplicate checksum of a first field: the first 8 hex digits of the SHA-1 of the
     * field without HTML (tags removed, then entities such as `&amp;` decoded), as a number. AnkiDroid stores it per note, so duplicates can be found
     * without reading every note.
     */
    fun checksum(field: String): Long {
        val text = decodeEntities(field.replace(Regex("<[^>]*>"), ""))
        val digest = MessageDigest.getInstance("SHA-1").digest(text.toByteArray(Charsets.UTF_8))
        return digest.take(4).fold(0L) { value, byte -> (value shl 8) or (byte.toLong() and 0xFF) }
    }

    private val entity = Regex("&(#[xX][0-9a-fA-F]+|#[0-9]+|[a-zA-Z]+);")

    /** The entities [escape] writes, plus numeric ones and the few named ones Anki's field text can carry. */
    private fun decodeEntities(text: String): String = if ('&' !in text) text else entity.replace(text) { match ->
        val name = match.groupValues[1]
        val decoded = when {
            name.startsWith("#x") || name.startsWith("#X") -> name.substring(2).toIntOrNull(16)?.let(::codePoint)
            name.startsWith("#") -> name.substring(1).toIntOrNull()?.let(::codePoint)
            else -> when (name) {
                "amp" -> "&"
                "lt" -> "<"
                "gt" -> ">"
                "quot" -> "\""
                "apos" -> "'"
                "nbsp" -> " "
                else -> null
            }
        }
        decoded ?: match.value
    }

    private fun codePoint(value: Int): String? =
        if (Character.isValidCodePoint(value)) String(Character.toChars(value)) else null

    fun escape(text: String): String = buildString(text.length) {
        for (c in text) {
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                // Anki's field separator: inside a field it would split the note into more fields.
                '\u001f' -> Unit
                else -> append(c)
            }
        }
    }
}

/**
 * The second note type, "Honmani Sentence": the front is the whole sentence the word was read in,
 * without any mark on the word and without furigana; the back gives the word, its reading and its
 * meanings. A word without a stored sentence has its word on the front instead.
 */
object SentenceCard {
    const val NOTE_TYPE = "Honmani Sentence"

    /** The first two fields are the word and its reading, as in [AnkiCard]: they identify the card. */
    val FIELDS = listOf("Word", "Reading", "Meaning", "Sentence", "WordAudio", "SentenceAudio", AnkiCard.PLAIN_FURIGANA)

    const val FRONT = """<div class="sentence">{{Sentence}}</div>
{{SentenceAudio}}"""

    const val BACK = """{{FrontSide}}
<hr id="answer">
<div class="word">{{Word}}</div>
<div class="reading">{{Reading}}</div>
<div class="meaning">{{Meaning}}</div>
{{WordAudio}}"""

    const val CSS = """.card {
  font-family: "Noto Sans CJK JP", "Hiragino Sans", "Yu Gothic", sans-serif;
  font-size: 20px;
  text-align: center;
  color: #1f1f1f;
  background-color: #ffffff;
}
.nightMode.card, .night_mode .card { color: #e6e6e6; background-color: #1f1f1f; }
.sentence { font-size: 26px; line-height: 1.7; margin: 8px 0; }
.word { font-size: 40px; margin: 12px 0 4px; }
.reading { font-size: 22px; opacity: 0.7; margin-bottom: 8px; }
.meaning { text-align: left; display: inline-block; max-width: 36em; }
.meaning ol { padding-left: 1.4em; margin: 0; }"""

    val MODEL = NoteModel(
        name = NOTE_TYPE,
        id = 1_726_000_000_002L,
        guidPrefix = "honmani-s-",
        fields = FIELDS,
        cardName = AnkiCard.CARD_NAME,
        front = FRONT,
        back = BACK,
        css = CSS,
        // The sentence field is never empty (see [sentence]); it is what the front shows.
        required = listOf(3),
        addedFields = listOf(AnkiCard.PLAIN_FURIGANA),
        build = { content, wordAudio, sentenceAudio -> fields(content, wordAudio, sentenceAudio) },
    )

    fun fields(content: CardContent, wordAudio: String = "", sentenceAudio: String = ""): List<String> {
        val reading = content.reading.ifEmpty { content.expression }
        return listOf(
            AnkiCard.escape(content.expression),
            AnkiCard.escape(reading),
            AnkiCard.meaning(content.senses),
            sentence(content),
            wordAudio,
            sentenceAudio,
            AnkiCard.plainFurigana(content.expression, reading, content.furigana),
        )
    }

    /** The sentence as it is; the word itself if none was stored. */
    fun sentence(content: CardContent): String =
        AnkiCard.escape(content.sentence.ifBlank { content.expression })
}
