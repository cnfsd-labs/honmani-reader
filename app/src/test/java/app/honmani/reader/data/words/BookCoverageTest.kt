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

    private fun knowledge(known: Set<String> = emptySet(), learning: Set<String> = emptySet(), read: Map<String, ReadWord> = emptyMap()) =
        WordKnowledge(read = read, progress = emptyMap(), learning = learning, known = known)

    @Test
    fun aNewReaderKnowsNothing_howeverCommonTheWords() {
        assertEquals(BookCoverage(0f, newWords = 3), BookCoverage.of(book, WordKnowledge.EMPTY))
    }

    @Test
    fun onlyWordsMarkedKnownCount_byTheirUses() {
        // 猫 is Known (6 of 10 uses); 行く is only learning; 鼬 was read once, which is not known.
        val state = knowledge(known = setOf("猫"), learning = setOf("行く"), read = mapOf("鼬" to ReadWord(40_000, 1)))

        assertEquals(BookCoverage(0.6f, newWords = 1), BookCoverage.of(book, state))
    }

    @Test
    fun aWordReadOftenEnoughIsKnown_butNotWhileLearning() {
        val read = mapOf("行く" to ReadWord(50, WordProgress.KNOWN), "猫" to ReadWord(900, WordProgress.KNOWN))

        // 行く counts (3 of 10 uses); 猫 is read as often but is being learned; 鼬 is new.
        assertEquals(BookCoverage(0.3f, newWords = 1), BookCoverage.of(book, knowledge(read = read, learning = setOf("猫"))))
    }

    @Test
    fun everyWordKnownOrLearningLeavesNothingNew() {
        val state = knowledge(known = setOf("猫", "鼬"), learning = setOf("行く"))

        assertEquals(BookCoverage(0.7f, newWords = 0), BookCoverage.of(book, state))
    }

    @Test
    fun aBookWithoutWordsHasNoCoverage() {
        assertNull(BookCoverage.of(BookWords(emptyList(), IntArray(0), emptyList()), WordKnowledge.EMPTY))
    }

    @Test
    fun unknownWordsComeMostUsedFirst_cappedWithTheirTotal() {
        val all = UnknownWords.of(book, WordKnowledge.EMPTY)
        assertEquals(listOf("猫", "行く", "鼬"), all.words.map { it.key })
        assertEquals(3, all.total)
        // A cap keeps the most used and still counts the rest.
        assertEquals(UnknownWords(listOf(UnknownWord("猫", 6), UnknownWord("行く", 3)), 3), UnknownWords.of(book, WordKnowledge.EMPTY, limit = 2))
    }

    @Test
    fun unknownWordsLeaveOutKnownAndLearningWords_asTheCoverageCountsThem() {
        val state = knowledge(known = setOf("猫"), learning = setOf("行く"))

        assertEquals(UnknownWords(listOf(UnknownWord("鼬", 1)), 1), UnknownWords.of(book, state))
        assertEquals(BookCoverage.of(book, state)!!.newWords, UnknownWords.of(book, state).total)
        assertEquals(emptyList<UnknownWord>(), UnknownWords.of(book, knowledge(known = setOf("猫", "鼬"), learning = setOf("行く"))).words)
    }

    @Test
    fun ignoredWordsLeaveBothSidesOfTheCoverage_andTheNewWords() {
        // 猫 ignored (6 uses): of the other 4 uses only 行く (3) is known; 鼬 is the one new word.
        val state = WordKnowledge(
            read = emptyMap(),
            progress = emptyMap(),
            learning = emptySet(),
            known = setOf("行く"),
            ignored = setOf("猫"),
        )

        assertEquals(BookCoverage(0.75f, newWords = 1), BookCoverage.of(book, state))
        assertEquals(UnknownWords(listOf(UnknownWord("鼬", 1)), 1), UnknownWords.of(book, state))
    }
}
