package app.honmani.reader.readaloud.generate

import java.io.OutputStream

/** Thrown when MP3 data can't be used or its pieces don't fit together. */
class Mp3Exception(message: String) : Exception(message)

/** MPEG audio version of an MP3 frame. */
enum class MpegVersion { V1, V2, V25 }

/** What all frames of one stream share. Layer III is the only layer handled. */
data class Mp3Format(val version: MpegVersion, val sampleRate: Int, val channels: Int) {
    /** Samples in one frame. */
    val samplesPerFrame: Int get() = if (version == MpegVersion.V1) 1152 else 576
}

/** One frame header: [length] is the whole frame in bytes, header and padding included. */
class Mp3FrameHeader(val format: Mp3Format, val bitrate: Int, val padding: Boolean, val crc: Boolean, val length: Int)

/** The audio frames of one MP3 file, without ID3 tags and the Xing/Info frame, and without a cut-off last frame. */
class Mp3Chunk internal constructor(
    val format: Mp3Format,
    val frames: Int,
    private val data: ByteArray,
    private val start: Int,
    private val end: Int,
    /** Bytes dropped at the end: a frame that was cut off. */
    val truncatedBytes: Int,
) {
    val sizeBytes: Int get() = end - start

    fun writeTo(out: OutputStream) = out.write(data, start, end - start)

    fun toByteArray(): ByteArray = data.copyOfRange(start, end)
}

/**
 * Reads and joins MP3 files (what a speech service sends back, one per sentence) without
 * decoding them. A frame of Layer III always holds the same number of samples, so the length
 * of a stream is exact from its frame count; there is no encoder delay to guess at once the
 * Xing/Info frame (which only describes the file) is dropped.
 *
 * Add up frames, not milliseconds: a frame is not a whole number of milliseconds at every rate
 * (1152 samples at 44.1 kHz), and rounding each piece would drift over thousands of sentences.
 */
