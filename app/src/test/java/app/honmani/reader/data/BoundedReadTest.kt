package app.honmani.reader.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class BoundedReadTest {
    @Test
    fun readsEverythingUpToTheLimit() {
        val bytes = ByteArray(100_000) { it.toByte() }
        assertArrayEquals(bytes, bytes.inputStream().readBytesAtMost(bytes.size))
        assertArrayEquals(ByteArray(0), ByteArray(0).inputStream().readBytesAtMost(0))
    }

    @Test
    fun refusesOneByteMoreThanTheLimit() {
        try {
            ByteArray(1001).inputStream().readBytesAtMost(1000)
            fail("expected TooLargeException")
        } catch (e: TooLargeException) {
            assertEquals(1000L, e.limit)
        }
    }

    @Test
    fun doesNotReadFarBeyondTheLimit() {
        // An endless stream (a zip bomb in effect) must stop at the limit, not run out of memory.
        val endless = object : java.io.InputStream() {
            override fun read() = 0
        }
        try {
            endless.readBytesAtMost(1 shl 20)
            fail("expected TooLargeException")
        } catch (_: TooLargeException) {
        }
    }
}
