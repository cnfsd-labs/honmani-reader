package app.honmani.reader.data.review

import app.honmani.reader.data.words.WordEntity
import app.honmani.reader.data.words.WordLookupEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReviewSentencesTest {
    private fun word(sentence: String = "", start: Int = 0, end: Int = 0) = WordEntity(
        expression = "猫", reading = "ねこ", gloss = "cat", lookupCount = 1, firstLookedUpAt = 0, lastLookedUpAt = 0,
        rankKey = 1.0, lastSentence = sentence, lastSurfaceStart = start, lastSurfaceEnd = end,
    )

    private fun lookup(sentence: String, start: Int, end: Int, book: String? = null) = WordLookupEntity(
        wordId = 1, surface = "猫", sentence = sentence, surfaceStart = start, surfaceEnd = end,
        bookId = if (book != null) 3 else null, bookTitle = book, lookedUpAt = 0,
    )

    @Test
    fun eachReviewShowsTheNextSentence() {
        val lookups = listOf(lookup("猫が来た。", 0, 1), lookup("黒い猫だ。", 2, 3, "本"), lookup("猫は寝る。", 0, 1))
        val shown = (0..3).map { ReviewSentences.pick(word(), lookups, it)!!.text }
        assertEquals(listOf("猫が来た。", "黒い猫だ。", "猫は寝る。", "猫が来た。"), shown)
        assertEquals("本", ReviewSentences.pick(word(), lookups, 1)!!.bookTitle)
    }

    @Test
    fun sentencesWithABadSpanOrRepeatedOnesAreSkipped() {
        val lookups = listOf(lookup("猫が来た。", 0, 1), lookup("猫が来た。", 0, 1), lookup("短い", 5, 9), lookup("", 0, 0))
        assertEquals(listOf("猫が来た。", "猫が来た。"), (0..1).map { ReviewSentences.pick(word(), lookups, it)!!.text })
    }

    @Test
    fun theWordsLatestSentenceIsTheFallback() {
        assertEquals("猫だ。", ReviewSentences.pick(word("猫だ。", 0, 1), emptyList(), 0)!!.text)
        assertNull(ReviewSentences.pick(word(), emptyList(), 0))
    }
}
