package app.honmani.reader.readaloud

import app.honmani.reader.data.sync.EpubPositions
import java.text.Normalizer

/** Where a subtitle is in the book: the counted characters [start] until [end] (ttu's character count). */
data class CueSpan(val start: Int, val end: Int)

/**
 * Finds each subtitle's passage in the book's text, for an audiobook that the book follows.
 *
 * Both sides are reduced to the characters ttu counts (letters, digits, kana, kanji: no blanks,
 * punctuation or furigana) and folded so that half-width and full-width forms, upper and lower
 * case, and katakana and hiragana compare equal. The book is then searched in order: a subtitle is
 * looked for just after the previous one, first close by, then further on, and a subtitle that
 * differs a little (subtitles made by speech recognition rarely match the book letter for letter)
 * still matches if it has few enough differences. The further from where the last one ended, the
 * fewer differences are tolerated, so a stray subtitle can't pull the later ones off course.
 * One that doesn't match stays null and changes nothing. After several in a row, the rest of the
 * book is searched by short letter groups, in case a stretch of the book is not in the audio.
 *
 * Positions are counted characters of the whole book, so the page can find the passage in its DOM
 * the way highlights are placed (`highlights.js`).
 */
object CueAligner {
    /** The text of a gaiji picture in [book]: never equal to anything. */
    const val GAIJI = -1

    private const val MIN_LENGTH = 3
    private const val OVERLAP = 6
    private const val NEAR_WINDOW = 60
    private const val WIDE_WINDOW = 1_500
    private const val GLOBAL_AFTER = 3
    private const val GRAM = 3

    /** A character of the book folded for comparing; for a counted character only. */
    fun fold(cp: Int): Int {
        var c = cp
        if (c in 0xFF61..0xFFEF || c in 0xFF01..0xFF5E) {
            val normal = Normalizer.normalize(String(Character.toChars(c)), Normalizer.Form.NFKC)
            if (normal.codePointCount(0, normal.length) == 1) c = normal.codePointAt(0)
        }
        if (c in 'A'.code..'Z'.code) c += 32
        if (c in 0x30A1..0x30F6) c -= 0x60
        return c
    }

