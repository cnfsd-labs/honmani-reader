package app.honmani.reader.known

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.random.Random

/** One answer of the placement quiz: the word at [rank] (1 = most common) was known or not. */
data class QuizAnswer(val rank: Int, val known: Boolean)

/**
 * The maths of the "Quick quiz": which words to ask, and from the answers where the reader's
 * vocabulary seems to end. Words are asked at ranks spread evenly on a log scale, since a common
 * word teaches far more than a rare one; the estimate is the rank that fits the answers best.
 */
object PlacementQuiz {
    /** About how many words are asked (a few fewer when two draws land on one word). */
    const val QUESTIONS = 45

    /** The most common words are not asked: nobody who reads Japanese misses them. */
    const val FIRST_RANK = 30

    /** Words further down than this are not asked, nor offered by the slider. */
    const val MAX_WORDS = 15000

    /**
     * Ranks to ask about among [size] ranked words: one random rank in each of [count] equal
     * steps of the log scale between [FIRST_RANK] and [size], most common first, no rank twice.
     */
    fun sampleRanks(size: Int, random: Random, count: Int = QUESTIONS): List<Int> {
        if (size < FIRST_RANK || count <= 0) return emptyList()
        val low = ln(FIRST_RANK.toDouble())
        val step = (ln(size.toDouble() + 1) - low) / count
        return (0 until count)
            .map { (exp(low + step * (it + random.nextDouble())).toInt()).coerceIn(FIRST_RANK, size) }
            .distinct()
    }

    /** How many ranks past a drawn one the quiz looks for a word it may ask. */
    const val MAX_STEP = 40

    /**
     * Moves each drawn rank in [ranks] forward to the nearest rank, within [maxStep], for which
     * [askable] holds (words that carry no information, such as particles, are skipped); a rank
     * is used once and the ranks stay those of the full list, so [estimate] is not skewed by the
     * skipped words. [askable] gets all ranks of one round at once and answers for each, in order.
     * Ranks with no askable word within reach are dropped.
     */
    suspend fun stepToAskable(
        ranks: List<Int>,
        size: Int,
        maxStep: Int = MAX_STEP,
        askable: suspend (List<Int>) -> List<Boolean>,
    ): List<Int> {
        val chosen = sortedSetOf<Int>()
        var open = ranks
        for (step in 0..maxStep) {
            val tried = open.filter { it + step <= size }
            if (tried.isEmpty()) break
            val answers = askable(tried.map { it + step })
            // A rank another draw already took is stepped past, like one that is not askable.
            open = tried.filterIndexed { i, rank -> !(answers[i] && chosen.add(rank + step)) }
        }
        return chosen.toList()
    }

    /**
     * The rank where knowing turns into not knowing: the cut-off that disagrees with the fewest
     * answers (a known word below it or an unknown one above it counts against it); between equal
     * cut-offs the middle one, and within the gap the geometric middle of the two ranks beside it.
     * Null without answers.
     */
    fun estimate(answers: List<QuizAnswer>, size: Int): Int? {
        if (answers.isEmpty()) return null
        val sorted = answers.sortedBy { it.rank }
        // Cutting after the first k answers: the known ones among them fit, so do the unknown ones after.
        var fits = sorted.count { !it.known }
        val scores = IntArray(sorted.size + 1)
        scores[0] = fits
        for (k in 1..sorted.size) {
            fits += if (sorted[k - 1].known) 1 else -1
            scores[k] = fits
        }
        val best = scores.max()
        val cuts = scores.indices.filter { scores[it] == best }
        val cut = cuts[cuts.size / 2]
        val low = if (cut == 0) 1.0 else sorted[cut - 1].rank.toDouble()
        val high = if (cut == sorted.size) size.toDouble().coerceAtLeast(low) else sorted[cut].rank.toDouble()
        return sqrt(low * high).roundToInt().coerceIn(1, size.coerceAtLeast(1))
    }

    /** [value] rounded to a tidy number to show and to move a slider in: 10 under 100, 50 under 1000, else 100. */
    fun tidy(value: Int): Int {
        val unit = if (value < 100) 10 else if (value < 1000) 50 else 100
        return ((value + unit / 2) / unit * unit).coerceAtLeast(unit)
    }
}
