package app.honmani.reader.readaloud

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AudiobookLoaderTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val store by lazy {
        AudiobookStore(
            ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("audiobook-loader-test", Context.MODE_PRIVATE),
            File(folder.root, "audiobook"),
        )
    }
    private val loader by lazy { AudiobookLoader(store, Dispatchers.Unconfined) }

    private val book = "今日は晴れです。手袋を買いに行きました。店の人は親切でした。".filter { it != '。' }.map { it.code }.toIntArray()
    private val srt = "1\n00:00:01,000 --> 00:00:03,000\n今日は晴れです。\n\n2\n00:00:04,000 --> 00:00:06,000\n手袋を買いに行きました。\n\n3\n00:00:07,000 --> 00:00:08,000\nまったく別の文\n"

    private fun load(epub: Boolean = true, text: () -> IntArray? = { book }): AudiobookLoad = runBlocking { loader.load("abc", epub, text) }

    @Test
    fun aBookThatIsNotAnEpubIsUnsupportedAndNothingIsRead() {
        store.pair("abc", "content://a", srt.toByteArray())
        assertEquals(AudiobookLoad.Failed(AudiobookProblem.BOOK_UNSUPPORTED), load(epub = false) { error("not read") })
    }

    @Test
    fun noPairingOrNoSubtitlesIsNoCues() {
        assertEquals(AudiobookLoad.Failed(AudiobookProblem.NO_CUES), load())
        store.pair("abc", "content://a", "nothing to see\n".toByteArray())
        assertEquals(AudiobookLoad.Failed(AudiobookProblem.NO_CUES), load())
    }

    @Test
    fun anUnreadableBookIsUnsupported() {
        store.pair("abc", "content://a", srt.toByteArray())
        assertEquals(AudiobookLoad.Failed(AudiobookProblem.BOOK_UNSUPPORTED), load { null })
    }

    @Test
    fun subtitlesAreMatchedKeptAndNotMatchedAgain() {
        store.pair("abc", "content://a", srt.toByteArray())
        store.savePosition("abc", 4_000)
        val first = load() as AudiobookLoad.Ready
        assertEquals("content://a", first.audio)
        assertEquals(4_000L, first.resumeMs)
        assertEquals(3, first.alignment.total)
        assertNotNull(first.alignment.cues[0].span)
        assertNotNull(first.alignment.cues[1].span)
        assertEquals(null, first.alignment.cues[2].span)
        val second = load { error("the matching was kept") } as AudiobookLoad.Ready
        assertEquals(first.alignment.serialize(), second.alignment.serialize())
        assertTrue(second.alignment.matched == 2)
    }
}
