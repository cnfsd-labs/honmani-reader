package app.honmani.reader.data.words

import org.junit.Assert.assertEquals
import org.junit.Test

class SentencesTest {
    private fun split(text: String) = Sentences.of(text).map { text.substring(it.first, it.last + 1) }

    @Test
    fun cutsAfterTheEndingAndTheClosingQuotes_asTheTapBridgeDoes() {
        assertEquals(listOf("「はい。」", "彼は言った！？", "終わり"), split("「はい。」彼は言った！？  終わり"))
    }

    @Test
    fun aLineBreakEndsASentence_andWhiteSpaceIsTrimmed() {
        assertEquals(listOf("東京", "都庁へ行く。", "猫 犬"), split("東京\n都庁へ行く。\n猫 犬\n"))
    }

    @Test
    fun emptyTextHasNoSentences() {
        assertEquals(emptyList<String>(), split(""))
        assertEquals(emptyList<String>(), split(" \n"))
    }
}
