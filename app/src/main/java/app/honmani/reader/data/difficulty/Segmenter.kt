package app.honmani.reader.data.difficulty

import app.honmani.reader.lookup.Deinflection
import app.honmani.reader.lookup.Deinflector
import app.honmani.reader.lookup.Inflection
import app.honmani.reader.lookup.JapaneseText
import app.honmani.reader.lookup.WordClass
import kotlin.math.ln

/**
 * One word of a text: [rank] is how common it is ([Lexicon.UNRANKED] if no frequency list has
 * it), or [UNKNOWN] for text no dictionary knows, such as most names. [key] is its dictionary
 * form.
 */
data class Token(val start: Int, val length: Int, val rank: Int, val key: String) {
    companion object {
        const val UNKNOWN = -1
    }
}

/**
 * Splits Japanese text into words with the lookup's dictionary forms ([Deinflector]) and the
 * frequency ranks in the [lexicon]. Of all the ways to split a stretch of text, it takes the
 * likeliest: a word of rank r costs about ln r (Zipf's law), so common words win over rare ones
 * and over phrases a dictionary happens to list (から|は, not the rare entry からは). This is
 * close to how the frequency lists themselves were counted, which a longest-match split is not.
 *
 * Punctuation, Latin letters and digits are skipped. Text no dictionary knows becomes one
 * unknown token per character, or per run of katakana (a name such as メロス). [canEnd] keeps
 * numbers and katakana words together.
 */
class Segmenter(private val lexicon: Lexicon, private val maxCached: Int = MAX_CACHED) {
    private class Hit(val key: String, val rank: Int, inflections: List<Inflection>, katakana: Boolean) {
        val cost: Double = costOf(rank) + inflectionCost(inflections, katakana)
    }

    /**
     * Prefixes seen before, with what they matched (null: nothing). Short ones repeat a lot. Cleared
     * when full: one segmenter outlives many books, and new prefixes would otherwise keep coming.
     */
    private val cache = HashMap<String, Hit?>()

    internal val cachedPrefixes: Int get() = cache.size

    fun segment(text: String, onToken: (Token) -> Unit) {
        var i = 0
        while (i < text.length) {
            if (!isJapanese(text[i])) {
                i++
                continue
            }
            var end = i
            while (end < text.length && isJapanese(text[end])) end++
            segmentRun(text, i, end, onToken)
            i = end
        }
    }

    /** The cheapest split of text[from, to), all Japanese. */
    private fun segmentRun(text: String, from: Int, to: Int, onToken: (Token) -> Unit) {
        val size = to - from
        val best = DoubleArray(size + 1) { Double.MAX_VALUE }
        val previous = arrayOfNulls<Token>(size + 1)
        best[0] = 0.0
        for (offset in 0 until size) {
            if (best[offset] == Double.MAX_VALUE) continue
            val start = from + offset
            val katakanaEnd = katakanaRunEnd(text, start, to)
            // Inside a katakana word, single katakana are no words of their own.
            val minLength = if (katakanaEnd - start >= 2) 2 else 1
            fun offer(token: Token, cost: Double) {
                val endOffset = token.start + token.length - from
                if (best[offset] + cost < best[endOffset]) {
                    best[endOffset] = best[offset] + cost
                    previous[endOffset] = token
                }
            }
            for (length in minLength..minOf(MAX_LENGTH, to - start)) {
                if (!canEnd(text, start + length, from, to)) continue
                val hit = match(text.substring(start, start + length)) ?: continue
                offer(Token(start, length, hit.rank, hit.key), hit.cost)
            }
            var unknownEnd = if (katakanaEnd > start) katakanaEnd else start + 1
            while (!canEnd(text, unknownEnd, from, to)) unknownEnd++
            offer(Token(start, unknownEnd - start, Token.UNKNOWN, text.substring(start, unknownEnd)), UNKNOWN_COST)
        }
        val tokens = ArrayList<Token>()
        var offset = size
        while (offset > 0) {
            val token = previous[offset]!!
            tokens += token
            offset = token.start - from
        }
        for (index in tokens.indices.reversed()) onToken(tokens[index])
    }

    /**
     * Whether a word can end before [at], in the stretch from [from] to [to]: not between the digits
     * of a number (二十分 is no 二 + 十分), and not so that a single letter is left at either end of a
     * run of katakana (アラスカ is no アラス + カ, 長いツノ no いツ).
     */
    private fun canEnd(text: String, at: Int, from: Int, to: Int): Boolean {
        if (at <= from || at >= to) return true
        if (text[at - 1] in KANJI_DIGITS && text[at] in KANJI_DIGITS) return false
        if (!isKatakana(text[at - 1]) || !isKatakana(text[at])) return true
        val first = at - 1 == from || !isKatakana(text[at - 2])
        val last = at + 1 == to || !isKatakana(text[at + 1])
        return !first && !last
    }

