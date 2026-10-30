package app.honmani.reader.anki

import app.honmani.reader.diagnostics.logWarning
import android.media.AudioAttributes
import android.media.MediaPlayer
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
 */
class WordAudio(
    private val voice: () -> CardVoice,
    private val dir: File,
    /** Where the player starts: the main thread. */
    private val main: CoroutineDispatcher = Dispatchers.Main,
) {
    private val mutex = Mutex()
    private var player: MediaPlayer? = null

    /** Plays [text]; returns why it couldn't, or null once it plays. */
    suspend fun play(text: String): VoiceProblem? {
        if (text.isBlank()) return null
        val file = try {
            mutex.withLock { record(voice(), text) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: VoiceException) {
            return e.problem
        } catch (e: Exception) {
            logWarning("Honmani.anki", "Making the word audio failed", e)
            return VoiceProblem.FAILED
        }
        return withContext(main) { start(file) }
    }

    private suspend fun record(voice: CardVoice, text: String): File = withContext(Dispatchers.IO) {
        dir.mkdirs()
        val file = File(dir, name(voice, text) + "." + voice.extension)
        if (!file.exists()) {
            val partial = File(dir, file.name + ".part")
            voice.speak(text, partial)
            if (!partial.renameTo(file)) throw VoiceException(VoiceProblem.FAILED)
            trim()
        }
        file.setLastModified(System.currentTimeMillis())
        file
    }

    private fun start(file: File): VoiceProblem? {
        player?.release()
        player = null
        return try {
            player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                setDataSource(file.path)
                setOnCompletionListener { done ->
                    done.release()
                    if (player === done) player = null
                }
                prepare()
                start()
            }
            null
        } catch (e: Exception) {
            logWarning("Honmani.anki", "Playing the word audio failed", e)
            file.delete()
            VoiceProblem.FAILED
        }
    }

    /** The oldest recordings go once there are more than [MAX_FILES]. */
    private fun trim() {
        val files = dir.listFiles()?.filter { !it.name.endsWith(".part") } ?: return
        files.sortedByDescending { it.lastModified() }.drop(MAX_FILES).forEach { it.delete() }
    }

    companion object {
        const val MAX_FILES = 200

        /** Different voices make different recordings of the same text. */
        internal fun name(voice: CardVoice, text: String): String {
            val digest = MessageDigest.getInstance("SHA-1").digest("${voice.javaClass.name}:$text".toByteArray())
            return digest.joinToString("") { "%02x".format(it) }.take(20)
        }
    }
}
