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
}
