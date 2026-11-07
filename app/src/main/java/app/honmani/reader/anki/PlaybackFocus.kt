package app.honmani.reader.anki

import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.util.Log

/**
 * Audio focus around a short spoken sound ([WordAudio]): [request] before it plays, [release] when it
 * ends or is stopped, so other audio that was playing comes back at full volume.
 */
interface PlaybackFocus {
    /** Asks for focus; false if it was refused (the sound plays anyway: it is short). */
    fun request(): Boolean

    fun release()

    companion object {
        /** No focus handling (tests, and callers that don't care). */
        val None = object : PlaybackFocus {
            override fun request() = true
            override fun release() = Unit
        }
    }
}

/**
 * Asks other audio to duck (lower its volume) while a word or sentence is spoken, then gives focus
 * back. A refused request is only logged: a spoken word is too short to wait for.
 */
class DuckingFocus(private val audioManager: AudioManager) : PlaybackFocus {
    private var request: AudioFocusRequest? = null

    override fun request(): Boolean {
        release()
        val next = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setOnAudioFocusChangeListener { change -> Log.d(TAG, "focus changed $change") }
            .build()
        val result = try {
            audioManager.requestAudioFocus(next)
        } catch (e: RuntimeException) {
            Log.w(TAG, "focus request failed", e)
            AudioManager.AUDIOFOCUS_REQUEST_FAILED
        }
        val granted = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        Log.d(TAG, "focus request " + if (granted) "granted" else "refused ($result)")
        if (granted) request = next
        return granted
    }

    override fun release() {
        val held = request ?: return
        request = null
        try {
            audioManager.abandonAudioFocusRequest(held)
            Log.d(TAG, "focus released")
        } catch (e: RuntimeException) {
            Log.w(TAG, "focus release failed", e)
        }
    }

    private companion object {
        const val TAG = "ReviewAudio"
    }
}
