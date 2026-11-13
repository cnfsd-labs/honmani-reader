package app.honmani.reader.readaloud

import android.media.AudioManager

/** What another app's use of the audio means for what Honmani speaks. Called on the main thread. */
interface FocusListener {
    /** Another app took the audio for good (a music player): pause and stay paused. */
    fun onFocusLost()

    /**
     * Another app needs the audio for a moment (a notification, a navigation prompt, a short
     * call): pause; the focus is kept, so [onFocusResumed] follows when it is done.
     */
    fun onFocusPaused()

    /** The moment after [onFocusPaused] is over: continue if that pause is still the only reason. */
    fun onFocusResumed()
}

/**
 * The audio focus between a pause for a moment and the focus coming back, shared by [LiveVoice]
 * and [MediaAudiobookPlayer]. A request to duck pauses too, like a transient loss: speech is not
 * understood under another sound and Android does not lower spoken content itself (the requests
 * say setWillPauseWhenDucked). While [lostForAWhile] the focus request must not be given up on a
 * pause, or Android never hands the audio back (AUDIOFOCUS_GAIN).
 */
internal class FocusPause {
    var lostForAWhile = false
        private set

    /**
     * Tells [listener] what focus [change] means; true when the focus is to be given up now
     * (lost for good, or back with nothing [playing] after the listener had its say).
     */
    fun onChange(change: Int, listener: FocusListener?, playing: () -> Boolean): Boolean {
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                lostForAWhile = false
                listener?.onFocusLost()
                return !playing()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                lostForAWhile = true
                listener?.onFocusPaused()
            }
            AudioManager.AUDIOFOCUS_GAIN -> if (lostForAWhile) {
                lostForAWhile = false
                listener?.onFocusResumed()
                return !playing()
            }
        }
        return false
    }

    /** The focus was given up, or granted again. */
    fun reset() {
        lostForAWhile = false
    }
}
