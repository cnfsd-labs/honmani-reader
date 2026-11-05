package app.honmani.reader.readaloud

import app.honmani.reader.diagnostics.logWarning
import java.io.File
import java.security.MessageDigest
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** A library book as [SentenceClips] needs it. */
class ClipBook(
    val hash: String,
    val epub: Boolean,
    /** The book's counted characters ([app.honmani.reader.data.sync.EpubPositions.countedText]), null if unreadable. */
    val countedText: () -> IntArray?,
)

/**
 * The audiobook's own voice for a sentence of a book: [clip] cuts the part of the audio that holds
 * the sentence, found through the subtitles ([CueLocator]), and keeps it in [AudiobookStore.clipDirectory]
 * (named by book, subtitle copy, audio and range; never backed up; the oldest go when it passes
 * [LIMIT_BYTES]). The store drops a book's clips when its pairing goes. Null means no clip, for any
 * reason (no pairing, a book that isn't an EPUB, a sentence not found, audio that can't be cut):
 * the caller uses text-to-speech instead. The returned file belongs to the cache: copy it, don't delete it.
 */
class SentenceClips(
    private val store: AudiobookStore,
    private val loader: AudiobookLoader,
    private val cutter: ClipCutter,
    /** The book with this library id, or else the only book with this title; null if there is none. */
    private val book: suspend (id: Long?, title: String?) -> ClipBook?,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val mutex = Mutex()

    /** The last book's text and alignment: a run of cards from one book reads and matches it once. */
    private var memory: Memory? = null

    private class Memory(val hash: String, val srtHash: String, val alignment: AudiobookAlignment, val text: IntArray)

    suspend fun clip(bookId: Long?, bookTitle: String?, sentence: String): File? {
        if (sentence.isBlank()) return null
        return try {
            withContext(dispatcher) { mutex.withLock { find(bookId, bookTitle, sentence) } }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logWarning("Honmani.audio", "Finding a sentence clip failed", e)
            null
        }
    }

    private suspend fun find(bookId: Long?, bookTitle: String?, sentence: String): File? {
        val found = book(bookId, bookTitle) ?: return null
        val pairing = store.pairing(found.hash) ?: return null
        val known = memory?.takeIf { it.hash == found.hash && it.srtHash == pairing.srtHash }
        val memo = known ?: load(found, pairing) ?: return null
        val range = CueLocator.locate(memo.alignment, memo.text, sentence) ?: return null
        val directory = store.clipDirectory
        val name = "${store.fileName(found.hash)}-${pairing.srtHash}-${shortHash(pairing.audio)}-${range.startMs}-${range.endMs}"
        EXTENSIONS.map { File(directory, "$name.$it") }.firstOrNull { it.isFile && it.length() > 0 }?.let {
            it.setLastModified(System.currentTimeMillis())
            return it
        }
        directory.mkdirs()
        val cut = cutter.cut(pairing.audio, range, File(directory, name)) ?: return null
        trim(directory, cut)
        return cut
    }

    private suspend fun load(found: ClipBook, pairing: AudiobookPairing): Memory? {
        var text: IntArray? = null
        val load = loader.load(found.hash, found.epub) { found.countedText().also { text = it } }
        val ready = load as? AudiobookLoad.Ready ?: return null
        // The loader asked for the text only if it had to match the subtitles.
        val counted = text ?: found.countedText() ?: return null
        val folded = IntArray(counted.size) { CueAligner.fold(counted[it]) }
        return Memory(found.hash, pairing.srtHash, ready.alignment, folded).also { memory = it }
    }

    /** Removes the least recently used clips (never [keep]) until the folder is within [LIMIT_BYTES]. */
    private fun trim(directory: File, keep: File) {
        val files = directory.listFiles { file -> file.isFile }?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (file in files) {
            if (total <= LIMIT_BYTES) break
            if (file == keep) continue
            total -= file.length()
            file.delete()
        }
    }

    private fun shortHash(text: String): String =
        MessageDigest.getInstance("SHA-1").digest(text.toByteArray(Charsets.UTF_8)).take(4).joinToString("") { "%02x".format(it) }

    companion object {
        /** Clips are a few dozen KB each; this holds thousands. */
        const val LIMIT_BYTES = 50L * 1024 * 1024
        private val EXTENSIONS = listOf("mp3", "m4a")
    }
}
