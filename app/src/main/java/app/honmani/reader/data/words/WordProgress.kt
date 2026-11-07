package app.honmani.reader.data.words

import kotlin.math.roundToInt

/**
 * A looked-up word's progress, with what it was worked out from (to tell what is new next time).
 * [kana]: the word was looked up where the book wrote it in kana (わかった for 分かる), so its reading
 * read in kana counts as reading it; it stays so, so the count of reads never drops back.
 */
data class WordStep(val steps: Int, val reads: Int, val lookups: Int, val learned: Boolean, val kana: Boolean = false)

/**
 * How well the reader knows a word, in steps from 0 to [KNOWN]: each time the word is read is a
 * step up, and each lookup takes [LOOKUP_COST] steps back (the read it happened in and two more:
 * a word looked up again is being forgotten). A word read [KNOWN] times without a lookup is known.
 * Marking a word as learned makes it known at once; a lookup afterwards counts as usual.
 *
 * Reads come from the books' word positions up to the reader's place, lookups from the word list;
 * between two updates the reads are counted before the lookups.
 */
object WordProgress {
    const val KNOWN = 9
    const val LOOKUP_COST = 3

    fun percent(steps: Int): Int = (steps * 100.0 / KNOWN).roundToInt()

    /**
     * The one progress a Learning word shows, 0 to 1. Reading's [steps] count first and a word in
     * review fills the rest by its [memory] (0 to 1): entering review loses nothing, forgetting never
     * goes below what reading earned, and it is full exactly when the word is known by either.
     */
    fun shown(steps: Int, memory: Float?): Float {
        val read = steps.coerceIn(0, KNOWN).toFloat() / KNOWN
        return read + (1f - read) * (memory ?: 0f).coerceIn(0f, 1f)
    }

    /** [progress] as a percentage; 100 only when it really is full. */
    fun shownPercent(progress: Float): Int =
        if (progress >= 1f) 100 else (progress * 100).roundToInt().coerceAtMost(99)

    /** Words never looked up: known once read [KNOWN] times. */
    fun steps(reads: Int): Int = reads.coerceIn(0, KNOWN)

    /** [previous] (null: first seen) brought up to [reads], [lookups] and the learned mark. */
    fun update(previous: WordStep?, reads: Int, lookups: Int, learned: Boolean): WordStep {
        if (previous == null) {
            val steps = if (learned) KNOWN else (reads - LOOKUP_COST * lookups).coerceIn(0, KNOWN)
            return WordStep(steps, reads, lookups, learned)
        }
        // Fewer reads than before (a book removed or started over): nothing to add.
        var steps = (previous.steps + (reads - previous.reads).coerceAtLeast(0)).coerceAtMost(KNOWN)
        val newLookups = (lookups - previous.lookups).coerceAtLeast(0)
        steps = (steps - LOOKUP_COST * newLookups).coerceAtLeast(0)
        if (learned && !previous.learned) steps = KNOWN
        // "Still learning" by hand: one lookup's worth below known. A lookup also clears the mark;
        // it already took its steps.
        if (!learned && previous.learned && newLookups == 0) steps = steps.coerceAtMost(KNOWN - LOOKUP_COST)
        return WordStep(steps, reads, lookups, learned)
    }
}
