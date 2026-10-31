package app.honmani.reader.data.words

/**
 * Where a paragraph's sentences are, as the tap bridge cuts them (`assets/honmani/tap-bridge.js`,
 * `sentenceAround`): a sentence ends after a run of 。！？!?… or a line break, and the closing
 * quotes that follow it.
 */
object Sentences {
    private const val ENDS = "。！？!?…\n"
    private const val CLOSING_QUOTES = "」』）〉》】\"'"

    /** The sentences of [text] without the white space around them, as ranges of its indexes. */
    fun of(text: String): List<IntRange> {
        val found = ArrayList<IntRange>()
        var start = 0
        while (start < text.length) {
            var end = start
            while (end < text.length && text[end] !in ENDS) end++
            while (end < text.length && text[end] in ENDS) end++
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
