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
        val rates = mutableListOf<Double>()
        override val extension = "wav"

        override suspend fun speak(text: String, out: File, rate: Double) {
            problem?.let { throw VoiceException(it) }
            spoken += text
            rates += rate
            out.writeBytes(byteArrayOf(1, 2, 3))
            ShadowMediaPlayer.addMediaInfo(DataSource.toDataSource(out.path.removeSuffix(".part")), ShadowMediaPlayer.MediaInfo(500, 0))
        }
    }

    private class CountingFocus : PlaybackFocus {
        var requests = 0
        var held = false

        override fun request(): Boolean {
            requests++
            held = true
            return true
        }

        override fun release() {
            held = false
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
    fun aSentenceIsReadByThePhoneVoice_andOnlyTheLastOneIsKept() = runBlocking {
        val card = CountingVoice()
        val phone = CountingVoice()
        val audio = WordAudio(voice = { card }, dir = dir, main = Dispatchers.Unconfined, sentenceVoice = { phone })

        assertEquals(null, audio.playSentence("吾輩は猫である。"))
        assertEquals(null, audio.playSentence("吾輩は猫である。"))
        assertEquals(null, audio.playSentence("名前はまだ無い。"))

        assertEquals(emptyList<String>(), card.spoken)
        // The same sentence twice is a replay of the kept recording; a new one replaces it, and one file is left.
        assertEquals(listOf("吾輩は猫である。", "名前はまだ無い。"), phone.spoken)
        assertEquals(listOf("sentence.wav"), dir.listFiles()!!.map { it.name })
        audio.stop()
    }

    @Test
    fun aSentenceIsReadAtTheSentenceSpeed_aWordAtNormalSpeed_andAnotherSpeedMakesANewRecording() = runBlocking {
        val voice = CountingVoice()
        var rate = 0.85
        val audio = WordAudio(voice = { voice }, dir = dir, main = Dispatchers.Unconfined, sentenceRate = { rate })

        audio.play("たべる")
        audio.playSentence("吾輩は猫である。")
        audio.playSentence("吾輩は猫である。")
        rate = 0.7
        audio.playSentence("吾輩は猫である。")

        assertEquals(listOf(1.0, 0.85, 0.7), voice.rates)
        audio.stop()
    }

    @Test
    fun aWordAtAnotherSpeedIsAnotherRecording_andNormalSpeedKeepsItsName() {
        val voice = CountingVoice()
        assertNotEquals(WordAudio.name(voice, "たべる", 1.0), WordAudio.name(voice, "たべる", 0.7))
        assertEquals(WordAudio.name(voice, "たべる"), WordAudio.name(voice, "たべる", 1.0))
    }

    @Test
    fun otherAudioIsAskedToDuckWhileASoundPlaysAndGetsFocusBackWhenItIsStopped() = runBlocking {
        val focus = CountingFocus()
        val audio = WordAudio(voice = { CountingVoice() }, dir = dir, main = Dispatchers.Unconfined, focus = focus)

        assertEquals(null, audio.play("たべる"))
        assertEquals(true, focus.held)
        // A second sound replaces the first: still one focus held.
        assertEquals(null, audio.playSentence("吾輩は猫である。"))
        assertEquals(true, focus.held)
        audio.stop()

        assertEquals(false, focus.held)
        assertEquals(true, focus.requests >= 2)
    }

    @Test
    fun focusIsGivenBackWhenASoundCantPlay() = runBlocking {
        val focus = CountingFocus()
        val audio = WordAudio(voice = { CountingVoice() }, dir = dir, main = Dispatchers.Unconfined, focus = focus)
        val broken = File(dir, "missing.mp3")

        assertEquals(VoiceProblem.FAILED, audio.playFile(broken))

        assertEquals(false, focus.held)
    }

    @Test
    fun aFileFromElsewhereIsPlayedAndNeverDeleted() = runBlocking {
        val clip = File(dir, "clip.mp3").also {
            it.writeBytes(byteArrayOf(1, 2, 3))
            ShadowMediaPlayer.addMediaInfo(DataSource.toDataSource(it.path), ShadowMediaPlayer.MediaInfo(500, 0))
        }
        val focus = CountingFocus()
        val audio = WordAudio(voice = { CountingVoice() }, dir = dir, main = Dispatchers.Unconfined, focus = focus)

        assertEquals(null, audio.playFile(clip))
        assertEquals(true, audio.playing)
        audio.stop()

        assertEquals(true, clip.exists())
        assertEquals(false, audio.playing)
        assertEquals(false, focus.held)
    }

    @Test
    fun aSentenceVoiceProblemIsReported_notThrown() = runBlocking {
        val audio = WordAudio(
            voice = { CountingVoice() },
            dir = dir,
            main = Dispatchers.Unconfined,
            sentenceVoice = { CountingVoice(VoiceProblem.NO_ENGINE) },
        )

        assertEquals(VoiceProblem.NO_ENGINE, audio.playSentence("吾輩は猫である。"))
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

    @Test
    fun recordingsOfAnotherNamedVoiceOfTheSameKindHaveOtherNames() {
        fun voice(name: String) = object : CardVoice {
            override val extension = "mp3"
            override val id = "cloud:$name"
            override suspend fun speak(text: String, out: File, rate: Double) = Unit
        }
        assertNotEquals(WordAudio.name(voice("a"), "たべる"), WordAudio.name(voice("b"), "たべる"))
    }
}
