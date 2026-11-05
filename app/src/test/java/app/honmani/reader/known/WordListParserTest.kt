package app.honmani.reader.known

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class WordListParserTest {
    @Test
    fun readsOneWordPerLine_withBomCommentsAndEmptyLines() {
        val text = "\uFEFF# my words\n\n猫\r\n  犬  \n# another\n鳥\n"
        val parsed = WordListParser.parse(text)
        assertEquals(listOf("猫", "犬", "鳥"), parsed.entries.map { it.word })
        assertEquals(0, parsed.skippedLines)
    }

    @Test
    fun splitsColumnsByTabOrComma_andNormalisesTheReadingToHiragana() {
        val parsed = WordListParser.parse("猫\tネコ\n犬,いぬ,extra\n鳥\n\"魚\",さかな\n")
        assertEquals(
            listOf(WordListEntry("猫", "ねこ"), WordListEntry("犬", "いぬ"), WordListEntry("鳥", null), WordListEntry("魚", "さかな")),
            parsed.entries,
        )
    }

    @Test
    fun aTabWinsOverCommasInTheWord_andANonKanaSecondColumnIsNotAReading() {
        val parsed = WordListParser.parse("a,b\tc\n猫,cat\n")
        assertEquals(listOf(WordListEntry("a,b", null), WordListEntry("猫", null)), parsed.entries)
    }

    @Test
    fun repeatsAreDropped_andAHugeLineIsSkippedAndCounted() {
        val parsed = WordListParser.parse("猫\n猫\n" + "あ".repeat(100_000) + "\n" + "長".repeat(50) + "\n犬\n")
        assertEquals(listOf("猫", "犬"), parsed.entries.map { it.word })
        assertEquals(2, parsed.skippedLines)
    }

    @Test
    fun aFileOverTheCapIsRefused() {
        val big = ByteArray(WordListParser.MAX_BYTES + 1) { 'a'.code.toByte() }
        assertThrows(WordListTooLargeException::class.java) { WordListParser.read(ByteArrayInputStream(big)) }
        assertEquals(1, WordListParser.read(ByteArrayInputStream("猫".toByteArray())).entries.size)
    }
}
