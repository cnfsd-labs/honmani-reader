package app.honmani.reader.ui.reader

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PathHandlerHeadersTest {
    @Test
    fun bookResponsesAreNotSniffed() {
        val dir = kotlin.io.path.createTempDirectory().toFile()
        val name = "a".repeat(64) + ".epub"
        File(dir, name).writeText("x")
        val handler = BookPathHandler(dir)
        assertEquals("nosniff", handler.handle(name).responseHeaders["X-Content-Type-Options"])
        assertEquals("nosniff", handler.handle("missing").responseHeaders["X-Content-Type-Options"])
        dir.deleteRecursively()
    }

    @Test
    fun fontMissResponseIsNotSniffed() {
        val dir = kotlin.io.path.createTempDirectory().toFile()
        assertEquals("nosniff", FontPathHandler(dir).handle("nope.ttf").responseHeaders["X-Content-Type-Options"])
        dir.deleteRecursively()
    }
}
