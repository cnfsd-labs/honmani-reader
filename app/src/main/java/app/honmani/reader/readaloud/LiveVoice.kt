package app.honmani.reader.readaloud

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import app.honmani.reader.anki.VoiceProblem
import java.util.Locale

/**
 * The phone's own text-to-speech engine, speaking live (PhoneVoice writes files instead). Asks for
 * audio focus while something is queued and gives it back when the queue is empty or stopped, and
 * reports when another app takes the audio away. The engine's callbacks come on its own threads;
 * they are all moved to the main thread here.
 */
class LiveVoice(
    context: Context,
    private val connectTimeoutMs: Long = CONNECT_TIMEOUT_MS,
) : ReadAloudVoice {
    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val audio = this.context.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private var engine: TextToSpeech? = null

    /** Bumped by [shutdown]: an engine that connects after that is let go at once. */
    private var epoch = 0
    private var rate = 1f
    private var focus: AudioFocusRequest? = null

    /** The utterances queued and not yet finished; callbacks for any other id are stale. */
    private val live = HashSet<String>()

    override var listener: ReadAloudVoice.Listener? = null

    override val maxInputLength: Int get() = TextToSpeech.getMaxSpeechInputLength()

    override fun connect(onResult: (VoiceProblem?) -> Unit) {
        if (engine != null) {
            onResult(null)
            return
        }
        val mine = epoch
        var answered = false

        var created: TextToSpeech? = null
        fun answer(problem: VoiceProblem?, ready: TextToSpeech?) {
            if (answered) {
                // A late or repeated callback: nothing wants this engine.
                if (ready !== engine) ready?.shutdown()
                return
            }
            answered = true
            if (mine != epoch) {
                ready?.shutdown()
                return
            }
            if (problem == null && ready != null) engine = ready
            onResult(problem)
        }
        main.postDelayed(
            {
                if (!answered) {
                    val stuck = created
                    answer(VoiceProblem.NO_ENGINE, null)
                    stuck?.shutdown()
                }
            },
            connectTimeoutMs,
        )
        created = TextToSpeech(context) { status ->
            main.post {
                val tts = created
                when {
                    tts == null -> Unit
                    answered || mine != epoch -> tts.shutdown()
                    status != TextToSpeech.SUCCESS -> {
                        tts.shutdown()
                        answer(VoiceProblem.NO_ENGINE, null)
                    }
                    else -> {
                        val language = tts.setLanguage(Locale.JAPAN)
                        if (language == TextToSpeech.LANG_MISSING_DATA || language == TextToSpeech.LANG_NOT_SUPPORTED) {
                            tts.shutdown()
                            answer(VoiceProblem.NO_JAPANESE, null)
                        } else {
                            tts.setAudioAttributes(attributes)
                            tts.setSpeechRate(rate)
                            tts.setOnUtteranceProgressListener(progress)
                            answer(null, tts)
                        }
                    }
                }
            }
        }
    }

    override fun setRate(rate: Float) {
        this.rate = rate
        engine?.setSpeechRate(rate)
    }

    override fun speak(utteranceId: String, text: String) {
        val tts = engine ?: return
        if (live.isEmpty() && !requestFocus()) {
            main.post { listener?.onFocusLost() }
            return
        }
        live.add(utteranceId)
        if (tts.speak(text, TextToSpeech.QUEUE_ADD, Bundle(), utteranceId) != TextToSpeech.SUCCESS) {
            main.post { finished(utteranceId)?.let { listener?.onError(it) } }
        }
    }

    override fun stop() {
        engine?.stop()
        live.clear()
        abandonFocus()
    }

    override fun shutdown() {
        epoch++
        engine?.stop()
        engine?.shutdown()
        engine = null
        live.clear()
        abandonFocus()
    }

    /** Takes [id] off the queue; the id back if it was still queued, and gives the focus up when none is. */
    private fun finished(id: String): String? {
        if (!live.remove(id)) return null
        if (live.isEmpty()) abandonFocus()
        return id
    }

    private fun requestFocus(): Boolean {
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attributes)
            // A request to duck pauses (pausesForFocusChange): Android must not lower the voice itself.
            .setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener(
                { change ->
                    if (pausesForFocusChange(change)) {
                        listener?.onFocusLost()
                    }
                },
                main,
            )
            .build()
        focus = request
        return audio.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonFocus() {
        focus?.let { audio.abandonAudioFocusRequest(it) }
        focus = null
    }

    private val progress = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            utteranceId ?: return
            main.post { if (utteranceId in live) listener?.onStart(utteranceId) }
        }

        override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
            utteranceId ?: return
            main.post { if (utteranceId in live) listener?.onRange(utteranceId, start) }
        }

        override fun onDone(utteranceId: String?) {
            utteranceId ?: return
            main.post { finished(utteranceId)?.let { listener?.onDone(it) } }
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) {
            utteranceId ?: return
            main.post { finished(utteranceId)?.let { listener?.onError(it) } }
        }

        override fun onError(utteranceId: String?, errorCode: Int) = onError(utteranceId)
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 10_000L
    }
}
