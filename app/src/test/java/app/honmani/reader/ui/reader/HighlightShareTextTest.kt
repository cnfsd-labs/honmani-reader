package app.honmani.reader.ui.reader

import app.honmani.reader.data.library.HIGHLIGHT_TEXT_MAX
import app.honmani.reader.data.library.HighlightEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class HighlightShareTextTest {
    private fun highlight(text: String, start: Long = 0) =
        HighlightEntity(contentHash = "h", start = start, end = start + text.length, color = 0, text = text, total = 1000, createdAt = 0)

    @Test
    fun oneHighlightIsTheQuoteThenTheTitle() {
        assertEquals("「吾輩は猫である。」\n— 吾輩は猫である", HighlightShareText.one(highlight("吾輩は猫である。"), "吾輩は猫である"))
    }

    @Test
    fun allHighlightsAreSeparatedByBlankLinesWithTheTitleOnceAtTheEnd() {
        val text = HighlightShareText.all(listOf(highlight("一つ目。"), highlight("二つ目。", 10)), "本")
        assertEquals("「一つ目。」\n\n「二つ目。」\n\n— 本", text)
    }

    @Test
    fun noTitleLeavesTheDashLineOut() {
        assertEquals("「文。」", HighlightShareText.one(highlight("文。"), " "))
        assertEquals("「文。」", HighlightShareText.one(highlight("文。"), null))
    }

    @Test
    fun aPassageKeptOnlyInPartEndsInAnEllipsis() {
        val long = "あ".repeat(HIGHLIGHT_TEXT_MAX)
        assertEquals("「$long…」\n— 本", HighlightShareText.one(highlight(long), "本"))
    }
}
