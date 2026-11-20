package app.honmani.reader.readaloud

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the media notification and the lock screen show: the book, and whether its voice plays or is paused. */
data class NowPlaying(val title: String, val playing: Boolean)

/**
 * Read aloud and the audiobook with the screen off. With "Keep playing with the screen off" (on by
 * default) the reader's voice goes on when the app leaves the screen, and [PlaybackService] (a
 * media playback foreground service) keeps the app running, with a notification and lock-screen
 * controls, for as long as there is something playing or paused. Off, the voice pauses when the app
 * leaves the screen, as it always did. Leaving the book for the library, or closing the app, ends
 * the voice either way.
 */
object BackgroundPlayback {
    /** The app left the screen: whether the reader's voice pauses. */
    fun pauseWhenStopped(keepPlaying: Boolean): Boolean = !keepPlaying

    /** What the notification shows, or null when there is none (nothing to play, or the setting is off). */
    fun nowPlaying(keepPlaying: Boolean, title: String, readAloud: ReadAloudPhase, audiobook: AudiobookPhase): NowPlaying? {
        if (!keepPlaying) return null
        val playing = readAloud == ReadAloudPhase.STARTING || readAloud == ReadAloudPhase.SPEAKING ||
            audiobook == AudiobookPhase.PLAYING
        val paused = readAloud == ReadAloudPhase.PAUSED || audiobook == AudiobookPhase.PAUSED
        return if (playing || paused) NowPlaying(title, playing) else null
    }
}

/**
 * Between the reader screen, which owns the voices, and [PlaybackService], which shows them in the
 * notification: the reader publishes [now] and sets [controls]; the service follows [now] and
 * passes the notification's and the lock screen's buttons to [controls]. Main thread only.
 */
class PlaybackHub {
    interface Controls {
        fun play()
        fun pause()

        /** Ends the voice, like the bar's close button: the notification goes away. */
        fun stop()
    }

    private val _now = MutableStateFlow<NowPlaying?>(null)
    val now: StateFlow<NowPlaying?> = _now.asStateFlow()

    var controls: Controls? = null

    /** Sets [now]; true when there was nothing before, so the service has to be started. */
    fun publish(value: NowPlaying?): Boolean {
        val started = _now.value == null && value != null
        _now.value = value
        return started
    }
}
