package app.honmani.reader.readaloud

/** The part of the audio that holds a sentence: [startMs] until [endMs]. */
data class ClipRange(val startMs: Long, val endMs: Long) {
    val lengthMs: Long get() = endMs - startMs
}

/**
 * Finds where in an audiobook a sentence of the book is heard, from the subtitles matched to the
 * book ([AudiobookAlignment]). Cues need not line up with sentences: the range runs from the start
 * of the first cue that overlaps the sentence to the end of the last one. Nothing is returned when
 * there is no sound worth using: the subtitles are doubtful, the sentence is not in the book, the
 * cues overlapping it cover too little of it, or the range is longer than [MAX_CLIP_MS].
 *
 * A sentence that occurs more than once in the book (or inside a longer sentence) is taken from the
 * occurrence whose cues reach the least beyond it, the first such one: a short sentence inside a
 * longer one would otherwise bring the longer one's whole cues. That is deterministic and cheap;
 * the same words are heard either way, only the voice's tone can differ.
 */
object CueLocator {
    /** Longer than this is not a sentence but a stretch of the book; the card goes without clip. */
    const val MAX_CLIP_MS = 30_000L

    /** Fewer characters than this (counted, folded) are too common in a book to find reliably. */
    const val MIN_SENTENCE_LENGTH = 4

    /** At least this share of the sentence must lie in cues that were found in the book. */
    private const val MIN_COVERAGE = 0.5

    /** How many occurrences are compared; later ones are not worth the time. */
    private const val MAX_OCCURRENCES = 50

    /**
     * [book] is the book's counted characters folded with [CueAligner.fold], as [AudiobookAlignment.of]
     * was given; [sentence] is as stored with a saved word.
     */
    fun locate(alignment: AudiobookAlignment, book: IntArray, sentence: String): ClipRange? {
        if (alignment.doubtful) return null
        val needle = CueAligner.reduce(sentence)
        if (needle.size < MIN_SENTENCE_LENGTH) return null
        var from = 0
        var best: Found? = null
        var seen = 0
        while (seen < MAX_OCCURRENCES) {
            val at = indexOf(book, needle, from)
            if (at < 0) break
            seen++
            range(alignment, at, at + needle.size)?.let { found ->
                if (found.excess == 0) return found.range
                if (best == null || found.excess < best.excess) best = found
            }
            from = at + 1
        }
        return best?.range
    }

    /** A clip for one occurrence; [excess] is how many characters its cues reach beyond the sentence. */
    private class Found(val range: ClipRange, val excess: Int)

    /** The range of the cues overlapping the book's characters [start] until [end], if they cover enough. */
    private fun range(alignment: AudiobookAlignment, start: Int, end: Int): Found? {
        var from = Long.MAX_VALUE
        var first = Int.MAX_VALUE
        var last = Int.MIN_VALUE
        var to = Long.MIN_VALUE
        var covered = 0
        var lastEnd = start
        for (cue in alignment.cues) {
            val span = cue.span ?: continue
            if (span.end <= start || span.start >= end) continue
            from = minOf(from, cue.startMs)
            to = maxOf(to, cue.endMs)
            first = minOf(first, span.start)
            last = maxOf(last, span.end)
            // Cues are in book order and rarely overlap; count each character once.
            val a = maxOf(span.start, start, lastEnd)
            val b = minOf(span.end, end)
            if (b > a) covered += b - a
            lastEnd = maxOf(lastEnd, b)
        }
        if (to <= from || covered < (end - start) * MIN_COVERAGE) return null
        val range = ClipRange(from, to).takeIf { it.lengthMs <= MAX_CLIP_MS } ?: return null
        return Found(range, maxOf(0, start - first) + maxOf(0, last - end))
    }

    private fun indexOf(text: IntArray, needle: IntArray, from: Int): Int {
        val last = text.size - needle.size
        var i = from
        while (i <= last) {
            if (text[i] == needle[0]) {
                var k = 1
                while (k < needle.size && text[i + k] == needle[k]) k++
                if (k == needle.size) return i
            }
            i++
        }
        return -1
    }
}
