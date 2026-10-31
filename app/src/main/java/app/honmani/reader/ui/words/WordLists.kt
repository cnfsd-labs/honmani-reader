package app.honmani.reader.ui.words

import app.honmani.reader.data.words.WordEntity
import app.honmani.reader.data.words.WordKnowledge
import app.honmani.reader.data.words.WordProgress
import app.honmani.reader.data.words.WordStatus
import app.honmani.reader.lookup.LookupGroup

/**
 * The Words tab's two lists. [learning]: saved words not known yet, starred first, then the most
 * looked up (recently). [known]: saved words known (read often enough, or marked), and words
 * known from reading alone ([knownByReading], never looked up, most read first).
 */
data class WordLists(
    val learning: List<WordEntity>,
    val known: List<WordEntity>,
    val knownByReading: List<Pair<String, Int>>,
    /** Progress in steps of [WordProgress.KNOWN], by word id. */
    val steps: Map<Long, Int>,
) {
    val knownCount: Int get() = known.size + knownByReading.size

    companion object {
        /** [words] in the database's order; without [knowledge] (still worked out) by the learned mark alone. */
        fun of(words: List<WordEntity>, knowledge: WordKnowledge?): WordLists {
            if (knowledge == null) {
                val (known, learning) = words.partition { it.status == WordStatus.LEARNED }
                return WordLists(learning.sortedByDescending { it.starred }, known, emptyList(), emptyMap())
            }
            val (known, learning) = words.partition(knowledge::isKnown)
            return WordLists(
                learning = learning.sortedWith(compareByDescending<WordEntity> { it.starred }.thenByDescending { it.rankKey }),
                known = known.sortedBy { it.expression },
                // A word looked up in kana is read as its reading (わかる for 分かる): not a word of its own.
                knownByReading = knowledge.knownByReading(
                    words.flatMapTo(HashSet()) { word ->
                        if (knowledge.progress[word.id]?.kana == true) listOf(word.expression, word.reading) else listOf(word.expression)
                    },
                ),
                steps = words.associate { it.id to knowledge.steps(it) },
            )
        }
    }
}

/**
 * A word known from reading alone, opened in the known list: what the dictionaries say about it
 * ([entry], null if none has it), or null while they are looked up ([loading]).
 */
data class ReadWordDetails(val expression: String, val loading: Boolean = true, val entry: LookupGroup? = null) {
    companion object {
        /**
         * The entry for [key], a dictionary form from the book's words, among the results of
         * looking it up: one covering all of it, the uninflected word written that way first,
         * then one read that way (a word read in kana); null if none covers it.
         */
        fun entryFor(key: String, groups: List<LookupGroup>): LookupGroup? {
            val whole = groups.filter { it.source == key }
            return whole.firstOrNull { it.expression == key && it.inflections.isEmpty() }
                ?: whole.firstOrNull { it.reading == key && it.inflections.isEmpty() }
                ?: whole.firstOrNull { it.expression == key || it.reading == key }
        }
    }
}

/**
 * Search in the Words tab: [words] whose word, reading or meaning contains [query], ignoring case
 * (and surrounding spaces); an empty query keeps all.
 */
fun filterWords(words: List<WordEntity>, query: String): List<WordEntity> {
    val wanted = query.trim().lowercase()
    if (wanted.isEmpty()) return words
    return words.filter {
        it.expression.lowercase().contains(wanted) || it.reading.lowercase().contains(wanted) ||
            it.gloss.lowercase().contains(wanted)
    }
}

/** The same for words known from reading alone, which have only the word itself. */
fun filterReadWords(words: List<Pair<String, Int>>, query: String): List<Pair<String, Int>> {
    val wanted = query.trim().lowercase()
    if (wanted.isEmpty()) return words
    return words.filter { it.first.lowercase().contains(wanted) }
}
