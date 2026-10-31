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
                .add("当たり前", 0, 698)
                .add("当り前", 0, 40_708)
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

    @Test
    fun anotherSpellingOfAWordReadOrBeingLearnedCountsAsIt() {
        val variants = SpellingVariants.Builder().add(1, 1448800, "当たり前").add(1, 1448800, "当り前").build()
        // 当たり前 was read: 当り前 is no new word.
        val read = WordKnowledge(
            read = mapOf("当たり前" to ReadWord(698, 4)),
            progress = emptyMap(),
            learning = emptySet(),
            variants = variants,
        )
        assertEquals(emptyList<WordMark>(), WordMarks.of("当り前", segmenter, read, WordMarksMode.NEW))
        // 当たり前 is being learned: 当り前 is marked as being learned.
        val learning = WordKnowledge(read = emptyMap(), progress = emptyMap(), learning = setOf("当たり前"), variants = variants)
        assertEquals(listOf(WordMark(0, 3, WordMark.Kind.LEARNING)), WordMarks.of("当り前", segmenter, learning, WordMarksMode.NEW))
        assertEquals(true, learning.isLearning("当り前"))
        assertEquals(false, learning.knows("当り前", 40_708, vocabulary = 50_000))
        assertEquals(true, read.knows("当り前", 40_708, vocabulary = 0))
    }

    @Test
    fun knownWordsAreMarkedOnlyWhenAsked_andNeverWhileBeingLearned() {
        // 猫 was looked up and learned; 食べる was read nine times but is being learned again.
        val known = WordKnowledge(
            read = mapOf("猫" to ReadWord(900, 3), "食べる" to ReadWord(187, 9)),
            progress = emptyMap(),
            learning = setOf("食べる"),
            known = setOf("猫"),
        )
        val text = "私は猫を食べた"
        fun marks(mode: WordMarksMode, knownWords: Boolean) =
            WordMarks.of(text, segmenter, known, mode, knownWords).map { text.substring(it.start, it.start + it.length) to it.kind }

        assertEquals(listOf("食べた" to WordMark.Kind.LEARNING), marks(WordMarksMode.LEARNING, false))
        assertEquals(
            listOf("猫" to WordMark.Kind.KNOWN, "食べた" to WordMark.Kind.LEARNING),
            marks(WordMarksMode.LEARNING, true),
        )
        // Word marks off: the known words are still found, nothing else is.
        assertEquals(listOf("猫" to WordMark.Kind.KNOWN), marks(WordMarksMode.OFF, true))
        assertEquals(emptyList<Pair<String, WordMark.Kind>>(), marks(WordMarksMode.OFF, false))
    }

    @Test
    fun aWordLearnedInWordsButNeverReadIsNewAndKnownAtOnce() {
        // 猫 was marked learned in Words and is not in any book read yet: the "+ New" grey and the hidden furigana both apply.
        val knowledge = WordKnowledge(read = emptyMap(), progress = emptyMap(), learning = emptySet(), known = setOf("猫"))
        val text = "猫"
        val marks = WordMarks.of(text, segmenter, knowledge, WordMarksMode.NEW, knownWords = true).map { it.kind }
        assertEquals(listOf(WordMark.Kind.NEW, WordMark.Kind.KNOWN), marks)
    }

    @Test
    fun aWordReadOftenEnoughIsKnownInAnySpelling() {
        val variants = SpellingVariants.Builder().add(1, 1448800, "当たり前").add(1, 1448800, "当り前").build()
        val knowledge = WordKnowledge(
            read = mapOf("当たり前" to ReadWord(698, WordProgress.KNOWN), "猫" to ReadWord(900, WordProgress.KNOWN - 1)),
            progress = emptyMap(),
            learning = emptySet(),
            variants = variants,
        )
        assertEquals(true, knowledge.isKnown("当り前"))
        assertEquals(false, knowledge.isKnown("猫"))
    }

    @Test
    fun aSentenceWithExactlyOneUnknownWordIsMarkedWhole_grammarAndNamesDoNotCount() {
        // 猫 was read; 鼬 and 当たり前 were not. は and を are grammar, メロス is a name no dictionary has.
        val text = "私は猫を食べた。メロスは鼬を見た。猫は当たり前の鼬だ。「私は鼬を。」猫は猫を"
        fun sentences(mode: WordMarksMode = WordMarksMode.OFF) =
            WordMarks.of(text, segmenter, knowledge, mode, sentences = true)
                .filter { it.kind == WordMark.Kind.SENTENCE }
                .map { text.substring(it.start, it.start + it.length) }

        // 食べる is being learned: one unknown word. The second sentence has 鼬 only (見る is not in
        // the test lexicon). The third has two (当たり前, 鼬). The fourth has 鼬 inside quotes.
        assertEquals(listOf("私は猫を食べた。", "メロスは鼬を見た。", "「私は鼬を。」"), sentences())
        // The tint's own mode makes no difference to the sentences.
        assertEquals(sentences(), sentences(WordMarksMode.NEW))
        assertEquals(
            emptyList<WordMark>(),
            WordMarks.of(text, segmenter, knowledge, WordMarksMode.OFF),
        )
    }

    @Test
    fun aWordLookedUpAsWrittenCountsAsTheUnknownWordOfItsSentence() {
        val learning = WordKnowledge(
            read = mapOf("猫" to ReadWord(900, 3)),
            progress = emptyMap(),
            learning = setOf("猫"),
            learningForms = setOf("猫"),
        )
        val text = "私は猫を。"
        assertEquals(
            listOf(WordMark(0, 5, WordMark.Kind.SENTENCE)),
            WordMarks.of(text, segmenter, learning, WordMarksMode.OFF, sentences = true),
        )
    }
}
