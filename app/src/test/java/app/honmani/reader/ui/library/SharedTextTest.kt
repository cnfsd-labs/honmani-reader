package app.honmani.reader.ui.library

import android.content.Intent
import android.net.Uri
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SharedTextTest {
    private val fallback = "Shared text 2026-11-07"

    @Test
    fun theSendersTitleComesFirst() {
        assertEquals("記事", SharedText.title("  記事 ", "本文\nつづき", fallback))
    }

    @Test
    fun withoutOneTheFirstLineWithTextIsTheTitle() {
        assertEquals("吾輩は猫である", SharedText.title(null, "\n　\n　吾輩は猫である\n名前はまだ無い。", fallback))
        assertEquals("吾輩は猫である", SharedText.title("   ", "吾輩は猫である", fallback))
    }

    @Test
    fun nothingUsableGivesTheDatedDefault() {
        assertEquals(fallback, SharedText.title(null, "///\n", fallback))
        assertEquals(fallback, SharedText.title("", "  ", fallback))
    }

    @Test
    fun fileNameCharactersAndWhiteSpaceAreCleaned() {
        assertEquals("a b c d", SharedText.title("a/b\\c:\n d", "x", fallback))
        assertEquals("note", SharedText.title("..note..", "x", fallback))
    }

    @Test
    fun longTitlesAreCutWithoutSplittingACharacter() {
        val title = SharedText.title(null, "𠮷".repeat(100), fallback)
        assertEquals("𠮷".repeat(SharedText.MAX_TITLE_CHARS) + "…", title)
        assertEquals("あ".repeat(SharedText.MAX_TITLE_CHARS), SharedText.title(null, "あ".repeat(SharedText.MAX_TITLE_CHARS), fallback))
    }

    @Test
    fun readyTextIsAUtf8TextFileNamedAfterTheTitle() {
        val ready = SharedText.prepare("題名\n本文", null, fallback) as SharedText.Prepared.Ready
        assertEquals("題名", ready.title)
        assertEquals("題名.txt", ready.fileName)
        assertArrayEquals("題名\n本文".toByteArray(Charsets.UTF_8), ready.bytes)
    }

    @Test
    fun emptyAndHugeTextIsRefused() {
        assertEquals(SharedText.Prepared.Empty, SharedText.prepare(null, null, fallback))
        assertEquals(SharedText.Prepared.Empty, SharedText.prepare(" \n\u3000", "T", fallback))
        // 2 million kana are 6 MB in UTF-8, though only 2 million characters.
        assertEquals(SharedText.Prepared.TooLarge, SharedText.prepare("あ".repeat(2_000_000), null, fallback))
        assertTrue(SharedText.prepare("あ".repeat(SharedText.MAX_BYTES / 3), null, fallback) is SharedText.Prepared.Ready)
    }

    @Test
    fun onlyTextOnlySharesCount() {
        val text = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, "本文").putExtra(Intent.EXTRA_SUBJECT, "件名")
        val shared = SharedText.fromIntent(text)!!
        assertEquals("本文", shared.text)
        assertEquals("件名", shared.title)

        val titled = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, "本文").putExtra(Intent.EXTRA_TITLE, "題")
        assertEquals("題", SharedText.fromIntent(titled)!!.title)

        // A text file is a file, not a text.
        val file = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_STREAM, Uri.parse("content://files/a.txt"))
        assertNull(SharedText.fromIntent(file))
        assertNull(SharedText.fromIntent(Intent(Intent.ACTION_SEND).setType("application/epub+zip")))
        assertNull(SharedText.fromIntent(Intent(Intent.ACTION_VIEW).setType("text/plain")))
        assertNull(SharedText.fromIntent(null))
    }

    @Test
    fun anEmptyShareIsStillReported() {
        val empty = Intent(Intent.ACTION_SEND).setType("text/plain")
        assertEquals("", SharedText.fromIntent(empty)!!.text)
    }
}
