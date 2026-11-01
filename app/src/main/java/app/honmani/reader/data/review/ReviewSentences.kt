package app.honmani.reader.data.review

import app.honmani.reader.data.words.WordEntity
import app.honmani.reader.data.words.WordLookupEntity

/** A sentence a word was met in, with the word's place in it, for the front of a review card. */
data class ReviewSentence(
    val text: String,
    val start: Int,
    val end: Int,
    val bookId: Long?,
    val bookTitle: String?,
)

object ReviewSentences {
    /**
     * The sentence for a review of [word]: each review of a word with several sentences shows
     * the next one ([reviews] counts the earlier gradings), newest first. Null when no lookup
     * kept a sentence (a word saved from the dictionary search).
     */
    fun pick(word: WordEntity, lookups: List<WordLookupEntity>, reviews: Int): ReviewSentence? {
        val found = lookups
            .map { ReviewSentence(it.sentence, it.surfaceStart, it.surfaceEnd, it.bookId, it.bookTitle) }
            .filter(::usable)
            .distinctBy { it.text }
        if (found.isNotEmpty()) return found[reviews.coerceAtLeast(0) % found.size]
        val latest = ReviewSentence(word.lastSentence, word.lastSurfaceStart, word.lastSurfaceEnd, null, word.lastBookTitle)
        return latest.takeIf(::usable)
    }

    private fun usable(sentence: ReviewSentence) =
        sentence.text.isNotBlank() && sentence.start >= 0 && sentence.end > sentence.start && sentence.end <= sentence.text.length
}
