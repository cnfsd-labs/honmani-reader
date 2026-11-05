package app.honmani.reader.readaloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PronunciationRulesTest {
    @Test
    fun aNameIsReplacedByItsReading() {
        val rules = PronunciationRules(listOf("雨宮" to "あめみや"))
        val spoken = rules.apply("雨宮は笑った。")
        assertEquals("あめみやは笑った。", spoken.text)
        assertTrue(spoken.changed)
    }

    @Test
    fun theLongestTextWinsAndAReadingIsNotReplacedAgain() {
        val rules = PronunciationRules(listOf("雨" to "あめ", "雨宮" to "うみや", "あめ" to "X"))
        assertEquals("うみやと あめ", rules.apply("雨宮と 雨").text)
        assertEquals("あめ", rules.apply("雨").text)
    }

    @Test
    fun nothingToReplaceLeavesTheSentenceAlone() {
        val spoken = PronunciationRules(listOf("雨宮" to "あめみや")).apply("彼は笑った。")
        assertEquals("彼は笑った。", spoken.text)
        assertFalse(spoken.changed)
        assertEquals(3, spoken.originalOffset(3))
        assertFalse(PronunciationRules.EMPTY.apply("文").changed)
    }

    @Test
    fun spokenOffsetsMapBackToTheBooksText() {
        // 雨宮(2) -> あめみや(4): spoken 0..3 are the name, spoken 4 is は, which was at 2.
        val spoken = PronunciationRules(listOf("雨宮" to "あめみや")).apply("雨宮は笑った。")
        assertEquals(0, spoken.originalOffset(0))
        assertEquals(0, spoken.originalOffset(3))
        assertEquals(2, spoken.originalOffset(4))
        assertEquals(3, spoken.originalOffset(5))
        assertEquals(7, spoken.originalOffset(100))
    }

    @Test
    fun furiganaCorrectionsBecomeReplacements() {
        assertEquals("飛び散る" to "とびちはる", PronunciationRules.fromFurigana("飛び散る", "飛[と]び 散[ちは]る"))
        assertEquals("北" to "き", PronunciationRules.fromFurigana("北", "北[き]"))
        assertNull(PronunciationRules.fromFurigana("北", ""))
        assertNull(PronunciationRules.fromFurigana("北", "南[みなみ]"))
    }
}
