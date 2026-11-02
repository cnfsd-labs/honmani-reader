package app.honmani.reader.data.review

import app.honmani.reader.data.difficulty.BookWords
import app.honmani.reader.data.difficulty.Token
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RelevanceTest {
    private fun words(vararg entries: Pair<String, IntArray>) = BookWords(
        keys = entries.map { it.first },
        ranks = IntArray(entries.size) { 1000 },
        positions = entries.map { (_, at) -> ShortArray(at.size) { at[it].toShort() } },
    )

    @Test
    fun usesAheadCountNearOnesMoreAndThoseBehindNot() {
        val relevance = Relevance.of(
            words("猫" to intArrayOf(100, 200), "犬" to intArrayOf(5100), "鳥" to intArrayOf(4000, 4100, 4200), "魚" to intArrayOf(9000)),
            step = 5000,
        )
        assertEquals(0.0, relevance.of("猫"), 0.0)
        assertEquals(1.0 / (1.0 + 100.0 / Relevance.NEAR), relevance.of("犬"), 1e-9)
        assertTrue(relevance.of("犬") > relevance.of("魚"))
        assertEquals(0.0, relevance.of("鳥"), 0.0)
        assertEquals(0.0, relevance.of("馬"), 0.0)
    }

    @Test
    fun aVariantSpellingCounts() {
        val relevance = Relevance.of(words("飲む" to intArrayOf(10)), step = 0)
        assertTrue(relevance.of("呑む", listOf("飲む")) > 0)
    }

    @Test
    fun manyUsesAheadBeatOneFarOne() {
        val relevance = Relevance.of(words("五" to intArrayOf(3000, 3500, 4000), "犬" to intArrayOf(2600)), step = 2500)
        assertTrue(relevance.of("五") > relevance.of("犬"))
    }

    @Test
    fun stepFollowsProgress() {
        assertEquals(0, Relevance.step(-1f))
        assertEquals(2500, Relevance.step(0.25f))
        assertEquals(BookWords.POSITIONS, Relevance.step(2f))
    }

    @Test
    fun newCandidatesGoByScoreAndKeepTheirOrderOtherwise() {
        val candidates = listOf("a", "b", "c", "d").mapIndexed { i, e -> NewCandidate(i.toLong(), e, null) }
        val scores = mapOf("c" to 2.0, "d" to 1.0)
        assertEquals(listOf("c", "d", "a", "b"), orderNew(candidates) { scores[it] ?: 0.0 }.map { it.expression })
    }

    /** Splits text into single characters, each its own word: enough to find words in a test. */
    private val characters: (String, (Token) -> Unit) -> Unit = { text, onToken ->
        text.forEachIndexed { i, c -> if (!c.isWhitespace() && c !in "。「」") onToken(Token(i, 1, 1, c.toString())) }
    }

    @Test
    fun sentencesAreTakenFromTheReadingPointOn() {
        val chapters = listOf("猫が来た。犬が来た。", "猫を見た。鳥も見た。")
        val length = chapters.sumOf { it.length }
        fun step(offset: Int) = offset * BookWords.POSITIONS / length
        val words = words("猫" to intArrayOf(step(0), step(10)), "鳥" to intArrayOf(step(15)), "犬" to intArrayOf(step(5)))
        val found = BookSentences.find(chapters, words, listOf("猫", "鳥", "犬", "馬"), step = step(8), segment = characters)
        assertEquals(FoundSentence("猫を見た。", 0, 1), found["猫"])
        assertEquals(FoundSentence("鳥も見た。", 0, 1), found["鳥"])
        // Nothing ahead: its first use.
        assertEquals("犬が来た。", found["犬"]?.text)
        assertEquals("犬", found["犬"]?.surface)
        assertNull(found["馬"])
    }

    @Test
    fun aLongSentenceIsCutAroundTheWord() {
        val text = "あ".repeat(300) + "猫" + "い".repeat(300) + "。"
        val words = words("猫" to intArrayOf(300 * BookWords.POSITIONS / text.length))
        val found = BookSentences.find(listOf(text), words, listOf("猫"), step = 0, segment = characters).getValue("猫")
        assertEquals(BookSentences.MAX_LENGTH, found.text.length)
        assertEquals("猫", found.surface)
        assertTrue(found.start in 90..110)
    }
}
