package app.honmani.reader.lookup

/**
 * An English (or other Latin-script) search: the words of the query as the gloss index matches
 * them, and how well an entry's glosses fit it. "eat" fits 食べる ("to eat") as well as "to
 * eat" does; a gloss that is the query (less "to", articles and remarks in brackets) beats one
 * that merely contains it, and the first meaning beats later ones.
 */
class EnglishQuery private constructor(
    /** The query as glosses are compared with it ([normalize]). */
    val text: String,
    /** The FTS4 MATCH expression: the words as a phrase. */
    val match: String,
) {
    /** How well [senses] (the glosses of each meaning, in order) fit: a [Fit] tier, lower is better. */
    fun fit(senses: List<List<String>>): Int {
        var best = Fit.OTHER
        senses.forEachIndexed { index, glosses ->
            for (gloss in glosses) {
                val normalized = normalize(gloss)
                val tier = when {
                    normalized == text -> if (index == 0) Fit.FIRST_EXACT else Fit.EXACT
                    index == 0 && containsWords(normalized) -> Fit.FIRST_WORD
                    else -> Fit.OTHER
                }
                if (tier < best) best = tier
            }
        }
        return best
    }

    private fun containsWords(gloss: String): Boolean {
        var from = gloss.indexOf(text)
        while (from >= 0) {
            val end = from + text.length
            if ((from == 0 || !gloss[from - 1].isLetterOrDigit()) && (end == gloss.length || !gloss[end].isLetterOrDigit())) return true
            from = gloss.indexOf(text, from + 1)
        }
        return false
    }

    /** Fit tiers, best first. */
    object Fit {
        /** A gloss of the first meaning is the query. */
        const val FIRST_EXACT = 0

        /** A gloss of a later meaning is the query. */
        const val EXACT = 1

        /** A gloss of the first meaning contains the query's words. */
        const val FIRST_WORD = 2
        const val OTHER = 3
    }

    companion object {
        private val brackets = Regex("\\([^()]*\\)|\\[[^\\[\\]]*]")
        private val leading = Regex("^(to|a|an|the) ")
        private val separators = Regex("[^\\p{L}\\p{N}']+")

        /**
         * The query in [text], or null if it has no words to search for: Latin letters (or
         * digits) only, as typed for romaji too.
         */
        fun parse(text: String): EnglishQuery? {
            if (!Romaji.isRomaji(text)) return null
            val normalized = normalize(text)
            // The index splits words at apostrophes too: "can't" is "can t".
            val words = normalized.split(' ', '\'').filter { it.isNotEmpty() }
            if (words.isEmpty()) return null
            return EnglishQuery(normalized, "\"" + words.joinToString(" ") + "\"")
        }

        /**
         * [gloss] lower case, without remarks in brackets, a leading "to" or article, and
         * punctuation: "to eat (e.g. a meal)" is "eat".
         */
        fun normalize(gloss: String): String {
            var text = gloss.lowercase()
            while (true) {
                val stripped = brackets.replace(text, " ")
                if (stripped == text) break
                text = stripped
            }
            text = text.replace(separators, " ").trim()
            return leading.replace(text, "").trim()
        }
    }
}
