package app.honmani.reader.readaloud

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What reading a book's audiobook pairing came to. */
sealed interface AudiobookLoad {
    /** The subtitles are matched to the book; play [audio] from [resumeMs]. */
    data class Ready(val alignment: AudiobookAlignment, val audio: String, val resumeMs: Long) : AudiobookLoad

    data class Failed(val problem: AudiobookProblem) : AudiobookLoad
}

/**
 * Prepares a paired audiobook for [AudiobookController]: the subtitle copy from the [store], parsed,
 * and matched to the book's text. The matching is slow for a long book, so it runs off the main
 * thread and is kept in the store; the next start reads it back. Nothing here touches the audio:
 * that is opened later, so a file that is gone shows when playing starts.
 */
class AudiobookLoader(
    private val store: AudiobookStore,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    /**
     * [countedText] gives the book's counted characters ([app.honmani.reader.data.sync.EpubPositions.countedText]),
     * null if the book can't be read; it is only called when the matching isn't kept already.
     * [epub] false (the book is some other format) fails at once.
     */
    suspend fun load(bookHash: String, epub: Boolean, countedText: () -> IntArray?): AudiobookLoad = withContext(dispatcher) {
        if (!epub) return@withContext AudiobookLoad.Failed(AudiobookProblem.BOOK_UNSUPPORTED)
        val pairing = store.pairing(bookHash) ?: return@withContext AudiobookLoad.Failed(AudiobookProblem.NO_CUES)
        val resume = store.position(bookHash)
        store.alignment(bookHash, pairing.srtHash)?.takeIf { it.total > 0 }?.let {
            return@withContext AudiobookLoad.Ready(it, pairing.audio, resume)
        }
        val text = store.subtitles(bookHash) ?: return@withContext AudiobookLoad.Failed(AudiobookProblem.NO_CUES)
        val cues = Srt.parse(text)
        if (cues.isEmpty()) return@withContext AudiobookLoad.Failed(AudiobookProblem.NO_CUES)
        val book = countedText() ?: return@withContext AudiobookLoad.Failed(AudiobookProblem.BOOK_UNSUPPORTED)
        val folded = IntArray(book.size) { i -> CueAligner.fold(book[i]) }
        val alignment = AudiobookAlignment.of(folded, cues)
        store.saveAlignment(bookHash, pairing.srtHash, alignment)
        AudiobookLoad.Ready(alignment, pairing.audio, resume)
    }
}
