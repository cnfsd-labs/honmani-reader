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
 * reports when another app takes the audio away; after a pause for a moment ([FocusPause]) it keeps
 * the focus until it comes back; headphones unplugged count as the focus lost ([BecomingNoisy]). An
 * engine that stops answering fails what is queued (see [stalled]). The engine's callbacks come on
 * its own threads; they are all moved to the main thread here.
 */
class LiveVoice(
    context: Context,
    private val connectTimeoutMs: Long = CONNECT_TIMEOUT_MS,
    private val stallTimeoutMs: Long = STALL_TIMEOUT_MS,
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
    private val focusPause = FocusPause()
    private val noisy = BecomingNoisy(context) { listener?.onFocusLost() }

    /** The utterances queued and not yet finished, in order; callbacks for any other id are stale. */
    private val live = LinkedHashSet<String>()

    /** The longest text queued since the queue was last empty: how long the engine may be silent. */
    private var longestQueued = 0

    /**
     * An engine whose process died (or hangs) reports nothing more: after too long without any
     * callback the queued utterances fail, so read aloud stops with a problem instead of hanging.
     */
    private val stalled = Runnable {
        if (live.isEmpty()) return@Runnable
        val ids = live.toList()
        live.clear()
        longestQueued = 0
        abandonFocus()
        ids.forEach { listener?.onError(it) }
    }

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
        longestQueued = maxOf(longestQueued, text.length)
        watch()
        if (tts.speak(text, TextToSpeech.QUEUE_ADD, Bundle(), utteranceId) != TextToSpeech.SUCCESS) {
            main.post { finished(utteranceId)?.let { listener?.onError(it) } }
        }
    }

    override fun stop() {
        engine?.stop()
        live.clear()
        watch()
        if (!focusPause.lostForAWhile) abandonFocus()
    }

    override fun shutdown() {
        epoch++
        engine?.stop()
        engine?.shutdown()
        engine = null
        live.clear()
        watch()
        abandonFocus()
    }

    /** Takes [id] off the queue; the id back if it was still queued, and gives the focus up when none is. */
    private fun finished(id: String): String? {
        if (!live.remove(id)) return null
        if (live.isEmpty()) abandonFocus()
        watch()
        return id
    }

    /** (Re)starts the wait for the engine's next callback while anything is queued. */
    private fun watch() {
        main.removeCallbacks(stalled)
        if (live.isEmpty()) {
            longestQueued = 0
            return
        }
        // Slow speech, and engines that report no ranges, keep quiet for a long sentence.
        main.postDelayed(stalled, stallTimeoutMs + (longestQueued * MS_PER_CHARACTER / rate).toLong())
    }

    /** Asks for the focus, again with the request still held after a pause for a moment. */
    private fun requestFocus(): Boolean {
        val request = focus ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attributes)
            // A request to duck pauses (FocusPause): Android must not lower the voice itself.
            .setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener(
                { change -> if (focusPause.onChange(change, listener) { live.isNotEmpty() }) abandonFocus() },
                main,
            )
            .build()
        focus = request
        val granted = audio.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (granted) {
            focusPause.reset()
            noisy.start()
        }
        return granted
    }

    private fun abandonFocus() {
        focus?.let { audio.abandonAudioFocusRequest(it) }
        focus = null
        focusPause.reset()
        noisy.stop()
    }

    private val progress = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            utteranceId ?: return
            main.post {
                if (utteranceId in live) {
                    watch()
                    listener?.onStart(utteranceId)
                }
            }
        }

        override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
            utteranceId ?: return
            main.post {
                if (utteranceId in live) {
                    watch()
                    listener?.onRange(utteranceId, start)
                }
            }
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
        const val STALL_TIMEOUT_MS = 20_000L
        const val MS_PER_CHARACTER = 400f
    }
}
