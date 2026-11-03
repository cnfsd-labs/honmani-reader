package app.honmani.reader.lookup

/**
 * Taps on Japanese text inside the lookup panel (a meaning, an example sentence): the text a
 * nested lookup scans, as a tap in a book does, from the tapped character on.
 */
object NestedLookup {
    /** The reader's scan length: characters from the tapped one that a lookup tries. */
    const val SCAN_LENGTH = 20

    /**
     * The text to look up for a tap on the character at [index] of [text], at most [SCAN_LENGTH]
     * characters; null when the tap is not on Japanese (Latin text, digits, punctuation, spaces).
     */
    fun scanText(text: String, index: Int): String? {
        if (index < 0 || index >= text.length) return null
        if (!isJapanese(text.codePointAt(index))) return null
        var end = minOf(text.length, index + SCAN_LENGTH)
        // Never cut a character outside the BMP in two.
        if (end < text.length && end > index + 1 && text[end - 1].isHighSurrogate()) end--
        return text.substring(index, end)
    }

    /** Kana, kanji and the repeat and long-vowel marks: what a dictionary word is made of. */
    fun isJapanese(codePoint: Int): Boolean =
        codePoint in 0x3041..0x3096 || // hiragana
            codePoint in 0x309D..0x309F ||
            codePoint in 0x30A1..0x30FF || // katakana, incl. the long-vowel mark
            codePoint in 0xFF66..0xFF9F || // half-width katakana
            codePoint == '々'.code ||
            JapaneseText.isKanji(codePoint)
}
