package app.honmani.reader.readaloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechChunksTest {
    @Test
    fun shortTextStaysWhole() {
        assertEquals(listOf("こんにちは。"), splitForSpeech("こんにちは。", 100))
    }

    @Test
    fun emptyTextHasNoPieces() {
        assertEquals(emptyList<String>(), splitForSpeech("", 10))
    }

    @Test
    fun piecesJoinToTheTextAndStayWithinTheLimit() {
        val text = "吾輩は猫である、名前はまだ無い。どこで生れたかとんと見当がつかぬ。何でも薄暗いじめじめした所でニャーニャー泣いていた事だけは記憶している。"
        for (max in 2..40) {
            val pieces = splitForSpeech(text, max)
            assertEquals(text, pieces.joinToString(""))
            assertTrue("max $max", pieces.all { it.isNotEmpty() && it.length <= max })
        }
    }

    @Test
    fun breaksAfterAComma() {
        val pieces = splitForSpeech("あいうえお、かきくけこさしすせそ", 10)
        assertEquals(listOf("あいうえお、", "かきくけこさしすせそ"), pieces)
    }

    @Test
    fun neverSplitsASurrogatePair() {
        val text = "𠮷".repeat(20)
        for (max in 2..9) {
            val pieces = splitForSpeech(text, max)
            assertEquals(text, pieces.joinToString(""))
            assertTrue("max $max", pieces.all { it.length % 2 == 0 })
        }
    }
}
