package app.honmani.reader.data.library

import java.io.File
import java.nio.charset.Charset
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TextEncodingTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val text = "吾輩は猫である。名前はまだ無い。\r\nどこで生れたかとんと見当がつかぬ。《ルビ》ｶﾀｶﾅ ASCII 123\n".repeat(50)

    private fun roundTrip(bytes: ByteArray): Pair<Charset, String> {
        val file = File(folder.root, "book.txt").apply { writeBytes(bytes) }
        val charset = TextEncoding.convertToUtf8(file)
        return charset to file.readText(Charsets.UTF_8)
    }

    @Test
    fun utf8StaysAsItIs() {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val file = File(folder.root, "same.txt").apply { writeBytes(bytes) }
        assertEquals(Charsets.UTF_8, TextEncoding.convertToUtf8(file))
        assertEquals(bytes.toList(), file.readBytes().toList())
    }

    @Test
    fun byteOrderMarksAreRemoved() {
        assertEquals(Charsets.UTF_8 to text, roundTrip(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + text.toByteArray()))
        assertEquals(Charsets.UTF_16BE to text, roundTrip(byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + text.toByteArray(Charsets.UTF_16BE)))
        assertEquals(Charsets.UTF_16LE to text, roundTrip(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + text.toByteArray(Charsets.UTF_16LE)))
    }

    @Test
    fun utf16WithoutByteOrderMark() {
        assertEquals(text, roundTrip(text.toByteArray(Charsets.UTF_16BE)).second)
        assertEquals(text, roundTrip(text.toByteArray(Charsets.UTF_16LE)).second)
    }

    @Test
    fun shiftJisAndEucJp() {
        assertEquals(text, roundTrip(text.toByteArray(Charset.forName("windows-31j"))).second)
        assertEquals(text, roundTrip(text.toByteArray(Charset.forName("EUC-JP"))).second)
    }

    @Test
    fun aLongShiftJisFileCutInsideACharacterIsStillShiftJis() {
        val long = text.repeat(40) // well over the 64 KB sample
        assertEquals(long, roundTrip(long.toByteArray(Charset.forName("windows-31j"))).second)
    }

    @Test
    fun aFileOverTheLimitIsRefusedAndLeftAlone() {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val file = File(folder.root, "huge.txt").apply { writeBytes(bytes) }
        try {
            TextEncoding.convertToUtf8(file, maxBytes = bytes.size - 1)
            org.junit.Assert.fail("expected TooLargeException")
        } catch (_: app.honmani.reader.data.TooLargeException) {
        }
        assertEquals(bytes.toList(), file.readBytes().toList())
        assertEquals(Charsets.UTF_8, TextEncoding.convertToUtf8(file, maxBytes = bytes.size))
    }
}
