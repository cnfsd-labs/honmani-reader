package app.honmani.reader.lookup

import java.text.Normalizer

/**
 * What a dictionary search looks for, from the text typed so far: Japanese, romaji (also half
 * typed) or both mixed, or a pattern with wildcards (* any text, ? one character).
 */
data class SearchQuery(
    /** Texts the query spells out in full, looked up as words (with deinflection); the first is the query itself. */
    val exact: List<String>,
    /** Texts the words looked for start with. */
    val prefixes: List<String>,
    /** SQLite GLOB patterns when the query has wildcards; then [exact] and [prefixes] are empty. */
    val patterns: List<String>,
) {
    val isEmpty: Boolean get() = exact.isEmpty() && prefixes.isEmpty() && patterns.isEmpty()

    companion object {
        val EMPTY = SearchQuery(emptyList(), emptyList(), emptyList())

        fun parse(query: String): SearchQuery {
            // NFKC: full-width letters and ＊ ？ as typed on a Japanese keyboard, half-width kana.
            val text = Normalizer.normalize(query, Normalizer.Form.NFKC).filterNot { it.isWhitespace() }
            if (text.isEmpty()) return EMPTY
            if (text.any { it in WILDCARDS }) {
                val pattern = buildString {
                    var segment = StringBuilder()
                    fun flush() {
                        val typed = Romaji.toKana(segment.toString())
                        append(typed.kana.replace("[", "[[]"))
                        if (!typed.complete) append('*')
                        segment = StringBuilder()
                    }
                    for (c in text) {
                        if (c in WILDCARDS) {
                            flush()
                            append(c)
                        } else {
                            segment.append(c)
                        }
                    }
                    flush()
                }.replace(Regex("\\*+"), "*")
                if (pattern.all { it in WILDCARDS }) return EMPTY
                return SearchQuery(emptyList(), emptyList(), variants(pattern))
            }
            val typed = Romaji.toKana(text)
            if (typed.complete) {
                if (typed.kana.isEmpty()) return EMPTY
                val forms = variants(typed.kana)
                return SearchQuery(forms, forms, emptyList())
            }
            // A final n is ん already, or the start of な, に, ...
            val exact = if ("ん" in typed.pending) variants(typed.kana + "ん") else emptyList()
            return SearchQuery(exact, typed.pending.flatMap { variants(typed.kana + it) }.distinct(), emptyList())
        }

        /** [text], then in hiragana and in katakana if it has no kanji (kana words are written either way). */
        private fun variants(text: String): List<String> =
            if (text.codePoints().anyMatch(JapaneseText::isKanji)) {
                listOf(text)
            } else {
                listOf(text, JapaneseText.toHiragana(text), JapaneseText.toKatakana(text)).distinct()
            }

        private const val WILDCARDS = "*?"
    }
}
