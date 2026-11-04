package app.honmani.reader.readaloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CueAlignerTest {
    private val lines = listOf(
        "吾輩は猫である。",
        "名前はまだ無い。",
        "どこで生れたかとんと見当がつかぬ。",
        "何でも薄暗いじめじめした所でニャーニャー泣いていた事だけは記憶している。",
        "吾輩はここで始めて人間というものを見た。",
        "しかもあとで聞くとそれは書生という人間中で一番獰悪な種族であったそうだ。",
        "この書生というのは時々我々を捕えて煮て食うという話である。",
        "しかしその当時は何という考もなかったから別段恐しいとも思わなかった。",
        "ただ彼の掌に載せられてスーと持ち上げられた時何だかフワフワした感じがあったばかりである。",
        "掌の上で少し落ちついて書生の顔を見たのがいわゆる人間というものの見始であろう。",
    )

    /** The book as the page counts it: only counted characters, folded. */
    private fun book(texts: List<String>): IntArray = texts.joinToString("") { it }.let { CueAligner.reduce(it) }

    @Test
    fun matchesCuesInOrderWithPunctuationAndBlanksIgnored() {
        val book = book(lines)
        val spans = CueAligner.align(book, lines.map { "  $it ".replace("、", ", ") })
        assertEquals(lines.size, spans.size)
        var end = 0
        for ((i, span) in spans.withIndex()) {
            assertNotNull("cue $i", span)
            assertEquals("cue $i starts where the last ended", end, span!!.start)
            end = span.end
        }
        assertEquals(book.size, end)
    }

    @Test
    fun aChangedWordStillMatchesAndANonsenseCueStaysUnmarked() {
        val book = book(lines)
        val heard = lines.toMutableList()
        heard[3] = "何でも薄暗いじめじめした所でニャーニャー泣いていたことだけ記憶している"
        heard[5] = "全く関係のない文章がここに入っていますよ"
        val spans = CueAligner.align(book, heard)
        assertNotNull(spans[3])
        assertNull(spans[5])
        // The later cues are where they would be without the damage.
        val clean = CueAligner.align(book, lines)
        for (i in listOf(0, 1, 2, 4, 6, 7, 8, 9)) assertEquals("cue $i", clean[i], spans[i])
    }

    @Test
    fun foldsWidthCaseAndKatakana() {
        val book = book(listOf("ＡＢＣ１２３とカタカナ", "ﾊﾝｶｸです"))
        val spans = CueAligner.align(book, listOf("abc123 と かたかな", "はんかくです"))
        assertEquals(CueSpan(0, 11), spans[0])
        assertEquals(CueSpan(11, 17), spans[1])
    }

    @Test
    fun tooShortCuesAreSkippedAndDoNotMoveTheCursor() {
        val book = book(lines)
        val spans = CueAligner.align(book, listOf(lines[0], "はい", lines[1]))
        assertNull(spans[1])
        assertEquals(CueSpan(book.size.let { _ -> CueAligner.reduce(lines[0]).size }, CueAligner.reduce(lines[0]).size + CueAligner.reduce(lines[1]).size), spans[2])
    }

    @Test
    fun findsItsWayBackAfterAStretchNotInTheAudio() {
        val filler = (1..400).map { "ここは音声に入っていない長い一節のために作った文章その$it。" }
        val book = book(lines.take(3) + filler + lines.drop(3))
        val spans = CueAligner.align(book, lines)
        assertNotNull(spans[2])
        // Past the gap: the first few cues after it are lost, then the aligner finds the book again.
        assertTrue("later cues match", spans.drop(6).count { it != null } >= 3)
        val matched = spans.filterNotNull()
        assertEquals(matched.sortedBy { it.start }, matched)
    }

    @Test
    fun gaijiNeverMatches() {
        val book = intArrayOf(CueAligner.GAIJI) + CueAligner.reduce("ありがとうございます")
        val spans = CueAligner.align(book, listOf("ありがとうございます"))
        assertEquals(CueSpan(1, book.size), spans[0])
    }
}
