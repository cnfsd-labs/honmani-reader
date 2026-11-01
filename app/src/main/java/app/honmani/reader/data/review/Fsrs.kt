package app.honmani.reader.data.review

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/** How well a card was remembered. The review screen only offers Again and Good; Hard comes from reading. */
enum class Grade(val value: Int) {
    AGAIN(1),
    HARD(2),
    GOOD(3),

    /** Kept for the scheduler and the optional Easy button; unused by the screen so far. */
    EASY(4),
    ;

    val passed: Boolean get() = this != AGAIN

    companion object {
        fun of(value: Int): Grade = entries.first { it.value == value }
    }
}

/** What FSRS remembers about a card: [stability] in days (the time for recall to fall to 90%) and [difficulty] 1..10. */
data class Memory(val stability: Double, val difficulty: Double)

/**
 * FSRS-6, the spaced-repetition model of the open-spaced-repetition project (formulas in the
 * wiki "The Algorithm" of github.com/open-spaced-repetition/fsrs4anki, parameters as in the
 * py-fsrs package, `DEFAULT_PARAMETERS`). Written from the published formulas, not from any
 * implementation's code. Pure Kotlin, no Android.
 *
 * Time is in days (fractional). Retrievability R is the chance of recalling a card [t] days after
 * its last review: `R = (1 + FACTOR * t / S) ^ DECAY`, with `FACTOR` chosen so R(S, S) = 90%.
 */
object Fsrs {
    /** The default weights w0..w20 of FSRS-6. */
    val DEFAULT_WEIGHTS: DoubleArray = doubleArrayOf(
        0.2172, 1.1771, 3.2602, 16.1507, 7.0114, 0.57, 2.0966, 0.0069, 1.5261, 0.112, 1.0178,
        1.849, 0.1133, 0.3127, 2.2934, 0.2191, 3.0004, 0.7536, 0.3332, 0.1437, 0.2,
    )

    const val DESIRED_RETENTION = 0.9
    const val MIN_STABILITY = 0.001
    const val MAX_INTERVAL_DAYS = 36_500

    fun retrievability(stability: Double, elapsedDays: Double, w: DoubleArray = DEFAULT_WEIGHTS): Double {
        val decay = -w[20]
        return (1 + factor(decay) * max(elapsedDays, 0.0) / stability).pow(decay)
    }

    /** Whole days until recall is expected to fall to [retention]: at least 1. At 90% this is the stability. */
    fun interval(stability: Double, retention: Double = DESIRED_RETENTION, w: DoubleArray = DEFAULT_WEIGHTS): Int {
        val decay = -w[20]
        val days = stability / factor(decay) * (retention.pow(1 / decay) - 1)
        return days.roundToInt().coerceIn(1, MAX_INTERVAL_DAYS)
    }

    /** The memory after the first review of a new card. */
    fun first(grade: Grade, w: DoubleArray = DEFAULT_WEIGHTS): Memory =
        Memory(max(w[grade.value - 1], MIN_STABILITY), initialDifficulty(grade, w).coerceIn(1.0, 10.0))

    /**
     * The memory after reviewing a card [elapsedDays] after its previous review. A review the same
     * day (under one day) uses the short-term stability formula.
     */
    fun next(memory: Memory, grade: Grade, elapsedDays: Double, w: DoubleArray = DEFAULT_WEIGHTS): Memory {
        val s = memory.stability
        val d = memory.difficulty
        val r = retrievability(s, elapsedDays, w)
        val stability = when {
            elapsedDays < 1 -> shortTerm(s, grade, w)
            grade == Grade.AGAIN -> forgotten(s, d, r, w)
            else -> recalled(s, d, r, grade, w)
        }
        return Memory(max(stability, MIN_STABILITY), nextDifficulty(d, grade, w))
    }

    private fun factor(decay: Double) = 0.9.pow(1 / decay) - 1

    private fun initialDifficulty(grade: Grade, w: DoubleArray) = w[4] - exp(w[5] * (grade.value - 1)) + 1

    private fun nextDifficulty(d: Double, grade: Grade, w: DoubleArray): Double {
        val delta = -w[6] * (grade.value - 3)
        val damped = d + delta * (10 - d) / 9
        // Mean reversion towards the difficulty of an Easy first review.
        return (w[7] * initialDifficulty(Grade.EASY, w) + (1 - w[7]) * damped).coerceIn(1.0, 10.0)
    }

    private fun recalled(s: Double, d: Double, r: Double, grade: Grade, w: DoubleArray): Double {
        val hardPenalty = if (grade == Grade.HARD) w[15] else 1.0
        val easyBonus = if (grade == Grade.EASY) w[16] else 1.0
        return s * (1 + exp(w[8]) * (11 - d) * s.pow(-w[9]) * (exp(w[10] * (1 - r)) - 1) * hardPenalty * easyBonus)
    }

    private fun forgotten(s: Double, d: Double, r: Double, w: DoubleArray): Double {
        val after = w[11] * d.pow(-w[12]) * ((s + 1).pow(w[13]) - 1) * exp(w[14] * (1 - r))
        return min(after, s / exp(w[17] * w[18]))
    }

    private fun shortTerm(s: Double, grade: Grade, w: DoubleArray): Double {
        val after = s * exp(w[17] * (grade.value - 3 + w[18])) * s.pow(-w[19])
        return if (grade.passed) max(after, s) else after
    }

    /** Natural log of 0.9, for tests that check the factor. */
    internal val LN_090 = ln(0.9)
}
