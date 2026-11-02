package app.honmani.reader.data.review

import app.honmani.reader.data.difficulty.BookWords

/**
 * How soon and how often words come up in the book being read: each use ahead of the reading
 * point counts, a near one more than a far one (a use [NEAR] steps ahead counts half), so new
 * cards for the next pages come first. Uses before the reading point don't count.
 */
class Relevance(private val scores: Map<String, Double>) {
    /** The score of a word written [expression], also found through its [variants] spellings; 0 if it is not ahead. */
    fun of(expression: String, variants: Collection<String> = emptyList()): Double =
        scores[expression] ?: variants.maxOfOrNull { scores[it] ?: 0.0 } ?: 0.0

    companion object {
        /** A twentieth of the book. */
        const val NEAR = BookWords.POSITIONS / 20

        /** Scores of the [words] of a book read up to [step] (0 to [BookWords.POSITIONS]). */
        fun of(words: BookWords, step: Int): Relevance {
            val scores = HashMap<String, Double>()
            for (i in 0 until words.size) {
                var score = 0.0
                for (at in words.positions[i]) {
                    val distance = at - step
                    if (distance >= 0) score += 1.0 / (1.0 + distance.toDouble() / NEAR)
                }
                if (score > 0) scores[words.keys[i]] = score
            }
            return Relevance(scores)
        }

        /** Where a book read to [progress] (0 to 1) is, in [BookWords.POSITIONS] steps. */
        fun step(progress: Float): Int = (progress.coerceIn(0f, 1f) * BookWords.POSITIONS).toInt()
    }
}
