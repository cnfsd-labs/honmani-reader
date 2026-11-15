package app.honmani.reader.readaloud

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Looper
import android.speech.tts.TextToSpeech
import androidx.test.core.app.ApplicationProvider
import java.time.Duration
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowTextToSpeech

@RunWith(RobolectricTestRunner::class)
class LiveVoiceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val events = ArrayList<String>()
    private val voice = LiveVoice(context, stallTimeoutMs = 1_000)

    private fun connected(): TextToSpeech {
        ShadowTextToSpeech.addLanguageAvailability(Locale.JAPAN)
        voice.listener = object : ReadAloudVoice.Listener {
            override fun onStart(utteranceId: String) { events += "start $utteranceId" }
            override fun onRange(utteranceId: String, start: Int) = Unit
            override fun onDone(utteranceId: String) { events += "done $utteranceId" }
            override fun onError(utteranceId: String) { events += "error $utteranceId" }
            override fun onFocusLost() { events += "lost" }
            override fun onFocusPaused() = Unit
            override fun onFocusResumed() = Unit
        }
        voice.connect { events += "connected $it" }
        val tts = ShadowTextToSpeech.getLastTextToSpeechInstance()
        shadowOf(tts).onInitListener.onInit(TextToSpeech.SUCCESS)
        shadowOf(Looper.getMainLooper()).idle()
        return tts
    }

    @After
    fun tearDown() {
        voice.shutdown()
        ShadowTextToSpeech.reset()
    }

    @Test
    fun anEngineThatStopsAnsweringFailsWhatIsQueued() {
        val tts = connected()
        tts.setOnUtteranceProgressListener(null) // the engine's process is gone: no callbacks come
        voice.speak("ra:1:0:0:1", "あい")
        voice.speak("ra:1:1:0:1", "う")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_500))
        assertEquals(listOf("connected null"), events)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_000))
        assertEquals(listOf("connected null", "error ra:1:0:0:1", "error ra:1:1:0:1"), events)
    }

    @Test
    fun anEngineThatAnswersIsLeftAlone() {
        connected()
        voice.speak("ra:1:0:0:1", "あい")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10))
        assertEquals(listOf("connected null", "start ra:1:0:0:1", "done ra:1:0:0:1"), events)
    }

    @Test
    fun headphonesUnpluggedWhileSpeakingCountAsTheFocusLost() {
        val tts = connected()
        tts.setOnUtteranceProgressListener(null)
        voice.speak("ra:1:0:0:1", "あい")
        context.sendBroadcast(Intent(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf("connected null", "lost"), events)
        // Nothing speaking: nothing listens.
        voice.stop()
        context.sendBroadcast(Intent(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf("connected null", "lost"), events)
    }
}
