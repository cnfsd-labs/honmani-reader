package app.honmani.reader.readaloud

import app.honmani.reader.anki.VoiceProblem

/**
 * The speaking half of read aloud: a live speech engine that says short texts one after the
 * other. [ReadAloudController] decides what and when; it knows nothing of Android. All calls and
 * all [Listener] callbacks are on the main thread.
 */
interface ReadAloudVoice {
    interface Listener {
        /** The engine began utterance [utteranceId]. */
        fun onStart(utteranceId: String)

        /** The engine reached character [start] of the text of utterance [utteranceId]. */
        fun onRange(utteranceId: String, start: Int)

        fun onDone(utteranceId: String)

        fun onError(utteranceId: String)

        /** Another app took the audio: stop speaking. */
        fun onFocusLost()
    }

    var listener: Listener?

    /** The longest text the engine takes in one utterance. */
    val maxInputLength: Int

    /** Connects to the engine and Japanese; [onResult] gets null when ready, else why not. */
    fun connect(onResult: (VoiceProblem?) -> Unit)

    /** The speed for utterances queued from now on: 1 is normal. */
    fun setRate(rate: Float)

    /** Queues [text] after what is already queued (the next call speaks it without a gap). */
    fun speak(utteranceId: String, text: String)

    /** Silences the engine and drops everything queued; no callbacks for those utterances follow. */
    fun stop()

    /** Lets the engine and the audio focus go; [connect] may be called again afterwards. */
    fun shutdown()
}
