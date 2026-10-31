package app.honmani.reader.data.words

import app.honmani.reader.data.difficulty.Segmenter
import app.honmani.reader.data.difficulty.Token

/** Which words the reader marks (reader setting, `assets/honmani/word-marks.js`). */
enum class WordMarksMode(val storage: String) {
    OFF("off"),

    /** Words looked up and not known yet. */
    LEARNING("learning"),

    /** Those, and words the reader has never met ([WordMark.Kind.NEW]). */
    NEW("new"),
    ;

    companion object {
        val DEFAULT = LEARNING

        fun fromStorage(value: String?): WordMarksMode = entries.firstOrNull { it.storage == value } ?: DEFAULT
    }
}

/** A word to mark in a paragraph: [start] and [length] in its text. */
data class WordMark(val start: Int, val length: Int, val kind: Kind) {
    enum class Kind {
        /** Looked up and not known yet (`WordProgress`). */
        LEARNING,

        /** Never met in reading. */
        NEW,

        /** A word the reader knows ([WordKnowledge.isKnown]): the reader hides its furigana. */
        KNOWN,
    }
}

/**
 * The words to mark in the reader, split as books are graded ([Segmenter]). A new word is one the
 * reader has never met, not one the vocabulary estimate guesses they don't know: a mark is a claim
 * about one word, which a guess about frequency bands can't make. A new reader's first pages are
 * mostly marked; the particles and other common words lose their marks within a few pages.
 */
object WordMarks {
    fun of(
        text: String,
        segmenter: Segmenter,
        knowledge: WordKnowledge,
        mode: WordMarksMode,
        /** Also mark the words the reader knows ([WordMark.Kind.KNOWN]), whatever [mode]. */
        knownWords: Boolean = false,
    ): List<WordMark> {
        val tint = mode != WordMarksMode.OFF
        if (!tint && !knownWords) return emptyList()
        val marks = ArrayList<WordMark>()
        val tokens = ArrayList<Token>()
        segmenter.segment(text) { tokens += it }
        val tokenEnds = tokens.mapTo(HashSet()) { it.start + it.length }
        // Where a word written as it was looked up ends ([WordKnowledge.learningForms]).
        var formEnd = 0
        for (token in tokens) {
            if (token.start < formEnd) continue
            // Only a form that ends where a word of the book ends: た looked up inside だった
            // marks no た at the start of ただ.
            val form = if (tint) knowledge.learningFormAt(text, token.start) { token.start + it.length in tokenEnds } else null
            if (form != null) {
                marks += WordMark(token.start, form.length, WordMark.Kind.LEARNING)
                formEnd = token.start + form.length
                continue
            }
            // Names and other text no dictionary knows stay unmarked.
            if (token.rank == Token.UNKNOWN) continue
            val kind = when {
                tint && knowledge.isLearning(token.key) -> WordMark.Kind.LEARNING
                tint && mode == WordMarksMode.NEW && !knowledge.hasRead(token.key) -> WordMark.Kind.NEW
                knownWords && knowledge.isKnown(token.key) -> WordMark.Kind.KNOWN
                else -> continue
            }
            marks += WordMark(token.start, token.length, kind)
        }
        return marks
    }
}
