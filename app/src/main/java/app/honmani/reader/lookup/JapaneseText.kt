package app.honmani.reader.lookup

import java.text.Normalizer

/** Small helpers for Japanese text. */
object JapaneseText {
    private const val KATAKANA_FIRST = 'ァ'
    private const val KATAKANA_LAST = 'ヶ'
    private const val KATAKANA_TO_HIRAGANA = 'ァ' - 'ぁ'

    /** Kana whose voiced form is the next code point (か -> が). */
    private const val VOICEABLE = "かきくけこさしすせそたちつてとはひふへほカキクケコサシスセソタチツテトハヒフヘホ"

    /** Small hiragana vowels and their full-size forms, as in stretched ねぇ or すごぉい. */
    private const val SMALL_VOWELS = "ぁぃぅぇぉ"
    private const val VOWELS = "あいうえお"

    /** Katakana converted to hiragana; long-vowel marks and other characters are kept. */
    fun toHiragana(text: String): String = buildString(text.length) {
        for (c in text) append(if (c in KATAKANA_FIRST..KATAKANA_LAST) c - KATAKANA_TO_HIRAGANA else c)
    }

    /** [text] with hiragana in katakana (ゝゞ and the rest are kept). */
    fun toKatakana(text: String): String = buildString(text.length) {
        for (c in text) append(if (c in 'ぁ'..'ゖ') c + KATAKANA_TO_HIRAGANA else c)
    }

    fun containsKatakana(text: String): Boolean = text.any { it in KATAKANA_FIRST..KATAKANA_LAST }

    fun isHiragana(c: Char): Boolean = c in 'ぁ'..'ゖ'

    fun isKana(c: Char): Boolean = c in 'ぁ'..'ゟ' || c in '゠'..'ヿ'

    /** Whether [codePoint] is a kanji (a Han character, but not the repeat mark 々). */
    fun isKanji(codePoint: Int): Boolean =
        Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN && codePoint != '々'.code

    /** The distinct kanji of [text], in order of appearance. */
    fun kanjiIn(text: String): List<String> =
        text.codePoints().toArray().filter(::isKanji).distinct().map { String(Character.toChars(it)) }

    /**
     * [text] as a dictionary would write it: without variation selectors and zero-width
     * characters (葛 followed by U+E0100 is 葛), with the kana repeat marks written out (こゝろ is
     * こころ, いすゞ is いすず), half-width katakana and full-width letters in their usual width
     * (NFKC), and the old kana ゐ and ゑ as い and え.
     */
    fun normalize(text: String): String {
        val plain = buildString(text.length) {
            var i = 0
            while (i < text.length) {
                val codePoint = text.codePointAt(i)
                i += Character.charCount(codePoint)
                if (isIgnorable(codePoint)) continue
                val previous = lastOrNull()
                when (codePoint) {
                    'ゝ'.code, 'ヽ'.code -> append(previous ?: codePoint.toChar())
                    'ゞ'.code, 'ヾ'.code ->
                        append(previous?.let { if (it in VOICEABLE) it + 1 else it } ?: codePoint.toChar())
                    'ゐ'.code -> append('い')
                    'ゑ'.code -> append('え')
                    'ヰ'.code -> append('イ')
                    'ヱ'.code -> append('エ')
                    else -> appendCodePoint(codePoint)
                }
            }
        }
        return if (plain.any { it in '＀'..'￯' || it in '豈'..'﫿' }) {
            Normalizer.normalize(plain, Normalizer.Form.NFKC)
        } else {
            plain
        }
    }

    /**
     * Forms of a stretched or clipped word to try as well (none if there are none): すごーい as
     * すごい, ねぇ as ねえ and ね, and, when [clipped], 痛っ as 痛. Only long-vowel marks and small
     * vowels after hiragana count, so katakana words such as ラーメン and ファン stay as they are.
     */
    fun emphaticForms(text: String, clipped: Boolean): Set<String> {
        val withoutMarks = buildString(text.length) {
            for (c in text) {
                if (c == 'ー' && lastOrNull()?.let(::isHiragana) == true) continue
                append(c)
            }
        }.let { if (clipped && it.length > 1 && (it.last() == 'っ' || it.last() == 'ッ')) it.dropLast(1) else it }
        val forms = linkedSetOf(withoutMarks)
        if (withoutMarks.indices.any { isSmallVowelAfterHiragana(withoutMarks, it) }) {
            forms += String(CharArray(withoutMarks.length) { i ->
                val c = withoutMarks[i]
                if (isSmallVowelAfterHiragana(withoutMarks, i)) VOWELS[SMALL_VOWELS.indexOf(c)] else c
            })
            forms += withoutMarks.filterIndexed { i, _ -> !isSmallVowelAfterHiragana(withoutMarks, i) }
        }
        forms -= text
        return forms.filterTo(LinkedHashSet()) { it.isNotBlank() }
    }

    private fun isSmallVowelAfterHiragana(text: String, index: Int): Boolean =
        index > 0 && text[index] in SMALL_VOWELS && isHiragana(text[index - 1]) && text[index - 1] !in SMALL_VOWELS

    /** Variation selectors and zero-width characters, which change no word. */
    private fun isIgnorable(codePoint: Int): Boolean =
        codePoint in 0xFE00..0xFE0F || codePoint in 0xE0100..0xE01EF ||
            codePoint in 0x200B..0x200D || codePoint == 0x2060 || codePoint == 0xFEFF || codePoint == 0x00AD
}
