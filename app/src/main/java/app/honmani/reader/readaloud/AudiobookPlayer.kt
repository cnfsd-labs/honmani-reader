package app.honmani.reader.readaloud

/** Why the audiobook can't be played (the bar says it in words). */
enum class AudiobookProblem {
    /** The audio file is gone, or the app may not read it any more. */
    AUDIO_GONE,

    /** The phone can't play this audio. */
    UNSUPPORTED,

    /** The subtitle file has no subtitles, or is gone. */
    NO_CUES,

    /** Not an EPUB: only EPUBs can be followed. */
    BOOK_UNSUPPORTED,
}

/**
 * The playing half of the audiobook: one audio file, played in the app. [AudiobookController]
 * decides what and when; it knows nothing of Android. All calls and callbacks are on the main thread.
 */
interface AudiobookPlayer {
    interface Listener {
        /** The audio played to its end. */
        fun onCompleted()

        /** The player failed while playing. */
        fun onError()

        /** Another app took the audio: pause. */
        fun onFocusLost()
    }

    var listener: Listener?

    /** Opens the audio; [onResult] gets null when it is ready, else why not. */
    fun open(source: String, onResult: (AudiobookProblem?) -> Unit)

    /** Where the audio is, in milliseconds. */
    val positionMs: Long

    fun seekTo(ms: Long)

    /** Plays (asking for the audio focus); false when the focus was refused. */
    fun play(): Boolean

    fun pause()

    /** The speed: 1 is normal. Takes effect now if playing, else when playing starts. */
    fun setRate(rate: Float)

    /** Lets the file and the audio focus go. */
    fun release()
}
