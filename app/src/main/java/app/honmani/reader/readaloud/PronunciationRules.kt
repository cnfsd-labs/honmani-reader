package app.honmani.reader.readaloud

/** A sentence ready for the voice: [text] is what is spoken, [origin] maps it back to the page's text. */
class SpokenText(val text: String, private val origin: IntArray?) {
    /** Whether anything was replaced. */
    val changed: Boolean get() = origin != null

    /** The character of the original sentence that the spoken character [offset] belongs to. */
    fun originalOffset(offset: Int): Int {
        val map = origin ?: return offset
        return map[offset.coerceIn(0, map.lastIndex)]
    }
}

/**
 * The reader's "read this as that" list for one book: [apply] swaps each text for its reading
 * before the sentence goes to the voice. Longest text first, left to right, one pass (a reading is
 * never replaced again). The voice reports where it is in the spoken text, so every spoken
 * character remembers where it came from ([SpokenText.originalOffset]) and the page's mark still
 * lands on the right word.
 */
class PronunciationRules(pairs: List<Pair<String, String>>) {
    private val rules: List<Pair<String, String>> =
        pairs.filter { it.first.isNotEmpty() }.distinctBy { it.first }.sortedByDescending { it.first.length }

    val isEmpty: Boolean get() = rules.isEmpty()

    fun apply(text: String): SpokenText {
        if (rules.isEmpty()) return SpokenText(text, null)
        val out = StringBuilder(text.length)
        val origin = ArrayList<Int>(text.length + 1)
        var changed = false
        var i = 0
        while (i < text.length) {
            val rule = rules.firstOrNull { text.startsWith(it.first, i) }
            if (rule == null) {
                out.append(text[i])
                origin.add(i)
                i++
            } else {
                out.append(rule.second)
                repeat(rule.second.length) { origin.add(i) }
                changed = true
                i += rule.first.length
            }
        }
        origin.add(text.length)
        return if (changed) SpokenText(out.toString(), origin.toIntArray()) else SpokenText(text, null)
    }

    companion object {
        val EMPTY = PronunciationRules(emptyList())

        /**
         * The replacement a corrected furigana stands for (`飛[と]び 散[は]る`): the word as the book
         * writes it, and the same word in kana. Null when the form is not one.
         */
        fun fromFurigana(expression: String, furigana: String): Pair<String, String>? {
            val segments = app.honmani.reader.lookup.FuriganaOverride.parse(furigana, expression) ?: return null
            val reading = segments.joinToString("") { it.ruby ?: it.text }
            return if (reading == expression) null else expression to reading
        }
    }
}
