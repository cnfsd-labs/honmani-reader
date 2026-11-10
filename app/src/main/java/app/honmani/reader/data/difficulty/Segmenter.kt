package app.honmani.reader.data.difficulty

import app.honmani.reader.lookup.Deinflection
import app.honmani.reader.lookup.Deinflector
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
 * unknown token per character, or per run of katakana (a name such as メロス), and a run of
 * katakana is not split into one-character words.
 */
class Segmenter(private val lexicon: Lexicon, private val maxCached: Int = MAX_CACHED) {
    private class Hit(val key: String, val rank: Int, val inflections: Int) {
        val cost: Double = costOf(rank) + inflections * INFLECTION_COST
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
            for (length in minLength..minOf(Segmenter.MAX_LENGTH, to - start)) {
                val hit = match(text.substring(start, start + length)) ?: continue
                offer(Token(start, length, hit.rank, hit.key), hit.cost)
            }
            val unknownEnd = if (katakanaEnd > start) katakanaEnd else start + 1
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

    /** The cheapest dictionary word [source] can be, with its inflections undone. */
    private fun match(source: String): Hit? {
        if (source.length <= CACHED_LENGTH) cache[source]?.let { return it }
        if (source.length <= CACHED_LENGTH && cache.containsKey(source)) return null
        var best: Hit? = null
        val variants = if (JapaneseText.containsKatakana(source)) listOf(source, JapaneseText.toHiragana(source)) else listOf(source)
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
                val hit = Hit(form.text, lexicon.rank(index), form.inflections.size)
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

        /** About 30 MB of prefixes; a book uses far fewer. */
        internal const val MAX_CACHED = 200_000

        /** Rank assumed for words in a dictionary but in no frequency list. */
        internal const val RARE_RANK = 500_000

        /** Zipf: a word of rank r has probability about 1 / (r · ln N), N the list's length. */
        private val WORD_COST = ln(ln(RARE_RANK.toDouble()))

        /** Each inflection undone makes a reading slightly less likely than a plain word. */
        private const val INFLECTION_COST = 0.1

        private val UNKNOWN_COST = costOf(RARE_RANK) + 4

        private fun costOf(rank: Int): Double =
            ln((if (rank == Lexicon.UNRANKED) RARE_RANK else rank).toDouble()) + WORD_COST

        fun isKatakana(c: Char): Boolean = c in 'ァ'..'ヺ' || c == 'ー' || c in 'ｦ'..'ﾟ'

        fun isJapanese(c: Char): Boolean =
            c in 'ぁ'..'ゟ' || isKatakana(c) || c == '々' || c == '〆' ||
                JapaneseText.isKanji(c.code) || Character.isSurrogate(c)
    }
}
