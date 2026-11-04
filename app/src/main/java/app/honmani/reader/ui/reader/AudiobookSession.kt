package app.honmani.reader.ui.reader

import android.content.Context
import android.content.Intent
import android.net.Uri
import app.honmani.reader.AppContainer
import app.honmani.reader.R
import app.honmani.reader.data.library.BookFormat
import app.honmani.reader.data.sync.EpubPositions
import app.honmani.reader.readaloud.AudiobookController
import app.honmani.reader.readaloud.AudiobookLoad
import app.honmani.reader.readaloud.AudiobookProblem
import app.honmani.reader.readaloud.CueSpan
import app.honmani.reader.readaloud.MediaAudiobookPlayer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The reader's side of the audiobook: owns the [controller] (with the phone's [MediaAudiobookPlayer]),
 * remembers which book it plays so a late save lands on that book, and turns "play this book's
 * audiobook" into reading its files, matching the subtitles off the main thread, and handing
 * the result to the controller. Main thread only.
 */
class AudiobookSession(
    context: Context,
    private val container: AppContainer,
    private val scope: CoroutineScope,
    toPage: (String) -> Unit,
    onPlaying: () -> Unit,
    log: (String) -> Unit,
) {
    private val context = context.applicationContext
    private val store = container.audiobookStore
    private var job: Job? = null

    /** The book playing (its content hash): positions are saved for it even after the reader has moved on. */
    private var hash: String? = null

    /** Opens the book where a passage is that turning pages can't reach; set by the screen. */
    var navigate: (CueSpan) -> Unit = {}

    val controller = AudiobookController(
        player = MediaAudiobookPlayer(this.context),
        toPage = toPage,
        initialRate = store.rate(),
        saveRate = store::saveRate,
        savePosition = { ms -> hash?.let { store.savePosition(it, ms) } },
        onPlaying = onPlaying,
        navigate = { navigate(it) },
        log = log,
    )

    /** Plays the audiobook of the book with ttu id [ttuId], from where it was left. */
    fun start(ttuId: Long) {
        job?.cancel()
        controller.loading()
        job = scope.launch {
            val book = container.bookDao.findByTtuId(ttuId)
            if (book == null) {
                controller.fail(AudiobookProblem.NO_CUES)
                return@launch
            }
            hash = book.contentHash
            val load = container.audiobookLoader.load(book.contentHash, book.format == BookFormat.EPUB) {
                EpubPositions.countedText(container.bookImporter.bookFile(book))
            }
            ensureActive()
            when (load) {
                is AudiobookLoad.Ready -> controller.ready(load.alignment, load.audio, load.resumeMs)
                is AudiobookLoad.Failed -> controller.fail(load.problem)
            }
        }
    }

    fun close() {
        job?.cancel()
        controller.close()
    }

    /** The audio and subtitles [audio], [subtitles] (documents the reader chose) now belong to the book; false if the subtitles can't be read. */
    suspend fun pair(bookHash: String, audio: Uri, subtitles: Uri): Boolean {
        val bytes = withContext(Dispatchers.IO) {
            try {
                context.contentResolver.openInputStream(subtitles)?.use(::readLimited)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        }
        if (bytes == null || bytes.isEmpty()) return false
        val old = store.pairing(bookHash)?.audio
        withContext(Dispatchers.IO) { store.pair(bookHash, audio.toString(), bytes) }
        if (old != null && old != audio.toString()) release(old)
        return true
    }

    /** Takes the book's pairing away and lets the audio permission go. */
    suspend fun remove(bookHash: String) {
        val old = store.pairing(bookHash)?.audio
        withContext(Dispatchers.IO) { store.forget(bookHash) }
        if (old != null) release(old)
    }

    /** The app may read [audio] after a restart (the picker's grant lasts until the app lets go of it). */
    fun keepAccess(audio: Uri) {
        try {
            context.contentResolver.takePersistableUriPermission(audio, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
            // The provider doesn't offer a lasting grant: it works until the app is closed.
        }
    }

    /** [audio] was chosen but not used: lets its permission go, unless the book still plays it. */
    fun discard(bookHash: String, audio: Uri) {
        if (store.pairing(bookHash)?.audio != audio.toString()) release(audio.toString())
    }

    private fun release(audio: String) {
        try {
            context.contentResolver.releasePersistableUriPermission(Uri.parse(audio), Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) {
            // It was never persisted, or is already gone.
        }
    }

    /** All of [input], or null when it is longer than [MAX_SUBTITLE_BYTES]. */
    private fun readLimited(input: java.io.InputStream): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            if (out.size() + n > MAX_SUBTITLE_BYTES) return null
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    private companion object {
        /** A subtitle file for a long book is a few hundred KB: more than this is not one. */
        const val MAX_SUBTITLE_BYTES = 8 * 1024 * 1024
    }
}

/** What went wrong, as the bar says it. */
fun AudiobookProblem.label(): Int = when (this) {
    AudiobookProblem.AUDIO_GONE -> R.string.audiobook_problem_audio_gone
    AudiobookProblem.UNSUPPORTED -> R.string.audiobook_problem_unsupported
    AudiobookProblem.NO_CUES -> R.string.audiobook_problem_no_cues
    AudiobookProblem.BOOK_UNSUPPORTED -> R.string.audiobook_problem_book
}
