package app.honmani.reader.readaloud

import org.junit.Assert.assertEquals
import org.junit.Test

class SrtTest {
    @Test
    fun readsBlocksWithBomAndCrlf() {
        val text = "﻿1\r\n00:00:01,500 --> 00:00:03,250\r\n吾輩は猫である。\r\n\r\n2\r\n00:00:04,000 --> 00:00:06,000\r\n名前はまだ無い。\r\nどこで生れたか。\r\n"
        assertEquals(
            listOf(Cue(1500, 3250, "吾輩は猫である。"), Cue(4000, 6000, "名前はまだ無い。\nどこで生れたか。")),
            Srt.parse(text),
        )
    }

    @Test
    fun acceptsDotMillisecondsAndHoursOverNinetyNine() {
        val cues = Srt.parse("1\n100:00:01.5 --> 100:00:02.25\nA\n")
        assertEquals(listOf(Cue(360_001_500, 360_002_250, "A")), cues)
    }

    @Test
    fun stripsTagsAndStyleCodes() {
        val cues = Srt.parse("1\n00:00:01,000 --> 00:00:02,000\n{\\an8}<i>こんにちは</i> &amp; <font color=\"red\">さよなら</font>\n")
        assertEquals("こんにちは & さよなら", cues.single().text)
    }

    @Test
    fun skipsBrokenBlocksAndEmptyCues() {
        val text = "1\n00:00:01,000 --> 00:00:02,000\nA\n\n2\n00:00:0x,000 --> 00:00:04,000\nlost\n\n3\n00:00:05,000 --> 00:00:06,000\n\n\n4\nnot a time line\n\n5\n00:00:07,000 --> 00:00:08,000\nE"
        assertEquals(listOf("A", "E"), Srt.parse(text).map { it.text })
    }

    @Test
    fun sortsCuesThatAreOutOfOrder() {
        val text = "1\n00:00:09,000 --> 00:00:10,000\nlate\n\n2\n00:00:01,000 --> 00:00:02,000\nearly\n"
        assertEquals(listOf("early", "late"), Srt.parse(text).map { it.text })
    }

    @Test
    fun splitsCuesWithoutBlankLineBetweenThem() {
        val text = "1\n00:00:01,000 --> 00:00:02,000\nA\n2\n00:00:03,000 --> 00:00:04,000\nB\n"
        assertEquals(listOf("A", "B"), Srt.parse(text).map { it.text })
    }

    @Test
    fun readsWebVttToo() {
        val text = "WEBVTT\n\nNOTE a comment\n\nintro\n00:01.000 --> 00:02.500 align:start\nA\n\n01:00:00.000 --> 01:00:01.000\nB\n"
        assertEquals(listOf(Cue(1000, 2500, "A"), Cue(3_600_000, 3_601_000, "B")), Srt.parse(text))
    }

    @Test
    fun noCuesFromNonsense() {
        assertEquals(emptyList<Cue>(), Srt.parse("hello\nworld"))
        assertEquals(emptyList<Cue>(), Srt.parse(""))
    }
}
