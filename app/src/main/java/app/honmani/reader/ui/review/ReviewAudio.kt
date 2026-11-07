package app.honmani.reader.ui.review

import android.util.Log
import app.honmani.reader.anki.VoiceProblem
import app.honmani.reader.anki.WordAudio
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

/** What the speaker buttons of a review card play. Each call returns why it couldn't, or null once the sound plays. */
interface ReviewAudio {
    /** Says a word (its reading); [phoneOnly]: with the phone's own voice, not the one chosen for cards. */
    suspend fun word(text: String, phoneOnly: Boolean = false): VoiceProblem?

    /** Plays a card's sentence: the audiobook's recording if the book has one, else the phone's voice. */
    suspend fun sentence(text: String, bookId: Long?, bookTitle: String?): VoiceProblem?

    /** Stops what plays; [reason] is for the log. Call it on the main thread. */
    fun stop(reason: String)

    /** Stops the sound only if this player started it and nothing replaced it since (the lookup panel shares the player). */
    fun stopOwn(reason: String)
}

/** [ReviewAudio] through the app's one [WordAudio] player. */
class WordAudioReview(
    private val audio: WordAudio,
    /** The recording of the sentence cut from the book's paired audiobook; null if there is none. */
    private val clip: suspend (bookId: Long?, bookTitle: String?, sentence: String) -> File? = { _, _, _ -> null },
) : ReviewAudio {
    override suspend fun word(text: String, phoneOnly: Boolean): VoiceProblem? =
        guarded("word") { audio.play(text, phoneOnly) }

    override suspend fun sentence(text: String, bookId: Long?, bookTitle: String?): VoiceProblem? = guarded("sentence") {
        val found = try {
            clip(bookId, bookTitle, text)?.takeIf { it.isFile }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "finding the sentence clip failed", e)
            null
        }
        // A clip that won't play falls back to the voice.
        if (found != null && audio.playFile(found) == null) return@guarded null
        audio.playSentence(text)
    }

    /** [WordAudio.plays] after the last sound this started; another player start makes it stale. */
    private var lastPlay = -1

    override fun stop(reason: String) {
        Log.d(TAG, "stopped reason=$reason")
        audio.stop()
    }

    override fun stopOwn(reason: String) {
        if (audio.playing && audio.plays == lastPlay) {
            Log.d(TAG, "stopped own reason=$reason")
            audio.stop()
        }
    }

    private suspend fun guarded(kind: String, play: suspend () -> VoiceProblem?): VoiceProblem? = try {
        play().also {
            if (it != null) Log.w(TAG, "play $kind failed: $it") else lastPlay = audio.plays
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "play $kind failed", e)
        VoiceProblem.FAILED
    }

    private companion object {
        const val TAG = "ReviewAudio"
    }
}
