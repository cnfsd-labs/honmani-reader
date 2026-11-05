package app.honmani.reader.readaloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CueLocatorTest {
    private fun fold(text: String) = CueAligner.reduce(text)

    private val sentence1 = "今日は晴れです。"
    private val sentence2 = "手袋を買いに行きました。"
    private val book = fold(sentence1 + sentence2 + "店の人は親切でした。" + sentence1)

    /** A cue for the book characters [start] until [end] (counted, without punctuation). */
    private fun cue(startMs: Long, endMs: Long, start: Int, end: Int) = AlignedCue(startMs, endMs, CueSpan(start, end))

    private val a = fold(sentence1).size
    private val b = a + fold(sentence2).size

    @Test
    fun aCueThatIsTheSentenceGivesItsTimes() {
        val alignment = AudiobookAlignment(listOf(cue(1000, 3000, 0, a), cue(4000, 6000, a, b)))

        assertEquals(ClipRange(4000, 6000), CueLocator.locate(alignment, book, sentence2))
    }

    @Test
    fun severalCuesInOneSentenceAreJoined_andACueSpanningTwoSentencesIsTakenWhole() {
        val alignment = AudiobookAlignment(
            listOf(cue(1000, 2000, 0, 3), cue(2000, 3000, 3, a + 2), cue(3000, 5000, a + 2, b)),
        )

        assertEquals(ClipRange(1000, 3000), CueLocator.locate(alignment, book, sentence1))
        assertEquals(ClipRange(2000, 5000), CueLocator.locate(alignment, book, sentence2))
    }

    @Test
    fun aRepeatedSentenceTakesTheFirstOccurrenceThatCuesCover() {
        val last = book.size - a
        val both = AudiobookAlignment(listOf(cue(1000, 3000, 0, a), cue(70000, 72000, last, book.size)))
        assertEquals(ClipRange(1000, 3000), CueLocator.locate(both, book, sentence1))
        val laterOnly = AudiobookAlignment(listOf(cue(70000, 72000, last, book.size), cue(100, 200, 0, 1), cue(300, 400, 1, 2)))
        assertEquals(ClipRange(70000, 72000), CueLocator.locate(laterOnly, book, sentence1))
    }

    @Test
    fun aSentenceNotInTheBook_orTooShort_orWithoutMatchedCues_givesNothing() {
        val alignment = AudiobookAlignment(listOf(cue(1000, 3000, 0, a), AlignedCue(4000, 6000, null)))

        assertNull(CueLocator.locate(alignment, book, "全然別の文章です。"))
        assertNull(CueLocator.locate(alignment, book, "晴れ"))
        assertNull(CueLocator.locate(alignment, book, sentence2))
    }

    @Test
    fun aRangeLongerThanTheCapGivesNothing() {
        val tooLong = AudiobookAlignment(listOf(cue(0, CueLocator.MAX_CLIP_MS + 1, 0, a)))
        val justRight = AudiobookAlignment(listOf(cue(0, CueLocator.MAX_CLIP_MS, 0, a)))

        assertNull(CueLocator.locate(tooLong, book, sentence1))
        assertEquals(ClipRange(0, CueLocator.MAX_CLIP_MS), CueLocator.locate(justRight, book, sentence1))
    }

    @Test
    fun doubtfulSubtitlesGiveNothing() {
        val doubtful = AudiobookAlignment(
            listOf(cue(0, 2000, 0, a), AlignedCue(2000, 3000, null), AlignedCue(3000, 4000, null)),
        )

        assertNull(CueLocator.locate(doubtful, book, sentence1))
    }
}
