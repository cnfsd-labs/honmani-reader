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
    /**
     * Forms of a romaji query with the long vowels Hepburn leaves unwritten ("tokyo" is also
     * とうきょう and トーキョー), looked up as whole words below the [exact] ones.
     */
    val longVowels: List<String> = emptyList(),
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
                val romaji = text.any { it in 'a'..'z' || it in 'A'..'Z' }
                val long = if (romaji && forms.size > 1) longVowelForms(JapaneseText.toHiragana(typed.kana)) - forms.toSet() else emptyList()
                return SearchQuery(forms, forms, emptyList(), long)
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

        /**
         * [hiragana] with a long vowel added after each o or u sound that has none: お-row kana
         * take う or お, う-row kana う; the katakana forms take ー. At most [MAX_LONG_VOWELS]
         * places, so a long query stays a handful of lookups.
         */
        private fun longVowelForms(hiragana: String): List<String> {
            val places = hiragana.indices.filter { i ->
                val c = hiragana[i]
                val before = hiragana.getOrNull(i - 1)
                // The う of とう is its long vowel already.
                val isLongVowel = c in LONG_MARKS && before != null && (before in O_ROW || before in U_ROW)
                (c in O_ROW || c in U_ROW) && !isLongVowel && hiragana.getOrNull(i + 1).let { it == null || it !in LONG_MARKS }
            }.take(MAX_LONG_VOWELS)
            if (places.isEmpty()) return emptyList()
            fun expand(choices: (Char) -> List<String>): List<String> =
                places.fold(listOf(hiragana to 0)) { forms, place ->
                    // Each form carries how many characters the inserts before [place] added.
                    forms.flatMap { (form, shift) ->
                        choices(hiragana[place]).map { add ->
                            form.substring(0, place + shift + 1) + add + form.substring(place + shift + 1) to shift + add.length
                        }
                    }
                }.map { it.first }
            val hira = expand { if (it in O_ROW) listOf("", "う", "お") else listOf("", "う") }
            val kata = expand { listOf("", "ー") }.map(JapaneseText::toKatakana)
            return (hira + kata).distinct()
        }

        private const val O_ROW = "おこごそぞとどのほぼぽもよろをょ"
        private const val U_ROW = "うくぐすずつづぬふぶぷむゆるゅ"
        private const val LONG_MARKS = "うおー"
        private const val MAX_LONG_VOWELS = 4

        private const val WILDCARDS = "*?"
    }
}
