package app.honmani.reader.lookup

/**
 * How a short text such as a book title reads, in kana, for romaji search: kanji are read with
 * the dictionary (longest match first), everything else is kept.
 */
object TextReading {
    /**
     * The reading of [text]. [segment] gives, for a text starting with kanji, the length of the
     * dictionary match at its start and that part's reading, or null if nothing matches.
     */
    suspend fun of(text: String, segment: suspend (String) -> Pair<Int, String>?): String {
        val out = StringBuilder()
        var i = 0
        while (i < text.length) {
            val codePoint = text.codePointAt(i)
            val match = if (isKanji(codePoint)) {
                val end = text.offsetByCodePoints(i, minOf(MAX_SEGMENT, text.codePointCount(i, text.length)))
                segment(text.substring(i, end))
            } else {
                null
            }
            if (match == null || match.first <= 0) {
                out.appendCodePoint(codePoint)
                i += Character.charCount(codePoint)
            } else {
                out.append(match.second)
                i += match.first
            }
        }
        return out.toString()
    }

    /**
     * The reading of [source] (as written in the text) for a dictionary entry: the entry's reading
     * when it matched as written, with an inflected ending carried over (食べた for 食べる たべる
     * reads たべた); the source itself when it matched the entry's reading.
     */
    fun readingOf(source: String, expression: String, reading: String, matchedExpression: Boolean): String {
        if (!matchedExpression) return source
        if (source == expression) return reading
        val common = source.commonPrefixWith(expression)
        val ending = expression.substring(common.length)
        val stem = if (reading.endsWith(ending)) reading.dropLast(ending.length) else reading
        return stem + source.substring(common.length)
    }

    private fun isKanji(c: Int) =
        c in 0x3400..0x9FFF || c in 0xF900..0xFAFF || c in 0x20000..0x323AF ||
            c == '々'.code || c == '〆'.code || c == 'ヶ'.code

    private const val MAX_SEGMENT = 12
}
