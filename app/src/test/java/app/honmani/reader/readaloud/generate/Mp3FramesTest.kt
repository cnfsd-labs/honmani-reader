package app.honmani.reader.readaloud.generate

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class Mp3FramesTest {
    private val v2Mono24k = Mp3Format(MpegVersion.V2, 24_000, 1)

    /** A frame of 32 kbps, 24 kHz, mono, MPEG-2 Layer III: 96 bytes, 24 ms. Filled with [fill]. */
    private fun frame(fill: Int = 0x55, padding: Boolean = false): ByteArray {
        val bytes = ByteArray(if (padding) 97 else 96) { fill.toByte() }
        bytes[0] = 0xFF.toByte()
        bytes[1] = 0xF3.toByte()
        bytes[2] = (0x44 or (if (padding) 2 else 0)).toByte()
        bytes[3] = 0xC0.toByte()
        return bytes
    }

    private fun frames(count: Int, fill: Int = 0x55): ByteArray = ByteArray(0).let { (0 until count).fold(it) { acc, _ -> acc + frame(fill) } }

    private fun id3(payload: Int): ByteArray {
        val header = byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 4, 0, 0, 0, 0, (payload shr 7).toByte(), (payload and 0x7F).toByte())
        return header + ByteArray(payload) { 0x41 }
    }

    /** A Xing frame of the same format: header, 9 bytes of side information (MPEG-2 mono), the tag. */
    private fun xing(tag: String = "Xing"): ByteArray {
        val bytes = frame(0)
        val at = 4 + 9
        tag.forEachIndexed { i, c -> bytes[at + i] = c.code.toByte() }
        return bytes
    }

    @Test
    fun headerOfA32KbpsMonoFrame() {
        val header = Mp3Frames.parseHeader(frame(), 0)!!
        assertEquals(v2Mono24k, header.format)
        assertEquals(32_000, header.bitrate)
        assertEquals(96, header.length)
        assertEquals(576, header.format.samplesPerFrame)
        assertEquals(97, Mp3Frames.parseHeader(frame(padding = true), 0)!!.length)
    }

    @Test
    fun headersOfOtherVersionsAndStereo() {
        // MPEG-1 Layer III, 128 kbps, 44.1 kHz, joint stereo: 417 bytes (padding 0).
        val v1 = Mp3Frames.parseHeader(byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x40), 0)!!
        assertEquals(Mp3Format(MpegVersion.V1, 44_100, 2), v1.format)
        assertEquals(128_000, v1.bitrate)
        assertEquals(417, v1.length)
        assertEquals(1152, v1.format.samplesPerFrame)
    }

    @Test
    fun noHeader() {
        assertNull(Mp3Frames.parseHeader(ByteArray(10), 0))
        assertNull(Mp3Frames.parseHeader(frame(), 90)) // too close to the end
        // Layer II, reserved version, free format, bad bitrate, reserved rate.
        for (header in listOf(
            byteArrayOf(0xFF.toByte(), 0xFD.toByte(), 0x44, 0xC0.toByte()),
            byteArrayOf(0xFF.toByte(), 0xEB.toByte(), 0x44, 0xC0.toByte()),
            byteArrayOf(0xFF.toByte(), 0xF3.toByte(), 0x04, 0xC0.toByte()),
            byteArrayOf(0xFF.toByte(), 0xF3.toByte(), 0xF4.toByte(), 0xC0.toByte()),
            byteArrayOf(0xFF.toByte(), 0xF3.toByte(), 0x4C, 0xC0.toByte()),
        )) assertNull(Mp3Frames.parseHeader(header, 0))
    }

    @Test
    fun durationOf32KbpsMono24kHzFrames() {
        val chunk = Mp3Frames.read(frames(100))
        assertEquals(100, chunk.frames)
        assertEquals(9_600, chunk.sizeBytes)
        // 576 samples at 24 kHz are 24 ms each.
        assertEquals(2_400L, Mp3Frames.durationMs(chunk.frames.toLong(), chunk.format))
        // 32 kbps is 4,000 bytes a second.
        assertEquals(2_400L, chunk.sizeBytes * 1000L / 4_000)
    }

    @Test
    fun durationRoundsOnlyOnceOverManyFrames() {
        val format = Mp3Format(MpegVersion.V1, 44_100, 2)
        // 1152 samples at 44.1 kHz are 26.122... ms.
        assertEquals(26L, Mp3Frames.durationMs(1, format))
        assertEquals(26_122L, Mp3Frames.durationMs(1000, format))
    }

    @Test
    fun id3AndXingFramesAreStripped() {
        val audio = frames(5)
        val chunk = Mp3Frames.read(id3(300) + xing() + audio)
        assertEquals(5, chunk.frames)
        assertArrayEquals(audio, chunk.toByteArray())
        assertEquals(5, Mp3Frames.read(xing("Info") + audio).frames)
        assertEquals(5, Mp3Frames.read(audio).frames)
        // A first frame that is audio stays.
        assertEquals(6, Mp3Frames.read(frame(0) + audio).frames)
    }

    @Test
    fun id3v1TagAtTheEndIsDropped() {
        val tag = ByteArray(128).also { it[0] = 'T'.code.toByte(); it[1] = 'A'.code.toByte(); it[2] = 'G'.code.toByte() }
        val chunk = Mp3Frames.read(frames(3) + tag)
        assertEquals(3, chunk.frames)
        assertEquals(0, chunk.truncatedBytes)
    }

    @Test
    fun aCutOffLastFrameIsDroppedAndReported() {
        val chunk = Mp3Frames.read(frames(4) + frame().copyOf(50))
        assertEquals(4, chunk.frames)
        assertEquals(50, chunk.truncatedBytes)
        assertEquals(4 * 96, chunk.sizeBytes)
    }

    @Test
    fun zeroPaddingAtTheEndIsNotAnError() {
        val chunk = Mp3Frames.read(frames(2) + ByteArray(30))
        assertEquals(2, chunk.frames)
        assertEquals(0, chunk.truncatedBytes)
    }

    @Test
    fun garbageInTheMiddleIsAnError() {
        val bytes = frames(2) + ByteArray(96) { 0x12 } + frames(2)
        try {
            Mp3Frames.read(bytes)
            fail("expected an error")
        } catch (e: Mp3Exception) {
            assertTrue(e.message!!, e.message!!.contains("192"))
        }
    }

    @Test
    fun noAudioIsAnError() {
        try {
            Mp3Frames.read(ByteArray(200) { 0x12 })
            fail("expected an error")
        } catch (_: Mp3Exception) {
        }
    }

    @Test
    fun framesOfAnotherRateInOneFileAreAnError() {
        val other = frame().also { it[2] = 0x40 } // 22.05 kHz
        try {
            Mp3Frames.read(frames(2) + other)
            fail("expected an error")
        } catch (e: Mp3Exception) {
            assertTrue(e.message!!, e.message!!.contains("22050"))
        }
    }

    @Test
    fun joinedChunksMustShareTheFormat() {
        val a = Mp3Frames.read(id3(10) + frames(3, 0x11))
        val b = Mp3Frames.read(xing() + frames(2, 0x22))
        val out = ByteArrayOutputStream()
        assertEquals(5L, Mp3Frames.join(listOf(a, b), out))
        assertArrayEquals(frames(3, 0x11) + frames(2, 0x22), out.toByteArray())
        assertEquals(5, Mp3Frames.read(out.toByteArray()).frames)

        // 16 kHz instead of 24 kHz (rate index 2).
        val odd = ByteArray(144) { 0x55 }.also { it[0] = 0xFF.toByte(); it[1] = 0xF3.toByte(); it[2] = 0x48; it[3] = 0xC0.toByte() }
        val c = Mp3Frames.read(odd + odd)
        try {
            Mp3Frames.join(listOf(a, c), ByteArrayOutputStream())
            fail("expected an error")
        } catch (e: Mp3Exception) {
            assertTrue(e.message!!, e.message!!.contains("16000"))
        }
        // Stereo instead of mono, MPEG-1 instead of MPEG-2.
        assertTrue(a.format != Mp3Format(v2Mono24k.version, 24_000, 2))
        try {
            Mp3Frames.requireSame(a.format, Mp3Format(MpegVersion.V1, 24_000, 1))
            fail("expected an error")
        } catch (_: Mp3Exception) {
        }
    }
}
