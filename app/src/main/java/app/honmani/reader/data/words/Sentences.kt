package app.honmani.reader.data.words

/**
 * Where a paragraph's sentences are, as the tap bridge cuts them (`assets/honmani/tap-bridge.js`,
 * `sentenceAround`): a sentence ends after a run of the end characters (the reader's choice,
 * [DEFAULT_ENDS] unless changed) or line breaks, and the closing quotes that follow it.
 */
object Sentences {
    /** `DEFAULT_SENTENCE_ENDS` in `tap-bridge.js` is the same. A line break always ends a sentence too. */
    const val DEFAULT_ENDS = "。！？!?…"
    private const val MAX_ENDS = 32
    private const val CLOSING_QUOTES = "」』）〉》】\"'"

    /**
     * [ends] as stored: each character once, without white space and the halves of characters
     * beyond 16 bits (the sentence cutting looks at single chars). Null or nothing left: the default.
     */
    fun cleanEnds(ends: String?): String =
        ends.orEmpty().filterNot { it.isWhitespace() || it.isSurrogate() || it.isISOControl() }
            .toList().distinct().take(MAX_ENDS).joinToString("").ifEmpty { DEFAULT_ENDS }

    /** The sentences of [text] without the white space around them, as ranges of its indexes. */
    fun of(text: String, ends: String = DEFAULT_ENDS): List<IntRange> {
        fun isEnd(c: Char) = c == '\n' || c in ends
        val found = ArrayList<IntRange>()
        var start = 0
        while (start < text.length) {
            var end = start
            while (end < text.length && !isEnd(text[end])) end++
            while (end < text.length && isEnd(text[end])) end++
            while (end < text.length && text[end] in CLOSING_QUOTES) end++
            var from = start
            var to = end
            while (from < to && text[from].isWhitespace()) from++
            while (to > from && text[to - 1].isWhitespace()) to--
            if (to > from) found += from until to
            start = end
        }
        return found
    }
}