    /** A subtitle reduced to what compares: its counted characters, folded. */
    fun reduce(text: String): IntArray {
        val out = ArrayList<Int>(text.length)
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            val folded = fold(cp)
            if (EpubPositions.isCounted(cp) || EpubPositions.isCounted(folded)) out += folded
        }
        return out.toIntArray()
    }

    /** One entry per subtitle, in the order given: its span in [book], or null if it wasn't found. */
    fun align(book: IntArray, cues: List<String>): List<CueSpan?> {
        val reduced = cues.map(::reduce)
        val spans = ArrayList<CueSpan?>(cues.size)
        val search = Search(book)
        var cursor = 0
        var misses = 0
        for (cue in reduced) {
            val span = if (cue.size < MIN_LENGTH) null else search.find(cue, cursor, misses >= GLOBAL_AFTER)
            spans += span
            if (span != null) {
                cursor = span.end
                misses = 0
            } else if (cue.size >= MIN_LENGTH) {
                misses++
            }
        }
        return spans
    }

    private class Search(val book: IntArray) {
        private var cost = IntArray(0)
        private var startOf = IntArray(0)
        private var lastCost = IntArray(0)
        private var lastStart = IntArray(0)
        private var grams: LongArray? = null

        fun find(cue: IntArray, cursor: Int, global: Boolean): CueSpan? {
            val m = cue.size
            val from = maxOf(0, cursor - OVERLAP)
            near(m)?.let { limit ->
                best(cue, from, minOf(book.size, cursor + m * 2 + NEAR_WINDOW), limit)?.let { return it }
            }
            best(cue, from, minOf(book.size, cursor + WIDE_WINDOW), wide(m))?.let { return it }
            return if (global) farAway(cue, from) else null
        }

        /** Differences tolerated close to where the last subtitle ended. */
        private fun near(m: Int): Int? = if (m < 6) m / 6 else m / 4

        private fun wide(m: Int) = m / 8

        private fun far(m: Int) = m / 10

        /** The cheapest approximate occurrence of [cue] in book[from, to), with at most [limit] differences. */
        private fun best(cue: IntArray, from: Int, to: Int, limit: Int): CueSpan? {
            val n = to - from
            val m = cue.size
            if (n <= 0 || m - limit > n) return null
            if (cost.size < n + 1) {
                cost = IntArray(n + 1)
                startOf = IntArray(n + 1)
                lastCost = IntArray(n + 1)
                lastStart = IntArray(n + 1)
            }
            var prev = cost
            var prevStart = startOf
            var cur = lastCost
            var curStart = lastStart
            for (j in 0..n) {
                prev[j] = 0
                prevStart[j] = j
            }
            for (i in 1..m) {
                val c = cue[i - 1]
                cur[0] = i
                curStart[0] = 0
                for (j in 1..n) {
                    var bestCost = prev[j - 1] + if (book[from + j - 1] == c) 0 else 1
                    var bestStart = prevStart[j - 1]
                    val skipCue = prev[j] + 1
                    if (skipCue < bestCost) {
                        bestCost = skipCue
                        bestStart = prevStart[j]
                    }
                    val skipBook = cur[j - 1] + 1
                    if (skipBook < bestCost) {
                        bestCost = skipBook
                        bestStart = curStart[j - 1]
                    }
                    cur[j] = bestCost
                    curStart[j] = bestStart
                }
                val swap = prev
                prev = cur
                cur = swap
                val swapStart = prevStart
                prevStart = curStart
                curStart = swapStart
            }
            var bestJ = -1
            var bestCost = limit + 1
            for (j in 1..n) {
                if (prev[j] < bestCost) {
                    bestCost = prev[j]
                    bestJ = j
                }
            }
            if (bestJ < 0) return null
            val start = from + prevStart[bestJ]
            val end = from + bestJ
            return if (end > start) CueSpan(start, end) else null
        }

        private fun gramKey(a: Int, b: Int, c: Int): Int = (a * 31 + b) * 31 + c

        private fun index(): LongArray = grams ?: run {
            val count = maxOf(0, book.size - GRAM + 1)
            val keys = LongArray(count)
            for (p in 0 until count) {
                keys[p] = (gramKey(book[p], book[p + 1], book[p + 2]).toLong() shl 32) or p.toLong()
            }
            keys.sort()
            keys.also { grams = it }
        }

        /** Positions of the book's letter groups equal to the one at [key], from [min] on. */
        private inline fun forEachPosition(key: Int, min: Int, action: (Int) -> Unit) {
            val keys = index()
            val low = key.toLong() shl 32
            var lo = 0
            var hi = keys.size
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (keys[mid] < low) lo = mid + 1 else hi = mid
            }
            var i = lo
            while (i < keys.size && (keys[i] ushr 32).toInt() == key) {
                val p = (keys[i] and 0xFFFFFFFFL).toInt()
                if (p >= min) action(p)
                i++
            }
        }

        private fun farAway(cue: IntArray, from: Int): CueSpan? {
            val m = cue.size
            if (m < GRAM + 3) return null
            val votes = HashMap<Int, Int>()
            for (offset in 0..m - GRAM) {
                val key = gramKey(cue[offset], cue[offset + 1], cue[offset + 2])
                var seen = 0
                forEachPosition(key, from) { p ->
                    // A group that is everywhere (a run of the same kana) tells nothing.
                    if (++seen <= MAX_OCCURRENCES) votes.merge((p - offset) / BUCKET, 1, Int::plus)
                }
            }
            val top = votes.maxByOrNull { it.value } ?: return null
            if (top.value < maxOf(3, (m - GRAM + 1) / 3)) return null
            val centre = top.key * BUCKET
            return best(cue, maxOf(from, centre - m), minOf(book.size, centre + BUCKET + m * 2), far(m))
        }

        private companion object {
            const val BUCKET = 16
            const val MAX_OCCURRENCES = 40
        }
    }
}
