package app.honmani.reader.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RestoredBooksTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun forgettingAPositionKeepsOtherPositionsAndWaitingBooks() {
        val file = folder.root.resolve("restored-books.json")
        val restored = RestoredBooks(file)
        restored.addWaiting(listOf(WaitingBook(key = "waiting", id = -1)))
        restored.setPosition("a", RestoredPosition(100, 0.1f))
        restored.setPosition("b", RestoredPosition(200, 0.2f))

        restored.forgetPosition("a")

        val reloaded = RestoredBooks(file)
        assertNull(reloaded.takePosition("a"))
        assertEquals(RestoredPosition(200, 0.2f), reloaded.takePosition("b"))
        assertEquals(listOf("waiting"), reloaded.waiting().map { it.key })
    }

    @Test
    fun aChangeThatCouldNotBeWrittenIsNotKeptInMemory() {
        // A non-empty directory cannot be replaced by the file, so the write fails.
        val file = folder.newFolder("restored-books.json")
        file.resolve("inner").writeText("x")
        val restored = RestoredBooks(file)

        restored.addWaiting(listOf(WaitingBook(key = "waiting", id = -1)))
        restored.setPosition("a", RestoredPosition(100, 0.1f))

        assertEquals(emptyList<WaitingBook>(), restored.waiting())
        assertNull(restored.takePosition("a"))
    }

    @Test
    fun aPositionIsTakenOnceEvenIfTheFileCannotBeWritten() {
        val file = folder.root.resolve("restored-books.json")
        RestoredBooks(file).setPosition("a", RestoredPosition(100, 0.1f))
        val restored = RestoredBooks(file)
        // A folder that cannot be written to: the file keeps the position.
        folder.root.setWritable(false)
        try {
            assertEquals(RestoredPosition(100, 0.1f), restored.takePosition("a"))
            assertNull(restored.takePosition("a"))
        } finally {
            folder.root.setWritable(true)
        }
    }
}
