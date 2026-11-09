package app.honmani.reader.readaloud

import android.content.Context
import android.net.Uri
import app.honmani.reader.R
import app.honmani.reader.data.library.DocumentTree
import app.honmani.reader.data.library.ExportNames
import app.honmani.reader.diagnostics.logWarning
import app.honmani.reader.readaloud.generate.AudiobookMaker
import app.honmani.reader.readaloud.generate.MakerStatus
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** How an audiobook export ended. */
sealed interface AudiobookExportResult {
    /** The audio and the subtitles are in the folder; [audioName] is the name the audio file asked for. */
    data class Saved(val audioName: String) : AudiobookExportResult

    /** The audio is in the folder, but the book's subtitle copy is gone from this phone, so no subtitle file was written. */
    data class SavedWithoutSubtitles(val audioName: String) : AudiobookExportResult

    /** The book has no audiobook that is ready (none, or a generated one still in the making). */
    data object NothingToExport : AudiobookExportResult

    /** The audio file the reader paired is gone or can no longer be read. */
    data object AudioMissing : AudiobookExportResult

    /** The chosen folder cannot be written. */
    data object FolderUnusable : AudiobookExportResult

    data object OutOfSpace : AudiobookExportResult

    /** Anything else; what was written has been deleted. */
    data object Failed : AudiobookExportResult

    /** A readable sentence for a snackbar. */
    fun message(context: Context, title: String): String = when (this) {
        is Saved -> context.getString(R.string.audiobook_export_saved, title)
        is SavedWithoutSubtitles -> context.getString(R.string.audiobook_export_saved_no_subtitles, title)
        NothingToExport -> context.getString(R.string.audiobook_export_nothing)
        AudioMissing -> context.getString(R.string.audiobook_export_missing)
        FolderUnusable -> context.getString(R.string.audiobook_export_folder)
        OutOfSpace -> context.getString(R.string.audiobook_export_space)
        Failed -> context.getString(R.string.audiobook_export_failed)
    }
}

/** What a content provider says about the audio file the reader paired; either may be unknown. */
data class AudioInfo(val displayName: String?, val mimeType: String?)

/**
 * Writes a book's audiobook (the audio and its subtitles) into a folder the reader picked. The audio
 * is the reader's own file where it is (read through [openAudio]; Honmani keeps no copy) or the one
 * [maker] made; it is copied as a stream, as it can be hundreds of megabytes. A generated audiobook
 * that is not finished is not exported. Nothing is overwritten (the provider picks a new name for a
 * taken one), and a half-written file is deleted again. Blocks: [export] runs on the IO dispatcher.
 */
