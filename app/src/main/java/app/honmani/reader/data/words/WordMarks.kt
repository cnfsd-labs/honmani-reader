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

        /** A sentence with exactly one word the reader does not know yet ("i+1"): tinted whole. */
        SENTENCE,
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
        /** Also mark the sentences with exactly one unknown word ([WordMark.Kind.SENTENCE]). */
        sentences: Boolean = false,
    ): List<WordMark> {
        val tint = mode != WordMarksMode.OFF
        if (!tint && !knownWords && !sentences) return emptyList()
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
        if (sentences) marks += oneUnknownSentences(text, tokens, knowledge)
        return marks
    }

    /** Words this common (a rank up to here: は, です, ない) are grammar, not vocabulary. */
    private const val GRAMMAR_RANK_LIMIT = 100

    /** A longer "sentence" has no punctuation to speak of; the reader's tap window cuts it at this too. */
    private const val MAX_SENTENCE = 400

    /**
     * The sentences of [text] that hold exactly one word the reader does not know: one never met in
     * reading or being learned (a form looked up counts as written). Grammar words and text no
     * dictionary knows (names) are not counted.
     */
    private fun oneUnknownSentences(text: String, tokens: List<Token>, knowledge: WordKnowledge): List<WordMark> {
        val ends = tokens.mapTo(HashSet()) { it.start + it.length }
        val unknown = ArrayList<Int>()
        var formEnd = 0
        for (token in tokens) {
            if (token.start < formEnd) continue
            val form = knowledge.learningFormAt(text, token.start) { token.start + it.length in ends }
            if (form != null) {
                unknown += token.start
                formEnd = token.start + form.length
            } else if (token.rank != Token.UNKNOWN && token.rank > GRAMMAR_RANK_LIMIT &&
                (knowledge.isLearning(token.key) || !knowledge.hasRead(token.key))
            ) {
                unknown += token.start
            }
        }
        val found = ArrayList<WordMark>()
        var next = 0
        for (sentence in Sentences.of(text)) {
            while (next < unknown.size && unknown[next] < sentence.first) next++
            var count = 0
            while (next + count < unknown.size && unknown[next + count] < sentence.last + 1) count++
            if (count == 1 && sentence.last + 1 - sentence.first <= MAX_SENTENCE) {
                found += WordMark(sentence.first, sentence.last + 1 - sentence.first, WordMark.Kind.SENTENCE)
            }
            next += count
        }
        return found
    }
}