    /** The cheapest dictionary word [source] can be, with its inflections undone. */
    private fun match(source: String): Hit? {
        if (source.length <= CACHED_LENGTH) cache[source]?.let { return it }
        if (source.length <= CACHED_LENGTH && cache.containsKey(source)) return null
        var best: Hit? = null
        val katakana = source.all(::isKatakana)
        val written = if (source.any { it in OLD_KANA }) JapaneseText.normalize(source) else source
        val variants = if (JapaneseText.containsKatakana(written)) listOf(written, JapaneseText.toHiragana(written)) else listOf(written)
        for (variant in variants) {
            // Inflections end in kana: text ending in a kanji can only be a dictionary form.
            val forms = if (JapaneseText.isKanji(variant.codePointBefore(variant.length))) {
                listOf(Deinflection(variant, WordClass.ANY, emptyList()))
            } else {
                Deinflector.deinflect(variant)
            }
            for (form in forms) {
                val index = lexicon.find(form.text)
                if (index < 0) continue
                if (form.conditions != WordClass.ANY && form.conditions and lexicon.classes(index) == 0) continue
                val hit = Hit(form.text, lexicon.rank(index), form.inflections, katakana)
                if (best == null || hit.cost < best.cost) best = hit
            }
        }
        if (source.length <= CACHED_LENGTH) {
            if (cache.size >= maxCached) cache.clear()
            cache[source] = best
        }
        return best
    }

    private fun katakanaRunEnd(text: String, from: Int, to: Int): Int {
        var end = from
        while (end < to && isKatakana(text[end])) end++
        return end
    }

    companion object {
        /** Matches the tap lookup's scan length. */
        const val MAX_LENGTH = 20

        private const val CACHED_LENGTH = 6

        /** Letters and repeat marks of old texts (こゝろ, ゐる). */
        private const val OLD_KANA = "ゝゞヽヾゐゑヰヱ"

        private const val KANJI_DIGITS = "〇一二三四五六七八九十百千万億兆"

        /** About 30 MB of prefixes; a book uses far fewer. */
        internal const val MAX_CACHED = 200_000

        /** Rank assumed for words in a dictionary but in no frequency list. */
        internal const val RARE_RANK = 500_000

        /** Zipf: a word of rank r has probability about 1 / (r · ln N), N the list's length. */
        private val WORD_COST = ln(ln(RARE_RANK.toDouble()))

        // What undoing an inflection costs: little for the common ones, more for those that a short
        // word of its own often looks like (あれ is no あう, いい no いう, できる no でくる, ツイ no つく).
        // Tuned on 12 Aozora Bunko works against Sudachi's and MeCab's splits, checked on 12 others.
        private const val INFLECTION_COST = 0.3
        private const val BARE_STEM_COST = 1.4
        private const val IMPERATIVE_COST = 2.5
        private const val POTENTIAL_COST = 3.0
        private const val CAUSATIVE_SHORT_COST = 3.0
        private const val PASSIVE_COST = 2.0
        private const val WANT_COST = 1.0
        private const val ROUGH_COST = 5.0

        /** On top, for an inflected word written all in katakana. */
        private const val KATAKANA_INFLECTION_COST = 3.0

        private val UNKNOWN_COST = costOf(RARE_RANK) + 4

        /** How much less likely than a plain word undoing [inflections] makes a reading. */
        private fun inflectionCost(inflections: List<Inflection>, katakana: Boolean): Double {
            var cost = if (katakana && inflections.isNotEmpty()) KATAKANA_INFLECTION_COST else 0.0
            for ((index, inflection) in inflections.withIndex()) {
                cost += when (inflection) {
                    Inflection.STEM -> if (index == inflections.lastIndex) BARE_STEM_COST else INFLECTION_COST
                    Inflection.IMPERATIVE -> IMPERATIVE_COST
                    Inflection.POTENTIAL -> POTENTIAL_COST
                    Inflection.CAUSATIVE_SHORT -> CAUSATIVE_SHORT_COST
                    Inflection.PASSIVE, Inflection.POTENTIAL_OR_PASSIVE -> PASSIVE_COST
                    Inflection.WANT, Inflection.DESIRE_OF_OTHERS -> WANT_COST
                    Inflection.ROUGH -> ROUGH_COST
                    else -> INFLECTION_COST
                }
            }
            return cost
        }

        private fun costOf(rank: Int): Double =
            ln((if (rank == Lexicon.UNRANKED) RARE_RANK else rank).toDouble()) + WORD_COST

        fun isKatakana(c: Char): Boolean = c in 'ァ'..'ヺ' || c == 'ー' || c in 'ｦ'..'ﾟ'

        fun isJapanese(c: Char): Boolean =
            c in 'ぁ'..'ゟ' || isKatakana(c) || c == '々' || c == '〆' ||
                JapaneseText.isKanji(c.code) || Character.isSurrogate(c)
    }
}