object Mp3Frames {
    private val BITRATES_V1 = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320)
    private val BITRATES_V2 = intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160)
    private val RATES_V1 = intArrayOf(44_100, 48_000, 32_000)
    private val RATES_V2 = intArrayOf(22_050, 24_000, 16_000)
    private val RATES_V25 = intArrayOf(11_025, 12_000, 8_000)

    /** The header at [offset], or null if there is none (no sync, reserved or unsupported values, not Layer III). */
    fun parseHeader(bytes: ByteArray, offset: Int): Mp3FrameHeader? {
        if (offset < 0 || offset + 4 > bytes.size) return null
        val b0 = bytes[offset].toInt() and 0xFF
        val b1 = bytes[offset + 1].toInt() and 0xFF
        val b2 = bytes[offset + 2].toInt() and 0xFF
        val b3 = bytes[offset + 3].toInt() and 0xFF
        if (b0 != 0xFF || b1 and 0xE0 != 0xE0) return null
        val version = when ((b1 shr 3) and 3) {
            3 -> MpegVersion.V1
            2 -> MpegVersion.V2
            0 -> MpegVersion.V25
            else -> return null
        }
        if ((b1 shr 1) and 3 != 1) return null // only Layer III
        val bitrateIndex = b2 shr 4
        if (bitrateIndex == 0 || bitrateIndex == 15) return null
        val rateIndex = (b2 shr 2) and 3
        if (rateIndex == 3) return null
        val bitrate = (if (version == MpegVersion.V1) BITRATES_V1 else BITRATES_V2)[bitrateIndex] * 1000
        val sampleRate = when (version) {
            MpegVersion.V1 -> RATES_V1
            MpegVersion.V2 -> RATES_V2
            MpegVersion.V25 -> RATES_V25
        }[rateIndex]
        val padding = (b2 shr 1) and 1 == 1
        val channels = if ((b3 shr 6) == 3) 1 else 2
        val coefficient = if (version == MpegVersion.V1) 144 else 72
        val length = coefficient * bitrate / sampleRate + if (padding) 1 else 0
        return Mp3FrameHeader(Mp3Format(version, sampleRate, channels), bitrate, padding, b1 and 1 == 0, length)
    }

    /**
     * The audio of [bytes]: a leading ID3v2 tag, a trailing ID3v1 tag and the first frame if it is
     * a Xing, Info or VBRI frame are dropped; a last frame that is cut off is dropped and reported
     * in [Mp3Chunk.truncatedBytes]. Anything else that is not a frame, or frames that differ in
     * rate, channels or version, is an [Mp3Exception].
     */
    fun read(bytes: ByteArray): Mp3Chunk {
        var pos = id3v2Length(bytes)
        var limit = bytes.size
        if (limit - pos >= 128 && bytes[limit - 128] == 'T'.code.toByte() && bytes[limit - 127] == 'A'.code.toByte() && bytes[limit - 126] == 'G'.code.toByte()) limit -= 128
        val first = parseHeader(bytes, pos) ?: throw Mp3Exception("No MP3 audio found at byte $pos")
        val format = first.format
        if (pos + first.length <= limit && isInfoFrame(bytes, pos, first)) pos += first.length
        val start = pos
        var frames = 0
        while (pos + 4 <= limit) {
            // A run of zero bytes at the end is padding, not a frame.
            if (bytes[pos] == 0.toByte() && (pos until limit).all { bytes[it] == 0.toByte() }) {
                pos = limit
                break
            }
            val header = parseHeader(bytes, pos) ?: throw Mp3Exception("Broken MP3 frame at byte $pos")
            if (header.format != format) throw Mp3Exception("The MP3 changes from ${describe(format)} to ${describe(header.format)} at byte $pos")
            if (pos + header.length > limit) break
            pos += header.length
            frames++
        }
        return Mp3Chunk(format, frames, bytes, start, pos, limit - pos)
    }

    /** Whether the frame at [pos] only describes the file (a Xing or Info tag of a VBR or LAME encoder). */
    private fun isInfoFrame(bytes: ByteArray, pos: Int, header: Mp3FrameHeader): Boolean {
        val format = header.format
        val sideInfo = if (format.version == MpegVersion.V1) (if (format.channels == 1) 17 else 32) else (if (format.channels == 1) 9 else 17)
        val tag = pos + 4 + (if (header.crc) 2 else 0) + sideInfo
        return matches(bytes, tag, "Xing") || matches(bytes, tag, "Info") || matches(bytes, pos + 4 + 32, "VBRI")
    }

    private fun matches(bytes: ByteArray, at: Int, text: String): Boolean =
        at >= 0 && at + text.length <= bytes.size && text.indices.all { bytes[at + it] == text[it].code.toByte() }

    /** The size of the ID3v2 tag at the start of [bytes], 0 if there is none. */
    private fun id3v2Length(bytes: ByteArray): Int {
        if (bytes.size < 10 || !matches(bytes, 0, "ID3")) return 0
        var size = 0
        for (i in 6..9) {
            val b = bytes[i].toInt()
            if (b and 0x80 != 0) throw Mp3Exception("Broken ID3 tag")
            size = (size shl 7) or b
        }
        val footer = if (bytes[5].toInt() and 0x10 != 0) 10 else 0
        val total = 10 + size + footer
        if (total > bytes.size) throw Mp3Exception("The ID3 tag is longer than the file")
        return total
    }

    /** Fails unless every chunk has the format of the first one. */
    fun requireSame(expected: Mp3Format, actual: Mp3Format) {
        if (expected != actual) throw Mp3Exception("The audio changed from ${describe(expected)} to ${describe(actual)}")
    }

    /** Writes the frames of all [chunks] one after the other to [out] and returns their frame count. All must share one format. */
    fun join(chunks: List<Mp3Chunk>, out: OutputStream): Long {
        var frames = 0L
        for (chunk in chunks) {
            requireSame(chunks.first().format, chunk.format)
            chunk.writeTo(out)
            frames += chunk.frames
        }
        return frames
    }

    /** The length of [frames] frames of [format] in milliseconds, rounded to the nearest. */
    fun durationMs(frames: Long, format: Mp3Format): Long =
        (frames * format.samplesPerFrame * 1000 + format.sampleRate / 2) / format.sampleRate

    private fun describe(format: Mp3Format): String {
        val version = when (format.version) {
            MpegVersion.V1 -> "MPEG-1"
            MpegVersion.V2 -> "MPEG-2"
            MpegVersion.V25 -> "MPEG-2.5"
        }
        return "$version ${format.sampleRate} Hz ${if (format.channels == 1) "mono" else "stereo"}"
    }
}
