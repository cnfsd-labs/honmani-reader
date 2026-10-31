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
    /** Pitch accent downsteps of [reading] (0: none); empty if unknown. */
    val pitches: List<Int> = emptyList(),
    /** How common the word is, as shown on the card ("Jiten #395"). */
    val frequency: String? = null,
)

/** What the dictionaries say about a word, for a card made from a saved word. */
data class WordDetails(
    val senses: List<List<String>> = emptyList(),
    val pitches: List<Int> = emptyList(),
    val frequency: String? = null,
)

/**
 * Honmani's Anki note type and how a [CardContent] fills it. The front shows the word and the
 * book's sentence with the word marked; the back adds furigana, the meanings and the audio.
 */
object AnkiCard {
    const val NOTE_TYPE = "Honmani"
    const val DECK = "Honmani"
    const val TAG = "honmani"

    /** Field names; the first is the sort field and duplicate key, as usual in Anki. */
    val FIELDS = listOf(
        "Word", "Reading", "Furigana", "Meaning", "Sentence", "Book", "WordAudio", "SentenceAudio", "Pitch", "Frequency",
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

    /** The fields for [content], in [FIELDS] order, with the audio fields as given. */
    fun fields(content: CardContent, wordAudio: String = "", sentenceAudio: String = ""): List<String> {
        val reading = content.reading.ifEmpty { content.expression }
        return listOf(
            escape(content.expression),
            escape(reading),
            furigana(content.expression, reading),
            meaning(content.senses),
            sentence(content.sentence, content.surfaceStart, content.surfaceEnd),
            escape(content.bookTitle.orEmpty()),
            wordAudio,
            sentenceAudio,
            PitchAccent.html(reading, content.pitches),
            escape(content.frequency.orEmpty()),
        )
    }

    /** 取り扱う read とりあつかう as `<ruby>取<rt>と</rt></ruby>り<ruby>扱<rt>あつか</rt></ruby>う`. */
    fun furigana(expression: String, reading: String): String =
        Furigana.segments(expression, reading).joinToString("") { segment ->
            val ruby = segment.ruby
            if (ruby == null || ruby == segment.text) {
                escape(segment.text)
            } else {
                "<ruby>${escape(segment.text)}<rt>${escape(ruby)}</rt></ruby>"
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
     * field without HTML, as a number. AnkiDroid stores it per note, so duplicates can be found
     * without reading every note.
     */
    fun checksum(field: String): Long {
        val text = field.replace(Regex("<[^>]*>"), "")
        val digest = MessageDigest.getInstance("SHA-1").digest(text.toByteArray(Charsets.UTF_8))
        return digest.take(4).fold(0L) { value, byte -> (value shl 8) or (byte.toLong() and 0xFF) }
    }

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
