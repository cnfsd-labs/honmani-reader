package app.honmani.reader.data.words

import app.honmani.reader.data.difficulty.Difficulty
import app.honmani.reader.data.difficulty.Lexicon
import app.honmani.reader.data.difficulty.Segmenter
import app.honmani.reader.lookup.WordClass
import org.junit.Assert.assertEquals
import org.junit.Test

class WordMarksTest {
    private val segmenter = Segmenter(
        Difficulty.addGrammar(
            Lexicon.Builder()
                .add("私", 0, 30)
                .add("は", 0, 2)
                .add("を", 0, 4)
                .add("猫", 0, 900)
                .add("ただ", 0, 300)
                .add("鼬", 0, 40_000)
                .add("食べる", WordClass.V1, 187),
        ).build(),
    )

    /** 食べる is being learned, 猫 was read before. */
    private val knowledge = WordKnowledge(
        read = mapOf("猫" to ReadWord(900, 3), "食べる" to ReadWord(187, 2)),
        progress = emptyMap(),
        learning = setOf("食べる"),
    )

    private fun marks(text: String, mode: WordMarksMode) =
        WordMarks.of(text, segmenter, knowledge, mode).map { text.substring(it.start, it.start + it.length) to it.kind }

    @Test
    fun wordsBeingLearnedAreMarked_inflectedAsWritten() {
        assertEquals(listOf("食べなかった" to WordMark.Kind.LEARNING), marks("私は猫を食べなかった", WordMarksMode.LEARNING))
    }

    @Test
    fun newWordsAreThoseNeverRead_namesLeftOut() {
        // 鼬, は, と and を are unread, however common; 猫 was read; メロス is no dictionary word.
        assertEquals(
            listOf(
                "は" to WordMark.Kind.NEW,
                "鼬" to WordMark.Kind.NEW,
                "を" to WordMark.Kind.NEW,
                "食べた" to WordMark.Kind.LEARNING,
            ),
            marks("メロスは猫鼬を食べた", WordMarksMode.NEW),
        )
    }

    @Test
    fun anUnreadWordIsNewWhateverTheVocabulary() {
        // 猫 (rank 900) unread is new, though any estimate would count it known.
        val unread = WordKnowledge(read = emptyMap(), progress = emptyMap(), learning = emptySet())
        val marks = WordMarks.of("猫", segmenter, unread, WordMarksMode.NEW)
        assertEquals(listOf(WordMark(0, 1, WordMark.Kind.NEW)), marks)
    }

    @Test
    fun offMarksNothing() {
        assertEquals(emptyList<Pair<String, WordMark.Kind>>(), marks("私は猫を食べなかった", WordMarksMode.OFF))
    }

    @Test
    fun aWordIsAlsoMarkedAsItWasWrittenWhereItWasLookedUp() {
        // Looked up as 猫 where the book wrote ねこ, and 食べ過ぎた as one word (the splitter has
        // no 過ぎる here): the splitter finds neither, the forms as written mark them.
        val looked = WordKnowledge(
            read = emptyMap(),
            progress = emptyMap(),
            learning = setOf("猫", "食べ過ぎる"),
            learningForms = setOf("ねこ", "食べ過ぎた"),
        )
        val text = "私はねこを食べ過ぎた"
        val marks = WordMarks.of(text, segmenter, looked, WordMarksMode.LEARNING)
            .map { text.substring(it.start, it.start + it.length) to it.kind }
        assertEquals(listOf("ねこ" to WordMark.Kind.LEARNING, "食べ過ぎた" to WordMark.Kind.LEARNING), marks)
    }

    @Test
    fun aFormAsWrittenMarksNothingWhereItEndsInsideAWord() {
        // た looked up on its own (inside だった): the た starting ただ is another word.
        val looked = WordKnowledge(
            read = emptyMap(),
            progress = emptyMap(),
            learning = setOf("た"),
            learningForms = setOf("た"),
        )
        val text = "私はただ猫を"
        val marks = WordMarks.of(text, segmenter, looked, WordMarksMode.LEARNING)
        assertEquals(emptyList<WordMark>(), marks)
    }
}
