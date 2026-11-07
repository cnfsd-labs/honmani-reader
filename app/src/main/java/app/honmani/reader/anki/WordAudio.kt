package app.honmani.reader.anki

import app.honmani.reader.diagnostics.logWarning
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import java.io.File
import java.security.MessageDigest
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Says a word in the lookup panel with the voice chosen for cards (the phone's voice when card
 * audio is off): the reading, since a voice can misread kanji but never kana. Recordings are
 * kept in [dir], at most [MAX_FILES], so a word heard again plays at once.
 *
 * A whole sentence ([playSentence]) is always read by [sentenceVoice], the phone's own voice
 * (a paid cloud voice would send book text away and cost money), at [sentenceRate]; its recording
 * is replaced by the next one, but played again at once if the same sentence is asked for twice.
 * A recording from elsewhere (a clip of an audiobook) plays with [playFile].
 *
 * Every sound asks other audio to duck through [focus] and gives focus back when it ends.
 */
class WordAudio(
    private val voice: () -> CardVoice,
    private val dir: File,
    /** Where the player starts: the main thread. */
    private val main: CoroutineDispatcher = Dispatchers.Main,
    private val sentenceVoice: () -> CardVoice = voice,
    /** How fast sentences are read, times normal; words are always read at normal speed. */
    private val sentenceRate: () -> Double = { 1.0 },
    private val focus: PlaybackFocus = PlaybackFocus.None,
) {
    private val mutex = Mutex()
    private var player: MediaPlayer? = null

    /** What the file "sentence.*" holds: voice, rate and text; null if unknown. */
    private var sentenceMade: String? = null

    /** A recording is playing right now (UI sounds keep quiet meanwhile). */
    val playing: Boolean get() = player != null

    /** Plays [text]; returns why it couldn't, or null once it plays. */
    suspend fun play(text: String): VoiceProblem? = play(text, voice(), keep = true, rate = 1.0)

    /** Reads the sentence [text] aloud with the phone's voice; returns why it couldn't, or null once it plays. */
    suspend fun playSentence(text: String): VoiceProblem? {
        // The player may still hold the last sentence's file.
        withContext(main) { stop() }
        return play(text, sentenceVoice(), keep = false, rate = sentenceRate())
    }

    /**
     * Plays a recording that belongs to someone else (it is never deleted), replacing what plays now;
     * [label] says in the log what it is. Returns why it couldn't, or null once it plays.
     */
    suspend fun playFile(file: File, label: String = "clip"): VoiceProblem? =
        withContext(main) { start(file, "sentence", label, 1.0, owned = false) }

    /** Stops what is playing, if anything, and gives audio focus back. Call it on the main thread. */
    fun stop() {
        player?.release()
        player = null
        focus.release()
    }

    private suspend fun play(text: String, voice: CardVoice, keep: Boolean, rate: Double): VoiceProblem? {
        if (text.isBlank()) return null
        val file = try {
            mutex.withLock { record(voice, text, keep, rate) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: VoiceException) {
            return e.problem
        } catch (e: Exception) {
            logWarning("Honmani.anki", "Making the word audio failed", e)
            return VoiceProblem.FAILED
        }
        return withContext(main) { start(file, if (keep) "word" else "sentence", voice.id, rate, owned = true) }
    }

    private suspend fun record(voice: CardVoice, text: String, keep: Boolean, rate: Double): File = withContext(Dispatchers.IO) {
        dir.mkdirs()
        val file = File(dir, (if (keep) name(voice, text, rate) else SENTENCE_NAME) + "." + voice.extension)
        if (!keep) {
            // The same sentence again (a replay) needs no new recording.
            val made = "${voice.id}\u0000$rate\u0000$text"
            if (sentenceMade != made || !file.exists()) file.delete()
            sentenceMade = made
        }
        if (!file.exists()) {
            val partial = File(dir, file.name + ".part")
            try {
                voice.speak(text, partial, rate)
                if (!partial.renameTo(file)) throw VoiceException(VoiceProblem.FAILED)
            } catch (e: Exception) {
                if (!keep) sentenceMade = null
                partial.delete()
                throw e
            }
            if (keep) trim()
        }
        file.setLastModified(System.currentTimeMillis())
        file
    }

    private fun start(file: File, kind: String, source: String, rate: Double, owned: Boolean): VoiceProblem? {
        stop()
        Log.d(LOG_TAG, "play $kind source=$source rate=$rate")
        focus.request()
        return try {
            // Assigned first, so a failure below still releases it (stop).
            val created = MediaPlayer().also { player = it }
            created.apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                setDataSource(file.path)
                setOnCompletionListener { done ->
                    done.release()
                    if (player === done) {
                        player = null
                        focus.release()
                    }
                }
                setOnErrorListener { broken, what, extra ->
                    Log.w(LOG_TAG, "player error $what/$extra")
                    broken.release()
                    if (player === broken) {
                        player = null
                        focus.release()
                    }
                    true
                }
                prepare()
                start()
            }
            null
        } catch (e: Exception) {
            logWarning("Honmani.anki", "Playing the word audio failed", e)
            Log.w(LOG_TAG, "play $kind failed source=$source", e)
            stop()
            if (owned) file.delete()
            VoiceProblem.FAILED
        }
    }

    /** The oldest recordings go once there are more than [MAX_FILES]. */
    private fun trim() {
        val files = dir.listFiles()?.filter { !it.name.endsWith(".part") && !it.name.startsWith(SENTENCE_NAME) } ?: return
        files.sortedByDescending { it.lastModified() }.drop(MAX_FILES).forEach { it.delete() }
    }

    companion object {
        const val MAX_FILES = 200

        private const val LOG_TAG = "ReviewAudio"

        /** The recording of the last sentence read aloud; not counted in [MAX_FILES]. */
        private const val SENTENCE_NAME = "sentence"

        /** Different voices make different recordings of the same text. */
        internal fun name(voice: CardVoice, text: String, rate: Double = 1.0): String {
            // Normal speed keeps the names recordings had before there was a rate.
            val key = if (rate == 1.0) "${voice.id}:$text" else "${voice.id}@$rate:$text"
            val digest = MessageDigest.getInstance("SHA-1").digest(key.toByteArray())
            return digest.joinToString("") { "%02x".format(it) }.take(20)
        }
    }
}
