package app.honmani.reader.anki

import android.content.Context
import android.os.Bundle
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Why audio for a card could not be made. */
enum class VoiceProblem {
    NO_ENGINE,
    NO_JAPANESE,
    FAILED,
    NO_KEY,
    KEY_REJECTED,
    QUOTA,
    NETWORK,

    /** The Google Cloud project of the key has no billing account (a paid voice needs one). */
    BILLING_DISABLED,

    /** The Cloud Text-to-Speech API is not switched on in the key's project. */
    SERVICE_DISABLED,
}

/**
 * [status] is the HTTP status of the answer (0 if there was none), [retryAfterSeconds] what the
 * server's Retry-After header asked for.
 */
class VoiceException(
    val problem: VoiceProblem,
    cause: Throwable? = null,
    val status: Int = 0,
    val retryAfterSeconds: Long? = null,
) : IOException(problem.name, cause)

/** Turns Japanese text into an audio file for a card. */
interface CardVoice {
    /** The file extension of what [speak] writes, such as "mp3". */
    val extension: String

    /**
     * What makes this voice's recordings differ from another's (engine, voice name): part of the
     * cache key and the sound file name, so a changed voice doesn't reuse old audio.
     */
    val id: String get() = javaClass.name

    /** The longest text (in characters) the voice takes in one go; longer text isn't spoken. */
    val maxInputLength: Int get() = Int.MAX_VALUE

    /**
     * Writes [text] spoken to [out], at [rate] times the normal speed; throws [VoiceException]
     * on failure.
     */
    suspend fun speak(text: String, out: File, rate: Double = 1.0)
}

/**
 * The phone's own text-to-speech voice (usually Google's), offline and free. Sounds clearly
 * synthetic, but needs no account. The engine stays connected for a while after use, since
 * connecting takes a moment and a card needs two recordings.
 *
 * An engine that doesn't answer within [connectTimeoutMs] or [speakTimeoutMs] counts as failed
 * (and is let go), so adding cards never waits forever.
 */
class PhoneVoice(
    context: Context,
    private val scope: CoroutineScope,
    /** Where the engine is created and connects: the main thread. */
    private val main: CoroutineDispatcher = Dispatchers.Main,
    private val connectTimeoutMs: Long = CONNECT_TIMEOUT_MS,
    private val speakTimeoutMs: Long = SPEAK_TIMEOUT_MS,
) : CardVoice {
    private val context = context.applicationContext
    private val mutex = Mutex()
    private var engine: TextToSpeech? = null
    private var idle: Job? = null

    override val extension = "wav"

    // The default engine is part of the identity: a recording differs after switching it.
    override val id: String
        get() = "${javaClass.name}:" + (runCatching { Settings.Secure.getString(context.contentResolver, "tts_default_synth") }.getOrNull() ?: "")

    override val maxInputLength: Int get() = TextToSpeech.getMaxSpeechInputLength()

    override suspend fun speak(text: String, out: File, rate: Double) {
        mutex.withLock {
            idle?.cancel()
            try {
                val ready = engine ?: japaneseEngine().also { engine = it }
                // The rate stays with the engine, so it goes back to normal after each recording.
                ready.setSpeechRate(rate.toFloat())
                try {
                    synthesize(ready, text, out)
                } finally {
                    // Unless it was let go for not answering.
                    if (engine === ready) ready.setSpeechRate(1f)
                }
            } finally {
                idle = scope.launch {
                    delay(IDLE_MS)
                    mutex.withLock {
                        engine?.shutdown()
                        engine = null
                    }
                }
            }
        }
    }

    /** A connected engine set to Japanese. */
    private suspend fun japaneseEngine(): TextToSpeech {
        val connected = connect()
        val available = connected.setLanguage(Locale.JAPAN)
        if (available == TextToSpeech.LANG_MISSING_DATA || available == TextToSpeech.LANG_NOT_SUPPORTED) {
            connected.shutdown()
            throw VoiceException(VoiceProblem.NO_JAPANESE)
        }
        return connected
    }

    private suspend fun connect(): TextToSpeech = withContext(main) {
        withTimeoutOrNull(connectTimeoutMs) { connectNow() } ?: throw VoiceException(VoiceProblem.NO_ENGINE)
    }

    private suspend fun connectNow(): TextToSpeech =
        suspendCancellableCoroutine { continuation ->
            var engine: TextToSpeech? = null
            engine = TextToSpeech(context) { status ->
                val ready = engine
                if (!continuation.isActive) {
                    // Called again (the engine reconnected) or no longer wanted.
                    if (status != TextToSpeech.SUCCESS) ready?.shutdown()
                } else if (status == TextToSpeech.SUCCESS && ready != null) {
                    continuation.resume(ready)
                } else {
                    ready?.shutdown()
                    if (continuation.isActive) {
                        continuation.resumeWith(Result.failure(VoiceException(VoiceProblem.NO_ENGINE)))
                    }
                }
            }
            continuation.invokeOnCancellation { engine.shutdown() }
        }

    private suspend fun synthesize(engine: TextToSpeech, text: String, out: File) {
        out.parentFile?.mkdirs()
        val utterance = UUID.randomUUID().toString()
        val done = withTimeoutOrNull(speakTimeoutMs) { recording(engine, text, out, utterance) }
        if (done == null) {
            // The engine stopped answering: let it go, the next card connects afresh.
            engine.shutdown()
            if (this.engine === engine) this.engine = null
            throw VoiceException(VoiceProblem.FAILED)
        }
        if (!done || !out.isFile || out.length() == 0L) throw VoiceException(VoiceProblem.FAILED)
    }

    /** Records [text] into [out]; true once the engine says it is done. */
    private suspend fun recording(engine: TextToSpeech, text: String, out: File, utterance: String): Boolean =
        suspendCancellableCoroutine { continuation ->
            // Callbacks come on the engine's threads; resuming twice would crash the app.
            val answered = AtomicBoolean(false)
            fun answer(success: Boolean) {
                if (answered.compareAndSet(false, true)) continuation.resume(success)
            }
            engine.setOnUtteranceProgressListener(
                object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = Unit

                    override fun onDone(utteranceId: String?) {
                        if (utteranceId == utterance) answer(true)
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        if (utteranceId == utterance) answer(false)
                    }

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        if (utteranceId == utterance) answer(false)
                    }
                },
            )
            if (engine.synthesizeToFile(text, Bundle(), out, utterance) != TextToSpeech.SUCCESS) answer(false)
            continuation.invokeOnCancellation { engine.stop() }
        }

    private companion object {
        const val IDLE_MS = 60_000L
        const val CONNECT_TIMEOUT_MS = 10_000L

        /** Generous: a long sentence at a slow rate on an old phone. */
        const val SPEAK_TIMEOUT_MS = 60_000L
    }
}
