package app.honmani.reader.data.words

import app.honmani.reader.data.difficulty.BookWords
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BookCoverageTest {
    /** 猫 (rank 900) used 6 times, 行く (rank 50) 3 times, 鼬 (rank 40,000) once. */
    private val book = BookWords(
        keys = listOf("猫", "行く", "鼬"),
        ranks = intArrayOf(900, 50, 40_000),
        positions = listOf(ShortArray(6) { (it * 100).toShort() }, ShortArray(3), ShortArray(1)),
    )

    @Test
    fun runningWordsCount_byWhatTheReaderMetOrCommonEnough() {
        val knowledge = WordKnowledge(read = mapOf("鼬" to ReadWord(40_000, 1)), progress = emptyMap(), learning = emptySet())

        // 行く is within a vocabulary of 500, 鼬 was read; 猫 is neither.
        assertEquals(BookCoverage(0.4f, newWords = 1), BookCoverage.of(book, knowledge, vocabulary = 500))
        assertEquals(BookCoverage(1f, newWords = 0), BookCoverage.of(book, knowledge, vocabulary = 1_000))
    }

    @Test
    fun aWordBeingLearnedIsNotKnown_howeverCommon() {
        val knowledge = WordKnowledge(read = mapOf("猫" to ReadWord(900, 4)), progress = emptyMap(), learning = setOf("猫"))

        assertEquals(BookCoverage(0.3f, newWords = 2), BookCoverage.of(book, knowledge, vocabulary = 1_000))
    }

    @Test
    fun aBookWithoutWordsHasNoCoverage() {
        assertNull(BookCoverage.of(BookWords(emptyList(), IntArray(0), emptyList()), WordKnowledge.EMPTY, vocabulary = 1_000))
    }

    @Test
    fun unknownWordsComeMostUsedFirst_cappedWithTheirTotal() {
        val knowledge = WordKnowledge(read = mapOf("鼬" to ReadWord(40_000, 1)), progress = emptyMap(), learning = emptySet())

        // Vocabulary 100: 行く is common enough and 鼬 was read, so only 猫 is unknown.
        assertEquals(UnknownWords(listOf(UnknownWord("猫", 6)), 1), UnknownWords.of(book, knowledge, vocabulary = 100))
        // Nothing read, vocabulary 10: all three, by uses (6, 3, 1).
        val all = UnknownWords.of(book, WordKnowledge.EMPTY, vocabulary = 10)
        assertEquals(listOf("猫", "行く", "鼬"), all.words.map { it.key })
        assertEquals(3, all.total)
        // A cap keeps the most used and still counts the rest.
        assertEquals(UnknownWords(listOf(UnknownWord("猫", 6), UnknownWord("行く", 3)), 3), UnknownWords.of(book, WordKnowledge.EMPTY, vocabulary = 10, limit = 2))
        // A word being learned is unknown however common, and nothing unknown gives an empty list.
        val learning = WordKnowledge(read = emptyMap(), progress = emptyMap(), learning = setOf("行く"))
        assertEquals(listOf("行く"), UnknownWords.of(book, learning, vocabulary = 100_000).words.map { it.key })
        assertEquals(emptyList<UnknownWord>(), UnknownWords.of(book, WordKnowledge.EMPTY, vocabulary = 100_000).words)
    }
}
