package app.honmani.reader.readaloud

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AudiobookStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun preferences() = ApplicationProvider.getApplicationContext<Context>()
        .getSharedPreferences("audiobook-store-test", Context.MODE_PRIVATE)

    private fun store() = AudiobookStore(preferences(), File(folder.root, "audiobook"))

    @Test
    fun pairingPositionAndSubtitlesAreKeptPerBook() {
        val store = store()
        assertNull(store.pairing("abc"))
        val pairing = store.pair("abc", "content://audio/1", "1\n00:00:01,000 --> 00:00:02,000\nこんにちは\n".toByteArray())
        assertEquals(pairing, AudiobookStore(preferences(), File(folder.root, "audiobook")).pairing("abc"))
        assertNull(store.pairing("other"))
        assertTrue(store.subtitles("abc")!!.contains("こんにちは"))
        store.savePosition("abc", 12_345)
        assertEquals(12_345L, store.position("abc"))
        assertEquals(0L, store.position("other"))
    }

    @Test
    fun aNewPairingStartsOverAndDropsTheOldMatching() {
        val store = store()
        val first = store.pair("abc", "content://audio/1", "a".toByteArray())
        store.saveAlignment("abc", first.srtHash, AudiobookAlignment(listOf(AlignedCue(0, 1, CueSpan(0, 1)))))
        store.savePosition("abc", 99)
        assertNotNull(store.alignment("abc", first.srtHash))
        val second = store.pair("abc", "content://audio/2", "b".toByteArray())
        assertNull(store.alignment("abc", first.srtHash))
        assertEquals(0L, store.position("abc"))
        assertEquals("content://audio/2", store.pairing("abc")!!.audio)
        assertTrue(first.srtHash != second.srtHash)
    }

    @Test
    fun forgetRemovesEverythingAndALateSaveDoesNotBringItBack() {
        val store = store()
        val pairing = store.pair("abc", "content://audio/1", "a".toByteArray())
        store.saveAlignment("abc", pairing.srtHash, AudiobookAlignment(emptyList()))
        store.forget("abc")
        assertNull(store.pairing("abc"))
        assertNull(store.subtitles("abc"))
        assertNull(store.alignment("abc", pairing.srtHash))
        store.savePosition("abc", 5)
        assertEquals(0L, store.position("abc"))
        assertTrue(File(folder.root, "audiobook").listFiles().orEmpty().isEmpty())
    }

    @Test
    fun utf16SubtitlesAreRead() {
        val store = store()
        store.pair("abc", "content://a", byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "字幕".toByteArray(Charsets.UTF_16LE))
        assertEquals("字幕", store.subtitles("abc"))
    }
}