class AudiobookExporter(
    private val store: AudiobookStore,
    private val maker: AudiobookMaker,
    /** Opens the audio at a content or file address; null or an exception if it cannot be read. */
    private val openAudio: (String) -> InputStream?,
    private val audioInfo: (String) -> AudioInfo,
    /** Opens the folder the reader picked (a tree address); null if it is not one. */
    private val openTree: (Uri) -> DocumentTree?,
    private val openOutput: (Uri) -> OutputStream?,
) {
    /** Whether [bookHash] has an audiobook that can be exported. Reads files: call it off the main thread. */
    fun canExport(bookHash: String): Boolean {
        val pairing = store.pairing(bookHash) ?: return false
        return !isGenerated(bookHash, pairing) || maker.status(bookHash) is MakerStatus.Finished
    }

    private fun isGenerated(bookHash: String, pairing: AudiobookPairing) =
        pairing.audio == AudiobookMaker.uri(maker.files(bookHash).audio)

    /** Saves the audio and the subtitles of the book [bookHash] ([title] names the files) into [tree]. */
    suspend fun export(bookHash: String, title: String, tree: Uri): AudiobookExportResult = withContext(Dispatchers.IO) {
        if (!canExport(bookHash)) return@withContext AudiobookExportResult.NothingToExport
        val pairing = store.pairing(bookHash) ?: return@withContext AudiobookExportResult.NothingToExport
        val input = try {
            openAudio(pairing.audio)
        } catch (e: Exception) {
            // FileNotFound, or the permission is gone with the file.
            logWarning("Honmani.audiobook", "The audio to export cannot be opened", e)
            null
        } ?: return@withContext AudiobookExportResult.AudioMissing
        input.use {
            val documents = openTree(tree) ?: return@withContext AudiobookExportResult.FolderUnusable
            val info = if (isGenerated(bookHash, pairing)) AudioInfo("audio.mp3", "audio/mpeg") else audioInfo(pairing.audio)
            val extension = extension(info)
            val audioName = ExportNames.unique(title, extension, mutableSetOf())
            val subtitleName = ExportNames.unique(title, "srt", mutableSetOf())
            val subtitles = store.subtitles(bookHash)
            val created = mutableListOf<Uri>()
            try {
                val audio = documents.createFile(documents.rootId, mimeOf(extension, info.mimeType), audioName)
                    ?: return@withContext AudiobookExportResult.FolderUnusable
                created += audio
                val out = openOutput(audio) ?: throw IOException("Cannot write $audio")
                out.use { copy(input, it) }
                if (subtitles != null) {
                    val srt = documents.createFile(documents.rootId, "application/x-subrip", subtitleName)
                        ?: throw IOException("Cannot create the subtitle file")
                    created += srt
                    val out2 = openOutput(srt) ?: throw IOException("Cannot write $srt")
                    out2.use { it.write(subtitles.toByteArray(Charsets.UTF_8)) }
                }
                if (subtitles != null) AudiobookExportResult.Saved(audioName) else AudiobookExportResult.SavedWithoutSubtitles(audioName)
            } catch (e: Throwable) {
                withContext(NonCancellable) { created.forEach(documents::delete) }
                if (e is CancellationException) throw e
                logWarning("Honmani.audiobook", "Exporting an audiobook failed", e)
                if (isOutOfSpace(e)) AudiobookExportResult.OutOfSpace else AudiobookExportResult.Failed
            }
        }
    }

    private suspend fun copy(input: InputStream, output: OutputStream) {
        val context = currentCoroutineContext()
        val buffer = ByteArray(BUFFER)
        while (true) {
            context.ensureActive()
            val read = input.read(buffer)
            if (read < 0) break
            output.write(buffer, 0, read)
        }
        output.flush()
    }

    private fun isOutOfSpace(e: Throwable): Boolean = generateSequence(e) { it.cause }.take(5).any {
        it is IOException && (it.message.orEmpty().contains("ENOSPC") || it.message.orEmpty().contains("No space left", ignoreCase = true))
    }

    private fun extension(info: AudioInfo): String {
        val fromName = info.displayName?.substringAfterLast('.', "")?.lowercase().orEmpty()
        if (fromName.length in 1..5 && fromName.all { it.isLetterOrDigit() }) return fromName
        return MIME_EXTENSIONS[info.mimeType?.lowercase()] ?: "audio"
    }

    private fun mimeOf(extension: String, reported: String?): String =
        EXTENSION_MIMES[extension] ?: reported?.takeIf { it.startsWith("audio/") } ?: "application/octet-stream"

    private companion object {
        const val BUFFER = 64 * 1024

        val MIME_EXTENSIONS = mapOf(
            "audio/mpeg" to "mp3", "audio/mp3" to "mp3", "audio/mp4" to "m4a", "audio/x-m4a" to "m4a",
            "audio/aac" to "aac", "audio/ogg" to "ogg", "application/ogg" to "ogg", "audio/opus" to "opus",
            "audio/flac" to "flac", "audio/x-flac" to "flac", "audio/wav" to "wav", "audio/x-wav" to "wav",
        )
        val EXTENSION_MIMES = mapOf(
            "mp3" to "audio/mpeg", "m4a" to "audio/mp4", "m4b" to "audio/mp4", "aac" to "audio/aac",
            "ogg" to "audio/ogg", "oga" to "audio/ogg", "opus" to "audio/opus", "flac" to "audio/flac", "wav" to "audio/wav",
        )
    }
}
