package app.honmani.reader.readaloud

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import app.honmani.reader.diagnostics.logWarning
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import kotlin.coroutines.cancellation.CancellationException

/** Cuts a stretch out of an audiobook into a small file. */
fun interface ClipCutter {
    /**
     * Cuts [range] of [audio] (a content URI) into a file named [target] plus the extension that
     * fits the audio. Returns the file, or null if the audio can't be cut (an unknown format, the
     * file is gone, nothing in the range); the caller then goes without a clip. Call off the main thread.
     */
    fun cut(audio: String, range: ClipRange, target: File): File?
}

/**
 * [ClipCutter] with Android's own [MediaExtractor]; nothing is decoded or re-encoded.
 *
 * - MP3: the extractor hands out whole frames, which stand on their own, so they are written to a
 *   `.mp3` file as they are. (Android's [MediaMuxer] has no MP3 output: `addTrack` fails with
 *   "Unsupported mime 'audio/mpeg'".)
 * - AAC (M4B, M4A, MP4): the samples go through [MediaMuxer] into an `.m4a` file.
 * - Anything else (Opus, FLAC, WAV, ...): null.
 *
 * The range is widened by [PAD_MS] on each side, because subtitles cut tightly.
 */
class MediaClipCutter(private val context: Context) : ClipCutter {
    /** Audio files whose format can't be cut (until the app restarts): not opened again for every card. */
    private val unsupported = java.util.Collections.synchronizedSet(HashSet<String>())

    override fun cut(audio: String, range: ClipRange, target: File): File? {
        if (audio in unsupported) return null
        val extractor = MediaExtractor()
        var part: File? = null
        try {
            extractor.setDataSource(context, Uri.parse(audio), null)
            val track = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            val format = extractor.getTrackFormat(track)
            val extension = when (format.getString(MediaFormat.KEY_MIME)) {
                MediaFormat.MIMETYPE_AUDIO_MPEG -> "mp3"
                MediaFormat.MIMETYPE_AUDIO_AAC -> "m4a"
                else -> {
                    unsupported += audio
                    logWarning("Honmani.audio", "The audiobook's format can't be cut into clips: " + format.getString(MediaFormat.KEY_MIME))
                    return null
                }
            }
            val result = File(target.parentFile, target.name + "." + extension)
            part = File(target.parentFile, target.name + ".part")
            part.parentFile?.mkdirs()
            extractor.selectTrack(track)
            extractor.seekTo(maxOf(0L, range.startMs - PAD_MS) * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val endUs = (range.endMs + PAD_MS) * 1000
            val size = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 0
            val buffer = ByteBuffer.allocate(maxOf(size, MIN_BUFFER))
            val samples = if (extension == "mp3") copyFrames(extractor, buffer, endUs, part) else mux(extractor, format, buffer, endUs, part)
            if (samples == 0) return null
            result.delete()
            return if (part.renameTo(result)) result else null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A vanished file, a permission that is gone, a format the device can't read: no clip.
            logWarning("Honmani.audio", "Cutting a clip failed", e)
            return null
        } finally {
            extractor.release()
            part?.delete()
        }
    }

    /** MP3 frames written one after the other; returns how many. */
    private fun copyFrames(extractor: MediaExtractor, buffer: ByteBuffer, endUs: Long, out: File): Int {
        var count = 0
        FileOutputStream(out).use { stream ->
            while (true) {
                val time = extractor.sampleTime
                if (time < 0 || time > endUs) break
                buffer.clear()
                val read = extractor.readSampleData(buffer, 0)
                if (read < 0) break
                stream.write(buffer.array(), 0, read)
                count++
                if (!extractor.advance()) break
            }
        }
        return count
    }

    /** AAC samples into an MPEG-4 audio file; returns how many. */
    private fun mux(extractor: MediaExtractor, format: MediaFormat, buffer: ByteBuffer, endUs: Long, out: File): Int {
        val muxer = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var started = false
        var count = 0
        try {
            val track = muxer.addTrack(format)
            muxer.start()
            started = true
            val info = MediaCodec.BufferInfo()
            var base = -1L
            while (true) {
                val time = extractor.sampleTime
                if (time < 0 || time > endUs) break
                buffer.clear()
                val read = extractor.readSampleData(buffer, 0)
                if (read < 0) break
                if (base < 0) base = time
                info.set(0, read, time - base, MediaCodec.BUFFER_FLAG_KEY_FRAME)
                muxer.writeSampleData(track, buffer, info)
                count++
                if (!extractor.advance()) break
            }
        } finally {
            try {
                if (started) muxer.stop()
            } finally {
                muxer.release()
            }
        }
        return count
    }

    companion object {
        /** Room around the cues, so a tightly cut subtitle doesn't clip the first or last sound. */
        const val PAD_MS = 150L
        private const val MIN_BUFFER = 1 shl 16
    }
}
