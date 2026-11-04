package app.honmani.reader.readaloud

import android.content.SharedPreferences
import java.io.File
import java.security.MessageDigest

/** The audio and subtitles a book is paired with. [srtHash] names the subtitle copy the matching was made from. */
data class AudiobookPairing(val audio: String, val srtHash: String)

/**
 * What the reader chose for each book's audiobook, on this phone only: the audio file (its content
 * URI; the app holds read permission for it), a copy of the subtitle file kept in app storage, and
 * where the audio was last. The three go in the "device" preferences (never backed up) under the
 * book's content hash; the subtitle copy and the matching worked out from it
 * ([AudiobookAlignment], a cache) are files in [directory]. Call the file functions off the main thread.
 */
class AudiobookStore(
    private val preferences: SharedPreferences,
    private val directory: File,
) {
    fun pairing(bookHash: String): AudiobookPairing? {
        val audio = preferences.getString(key(bookHash, AUDIO), null) ?: return null
        val srt = preferences.getString(key(bookHash, SRT), null) ?: return null
        return AudiobookPairing(audio, srt)
    }

    /** Pairs the book with [audio] and the subtitles in [srtBytes]; the position starts over. */
    fun pair(bookHash: String, audio: String, srtBytes: ByteArray): AudiobookPairing {
        val hash = sha256(srtBytes)
        directory.mkdirs()
        deleteFiles(bookHash)
        File(directory, "${name(bookHash)}.srt").writeBytes(srtBytes)
        preferences.edit()
            .putString(key(bookHash, AUDIO), audio)
            .putString(key(bookHash, SRT), hash)
            .remove(key(bookHash, POSITION))
            .apply()
        return AudiobookPairing(audio, hash)
    }

    /** The subtitle copy as text (UTF-8, or UTF-16 with a byte order mark), or null if it is gone. */
    fun subtitles(bookHash: String): String? {
        val file = File(directory, "${name(bookHash)}.srt").takeIf { it.isFile } ?: return null
        val bytes = file.readBytes()
        return when {
            bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
            bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
            else -> String(bytes, Charsets.UTF_8)
        }
    }

    fun alignment(bookHash: String, srtHash: String): AudiobookAlignment? =
        File(directory, "${name(bookHash)}-$srtHash.align").takeIf { it.isFile }?.let { AudiobookAlignment.parse(it.readText()) }

    fun saveAlignment(bookHash: String, srtHash: String, alignment: AudiobookAlignment) {
        directory.mkdirs()
        File(directory, "${name(bookHash)}-$srtHash.align").writeText(alignment.serialize())
    }

    fun position(bookHash: String): Long = preferences.getLong(key(bookHash, POSITION), 0L).coerceAtLeast(0)

    fun savePosition(bookHash: String, ms: Long) {
        // Only for a book that is paired: a late save must not bring a removed pairing's position back.
        if (preferences.contains(key(bookHash, AUDIO))) preferences.edit().putLong(key(bookHash, POSITION), ms).apply()
    }

    /** Removes the pairing and everything kept for it. */
    fun forget(bookHash: String) {
        preferences.edit()
            .remove(key(bookHash, AUDIO))
            .remove(key(bookHash, SRT))
            .remove(key(bookHash, POSITION))
            .apply()
        deleteFiles(bookHash)
    }

    private fun deleteFiles(bookHash: String) {
        val prefix = name(bookHash)
        directory.listFiles { file -> file.name == "$prefix.srt" || file.name.startsWith("$prefix-") }?.forEach { it.delete() }
    }

    private fun key(bookHash: String, field: String) = "audiobook.${name(bookHash)}.$field"

    /** The hash as a file name part. */
    private fun name(bookHash: String) = bookHash.filter { it.isLetterOrDigit() }

    companion object {
        private const val AUDIO = "audio"
        private const val SRT = "srt"
        private const val POSITION = "position"

        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).take(16).joinToString("") { "%02x".format(it) }
    }
}
