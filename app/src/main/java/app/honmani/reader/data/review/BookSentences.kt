package app.honmani.reader.data.review

import app.honmani.reader.data.difficulty.BookWords
import app.honmani.reader.data.difficulty.Token
import app.honmani.reader.data.words.Sentences

/** A sentence of a book with a word in it: [surface] as written at [start] of [text]. */
data class FoundSentence(val text: String, val start: Int, val end: Int) {
    val surface: String get() = text.substring(start, end)
}

/**
 * Sentences of a book for words to learn before reading it: for each word, the sentence where
 * it first comes up from the reading point on (or its first use, if none is ahead). Only the
 * chapters needed are split into words, so this stays quick for a handful of words.
 */
object BookSentences {
    /** Longer sentences are cut to this many characters around the word. */
    const val MAX_LENGTH = 200

    /**
     * [chapters]: the book's text as the grader counts it (`Difficulty.measure`: positions are
     * shares of the summed lengths); [words]: its words; [keys]: the words wanted; [step]: the
     * reading point. [segment] splits a chapter into words with their dictionary forms.
     */
    fun find(
        chapters: List<String>,
        words: BookWords,
        keys: Collection<String>,
        step: Int,
        segment: (String, (Token) -> Unit) -> Unit,
    ): Map<String, FoundSentence> {
        val length = chapters.sumOf { it.length.toLong() }.coerceAtLeast(1)
        val index = words.keys.withIndex().associate { it.value to it.index }
        // The place wanted for each key, as a character offset into the whole text.
        val wanted = keys.mapNotNull { key ->
            val positions = words.positions[index[key] ?: return@mapNotNull null]
            if (positions.isEmpty()) return@mapNotNull null
            val at = positions.firstOrNull { it >= step } ?: positions[0]
            key to (at.toLong() * length / BookWords.POSITIONS)
        }.toMap()
        val found = HashMap<String, FoundSentence>()
        var offset = 0L
        for (chapter in chapters) {
            val end = offset + chapter.length
            // Positions are rounded down to a step: a use can sit up to one step past its offset.
            val slack = length / BookWords.POSITIONS + 1
            val here = wanted.filter { (key, at) -> key !in found && at < end && at + slack >= offset }
            if (here.isNotEmpty()) {
                val best = HashMap<String, Token>()
                segment(chapter) { token ->
                    val at = here[token.key] ?: return@segment
                    val current = best[token.key]
                    val distance = kotlin.math.abs(offset + token.start - at)
                    if (current == null || distance < kotlin.math.abs(offset + current.start - at)) best[token.key] = token
                }
                if (best.isNotEmpty()) {
                    val sentences = Sentences.of(chapter)
                    for ((key, token) in best) {
                        val range = sentences.firstOrNull { token.start in it } ?: continue
                        found[key] = cut(chapter, range, token.start, token.start + token.length)
                    }
                }
            }
            offset = end
        }
        return found
    }

    /** The sentence [range] of [text], at most [MAX_LENGTH] long and around the word at [from] until [to]. */
    private fun cut(text: String, range: IntRange, from: Int, to: Int): FoundSentence {
        var start = range.first
        var end = range.last + 1
        if (end - start > MAX_LENGTH) {
            start = maxOf(start, from - (MAX_LENGTH - (to - from)) / 2)
            end = minOf(end, start + MAX_LENGTH)
            start = maxOf(range.first, end - MAX_LENGTH)
            // Not half a surrogate pair at either cut.
            if (start > range.first && text[start].isLowSurrogate()) start++
            if (end < range.last + 1 && text[end - 1].isHighSurrogate()) end--
        }
        return FoundSentence(text.substring(start, end), from - start, to - start)
    }
}
