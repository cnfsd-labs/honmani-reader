package app.honmani.reader.data.library

import app.honmani.reader.data.TooLargeException
import java.io.File
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * Japanese text files come in UTF-8, Shift_JIS (most Aozora Bunko originals), EUC-JP and UTF-16,
 * often without a byte order mark. ッツ Ebook Reader reads them as UTF-8 only, so the library
 * stores every text file as UTF-8.
 */
object TextEncoding {
    private const val SAMPLE_BYTES = 64 * 1024

    /** The longest Japanese novels are about 10 MB of text; the file is held in memory several times over. */
    internal const val MAX_TEXT_BYTES = 50 * 1024 * 1024

    /**
     * Rewrites [file] as UTF-8 (without BOM) unless it already is; returns the detected encoding.
     *
     * @throws TooLargeException if the file is larger than [maxBytes] (an IOException)
     */
    fun convertToUtf8(file: File, maxBytes: Int = MAX_TEXT_BYTES): Charset {
        if (file.length() > maxBytes) throw TooLargeException(maxBytes.toLong())
        val bytes = file.readBytes()
        val charset = detect(bytes)
        val start = bomLength(bytes)
        if (charset == Charsets.UTF_8 && start == 0) return charset
        val text = String(bytes, start, bytes.size - start, charset)
        file.writeText(text, Charsets.UTF_8)
        return charset
    }

    /** The most likely encoding of [bytes], judged from a byte order mark or the first 64 KB. */
    fun detect(bytes: ByteArray): Charset {
        when {
            bytes.startsWith(0xEF, 0xBB, 0xBF) -> return Charsets.UTF_8
            bytes.startsWith(0xFE, 0xFF) -> return Charsets.UTF_16BE
            bytes.startsWith(0xFF, 0xFE) -> return Charsets.UTF_16LE
        }
        val sample = bytes.copyOf(minOf(bytes.size, SAMPLE_BYTES))
        // Valid UTF-8 of any length is almost never another encoding by accident.
        if (decodes(sample, Charsets.UTF_8, complete = sample.size == bytes.size)) return Charsets.UTF_8
        val candidates = listOfNotNull(
            charset("windows-31j") ?: charset("Shift_JIS"),
            charset("EUC-JP"),
            Charsets.UTF_16BE,
            Charsets.UTF_16LE,
        )
        return candidates
            .mapNotNull { charset -> decode(sample, charset, complete = sample.size == bytes.size)?.let { charset to score(it) } }
            .maxByOrNull { it.second }
            ?.first
            ?: Charsets.UTF_8
    }

    /** Share of characters typical of Japanese prose: kana, common kanji, punctuation, ASCII. */
    internal fun score(text: String): Double {
        if (text.isEmpty()) return 0.0
        var kana = 0
        var plausible = 0
        for (c in text) {
            when (c) {
                in '぀'..'ヿ' -> {
                    kana++
                    plausible++
                }
                in '一'..'鿿', in '　'..'〿', in '＀'..'￯',
                in ' '..'~', '\n', '\r', '\t' -> plausible++
            }
        }
        // Kana weigh extra: random bytes decode to kanji or Hangul far more often than to kana.
        return (plausible + kana).toDouble() / text.length
    }

    private fun decodes(bytes: ByteArray, charset: Charset, complete: Boolean) = decode(bytes, charset, complete) != null

    /** Strict decoding; a sample may end inside a character, which is not an error. */
    private fun decode(bytes: ByteArray, charset: Charset, complete: Boolean): String? {
        val decoder = charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val out = CharBuffer.allocate(bytes.size * 2 + 16)
        return try {
            val result = decoder.decode(ByteBuffer.wrap(bytes), out, complete)
            if (result.isError) result.throwException()
            if (complete) decoder.flush(out)
            out.flip().toString()
        } catch (_: CharacterCodingException) {
            null
        }
    }

    private fun bomLength(bytes: ByteArray) = when {
        bytes.startsWith(0xEF, 0xBB, 0xBF) -> 3
        bytes.startsWith(0xFE, 0xFF) || bytes.startsWith(0xFF, 0xFE) -> 2
        else -> 0
    }

    private fun charset(name: String): Charset? = runCatching { Charset.forName(name) }.getOrNull()

    private fun ByteArray.startsWith(vararg prefix: Int) =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it].toByte() }
}
