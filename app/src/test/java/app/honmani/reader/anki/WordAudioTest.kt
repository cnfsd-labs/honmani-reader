package app.honmani.reader.anki

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowMediaPlayer
import org.robolectric.shadows.util.DataSource

@RunWith(RobolectricTestRunner::class)
class WordAudioTest {
    private val dir = Files.createTempDirectory("listen").toFile()

    private class CountingVoice(private val problem: VoiceProblem? = null) : CardVoice {
        val spoken = mutableListOf<String>()
        override val extension = "wav"

        override suspend fun speak(text: String, out: File, rate: Double) {
            problem?.let { throw VoiceException(it) }
            spoken += text
            out.writeBytes(byteArrayOf(1, 2, 3))
            ShadowMediaPlayer.addMediaInfo(DataSource.toDataSource(out.path.removeSuffix(".part")), ShadowMediaPlayer.MediaInfo(500, 0))
        }
    }

    @Test
    fun aWordHeardAgainIsNotRecordedAgain() = runBlocking {
        val voice = CountingVoice()
        val audio = WordAudio(voice = { voice }, dir = dir, main = Dispatchers.Unconfined)

        assertEquals(null, audio.play("たべる"))
        assertEquals(null, audio.play("たべる"))
        assertEquals(null, audio.play("のむ"))

        assertEquals(listOf("たべる", "のむ"), voice.spoken)
        assertEquals(2, dir.listFiles()!!.size)
    }

    @Test
    fun aVoiceProblemIsReported() = runBlocking {
        val audio = WordAudio(voice = { CountingVoice(VoiceProblem.NO_JAPANESE) }, dir = dir, main = Dispatchers.Unconfined)

        assertEquals(VoiceProblem.NO_JAPANESE, audio.play("たべる"))
    }

    @Test
    fun recordingsOfAnotherVoiceHaveOtherNames() {
        val other = object : CardVoice {
            override val extension = "mp3"
            override suspend fun speak(text: String, out: File, rate: Double) = Unit
        }
        assertNotEquals(WordAudio.name(CountingVoice(), "たべる"), WordAudio.name(other, "たべる"))
    }
}
