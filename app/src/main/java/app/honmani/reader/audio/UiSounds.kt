package app.honmani.reader.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.util.Log
import app.honmani.reader.R
import app.honmani.reader.anki.WordAudio
import kotlinx.coroutines.flow.StateFlow

/** The short interface sounds (Settings → Appearance → Sounds). */
enum class UiSound(val resource: Int) {
    GOT_IT(R.raw.ui_got_it),
    FORGOT(R.raw.ui_forgot),
    SESSION_DONE(R.raw.ui_session_done),
    MILESTONE(R.raw.ui_milestone),
}

/** The pure rule of when a sound may play. */
object UiSoundRule {
    /** Off by the switch, or whenever speech or other media is playing: a click never talks over it. */
    fun maySound(enabled: Boolean, readerSpeaking: Boolean, wordAudioPlaying: Boolean, mediaActive: Boolean): Boolean =
        enabled && !readerSpeaking && !wordAudioPlaying && !mediaActive
}

/**
 * Plays [UiSound]s with a [SoundPool] as short effects: no audio focus is taken, so nothing else is
 * ducked or paused. The clips load when the switch is first on and are released when it goes off.
 */
class UiSounds(
    context: Context,
    private val enabled: StateFlow<Boolean>,
    private val wordAudio: WordAudio,
) {
    private val appContext = context.applicationContext
    private val audio = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var pool: SoundPool? = null
    private val ids = HashMap<UiSound, Int>()
    private val loaded = HashSet<Int>()

    /** Asked for before its clip had loaded (the first sound after a start): plays once it has. */
    private var waiting: Int? = null

    /** Read aloud or the audiobook is running in the reader; set by the reader screen. */
    @Volatile
    var readerSpeaking: Boolean = false

    /** Main thread only. */
    fun play(sound: UiSound) {
        val on = enabled.value
        if (!on) {
            release()
            return
        }
        val allowed = UiSoundRule.maySound(on, readerSpeaking, wordAudio.playing, audio.isMusicActive)
        Log.d(TAG, "${sound.name.lowercase()} ${if (allowed) "play" else "skipped (speech or media active)"}, media volume ${audio.getStreamVolume(AudioManager.STREAM_MUSIC)}")
        if (!allowed) return
        val active = pool ?: create()
        val id = ids[sound] ?: return
        if (id in loaded) active.play(id, 1f, 1f, 1, 0, 1f) else waiting = id
    }

    private fun create(): SoundPool {
        val created = SoundPool.Builder()
            .setMaxStreams(2)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    // Media, not sonification: that stream is muted by the ringer's silent and vibrate
                    // modes (most phones sit there), so the sounds were never heard. Media volume rules.
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .build()
        created.setOnLoadCompleteListener { loadedPool, sampleId, status ->
            if (status == 0) loaded.add(sampleId)
            if (sampleId == waiting) {
                waiting = null
                if (status == 0) loadedPool.play(sampleId, 1f, 1f, 1, 0, 1f)
            }
        }
        for (sound in UiSound.entries) ids[sound] = created.load(appContext, sound.resource, 1)
        pool = created
        return created
    }

    private fun release() {
        pool?.release()
        pool = null
        ids.clear()
        loaded.clear()
        waiting = null
    }

    private companion object {
        const val TAG = "Honmani.sound"
    }
}
