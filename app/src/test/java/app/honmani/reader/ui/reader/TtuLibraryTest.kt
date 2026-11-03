package app.honmani.reader.ui.reader

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TtuLibraryTest {
    /** Records scripts; [answer] replies to the latest command as the bridge would. */
    private class FakePage : TtuPage {
        val scripts = mutableListOf<String>()
        val loads = mutableListOf<String>()

        override fun evaluate(script: String) {
            scripts += script
        }

        override fun load(path: String) {
            loads += path
        }

        fun lastArguments() = Json.parseToJsonElement(
            scripts.last().substringAfter("(").substringBeforeLast(")"),
        ).jsonObject

        fun lastRequestId() = lastArguments()["requestId"]!!.jsonPrimitive.long
    }

    private val library = TtuLibrary(pageTimeoutMs = 1_000, importTimeoutMs = 5_000)
    private val page = FakePage()

    @Test
    fun ensure_sendsTheBookAndReturnsTtusAnswer() = runTest {
        library.attach(page)
        val result = async { library.ensure(TtuBook(3, "猫"), "/honmani-books/a.epub", "猫.epub") }
        runCurrent()

        val arguments = page.lastArguments()
        assertTrue(page.scripts.last().startsWith("window.honmaniLibrary && honmaniLibrary.ensure("))
        assertEquals(3L, arguments["ttuId"]!!.jsonPrimitive.long)
        assertEquals("猫", arguments["ttuTitle"]!!.jsonPrimitive.content)
        assertEquals("/honmani-books/a.epub", arguments["url"]!!.jsonPrimitive.content)
        assertEquals("猫.epub", arguments["fileName"]!!.jsonPrimitive.content)

        library.onMessage(LibraryMessage.Ready(page.lastRequestId(), 9, "吾輩は猫である"))
        assertEquals(TtuBook(9, "吾輩は猫である"), result.await())
    }

    @Test
    fun ensure_sendsTheIdsOfOtherBooksToKeepAndTheTitleSuffix() = runTest {
        library.attach(page)
        val result = async { library.ensure(null, "/u", "a.epub", keepIds = listOf(4L, 7L), titleSuffix = " (9)") }
        runCurrent()

        val arguments = page.lastArguments()
        assertEquals(listOf(4L, 7L), arguments["keepIds"]!!.jsonArray.map { it.jsonPrimitive.long })
        assertEquals(" (9)", arguments["titleSuffix"]!!.jsonPrimitive.content)
        library.onMessage(LibraryMessage.Ready(page.lastRequestId(), 1, "a (9)"))
        result.await()
    }

    @Test
    fun ensure_withoutAKnownBook_sendsNulls() = runTest {
        library.attach(page)
        val result = async { library.ensure(null, "/honmani-books/a.epub", "a.epub") }
        runCurrent()

        assertEquals("null", page.lastArguments()["ttuId"].toString())
        library.onMessage(LibraryMessage.Ready(page.lastRequestId(), 1, "a"))
        result.await()
    }

    @Test
    fun ensure_failureThrows() = runTest {
        library.attach(page)
        val result = async { runCatching { library.ensure(null, "/u", "a.epub") } }
        runCurrent()

        library.onMessage(LibraryMessage.Failed(page.lastRequestId(), "Error importing a"))
        assertEquals("Error importing a", result.await().exceptionOrNull()?.message)
    }

    @Test
    fun answersToOtherRequestsAreIgnored() = runTest {
        library.attach(page)
        val result = async { library.ensure(null, "/u", "a.epub") }
        runCurrent()

        library.onMessage(LibraryMessage.Ready(page.lastRequestId() + 100, 1, "other"))
        runCurrent()
        assertTrue(result.isActive)
        library.onMessage(LibraryMessage.Ready(page.lastRequestId(), 2, "a"))
        assertEquals(TtuBook(2, "a"), result.await())
    }

    @Test
    fun commandsWaitForAPage() = runTest {
        val result = async { library.ensure(null, "/u", "a.epub") }
        runCurrent()
        assertTrue(page.scripts.isEmpty())

        library.attach(page)
        runCurrent()
        library.onMessage(LibraryMessage.Ready(page.lastRequestId(), 1, "a"))
        assertEquals(TtuBook(1, "a"), result.await())
    }

    @Test
    fun aReloadFailsPendingCommands() = runTest {
        library.attach(page)
        val result = async { runCatching { library.ensure(null, "/u", "a.epub") } }
        runCurrent()

        library.detach()
        assertTrue(result.await().exceptionOrNull() is TtuLibraryException)
    }

    @Test
    fun noAnswerTimesOut() = runTest {
        library.attach(page)
        try {
            library.remove(4)
            fail("Expected a timeout")
        } catch (_: TtuLibraryException) {
            // Expected.
        }
    }

    @Test
    fun noPageTimesOut() = runTest {
        try {
            library.open(4)
            fail("Expected a timeout")
        } catch (_: TtuLibraryException) {
            // Expected.
        }
    }

    @Test
    fun open_opensTheBookInsideTheRunningPage() = runTest {
        library.attach(page)
        library.open(12)

        assertEquals(emptyList<String>(), page.loads)
        assertEquals("window.honmaniLibrary && honmaniLibrary.open({\"ttuId\":12});", page.scripts.single())
        assertEquals(12L, page.lastArguments()["ttuId"]!!.jsonPrimitive.long)
    }

    @Test
    fun openContents_asksThePageToOpenTtusTableOfContents_andNeedsAPage() {
        library.openContents()
        assertEquals(emptyList<String>(), page.scripts)

        library.attach(page)
        library.openContents()

        assertEquals("window.honmaniReaderMenu && honmaniReaderMenu.openContents();", page.scripts.last())
    }

    @Test
    fun show_navigatesInsideTheRunningPage_andTheAccentFollowsEveryPage() = runTest {
        library.setAccent("#d0bcff", "#381e72")
        library.attach(page)
        assertEquals("window.honmaniSetAccent && honmaniSetAccent(\"#d0bcff\", \"#381e72\");", page.scripts.single())

        library.show("/settings")

        assertEquals(emptyList<String>(), page.loads)
        assertEquals(
            "window.honmaniLibrary ? honmaniLibrary.navigate({\"path\":\"/settings\"}) : location.assign(\"/settings\");",
            page.scripts.last(),
        )
    }

    @Test
    fun readerSettings_areReadWrittenAndAppliedByAReload() = runTest {
        library.attach(page)
        val values = async { library.readSettings() }
        runCurrent()
        assertTrue(page.scripts.last().startsWith("window.honmaniReaderSettings && honmaniReaderSettings.read("))
        library.onMessage(LibraryMessage.Settings(page.lastRequestId(), mapOf("fontSize" to "30", "theme" to null)))
        assertEquals(mapOf("fontSize" to "30", "theme" to null), values.await())

        library.writeSettings(mapOf("fontSize" to "32"))
        assertEquals("32", page.lastArguments()["settings"]!!.jsonObject["fontSize"]!!.jsonPrimitive.content)
        library.reload()
        assertTrue(page.scripts.last().contains("honmaniReaderSettings.reload()"))
        // To ttu's full settings with the changes: a page load there (once the reload is done).
        library.attach(page)
        library.reload("/settings")
        assertEquals(listOf("/settings"), page.loads)
    }

    @Test
    fun openHit_savesTheBookmarkThenReloadsThePageToMarkTheHit() = runTest {
        library.attach(page)
        val opening = async { library.openHit(5, 1234, 0.25, "吾輩は", "猫") }
        runCurrent()

        assertTrue(page.scripts.last().startsWith("window.honmaniLibrary && honmaniLibrary.setBookmark("))
        val arguments = page.lastArguments()
        assertEquals(5L, arguments["ttuId"]!!.jsonPrimitive.long)
        assertEquals(1234L, arguments["characters"]!!.jsonPrimitive.long)
        library.onMessage(LibraryMessage.BookmarkSet(page.lastRequestId()))
        opening.await()

        assertTrue(page.scripts.last().startsWith("window.honmaniSearch ? honmaniSearch.reload("))
        val hit = Json.parseToJsonElement(page.scripts.last().substringAfter("honmaniSearch.reload(").substringBefore(") :")).jsonObject
        assertEquals("吾輩は", hit["before"]!!.jsonPrimitive.content)
        assertEquals("猫", hit["match"]!!.jsonPrimitive.content)
        // The page being replaced gets no further commands.
        val next = async { library.open(5) }
        runCurrent()
        assertTrue(page.scripts.none { it.contains("honmaniLibrary.open(") })
        library.attach(FakePage())
        next.await()
    }

    @Test
    fun afterAReload_commandsWaitForTheNewPage() = runTest {
        library.attach(page)
        library.reload()
        val reloading = page.scripts.size

        // A book tapped right after closing the settings: not sent to the page being replaced.
        val opening = async { library.open(7) }
        runCurrent()
        assertEquals(reloading, page.scripts.size)

        val next = FakePage()
        library.attach(next)
        opening.await()
        assertTrue(next.scripts.last().contains("honmaniLibrary.open({\"ttuId\":7})"))
    }

    @Test
    fun downloadedFontsAreListedForTtu() {
        library.attach(page)

        library.writeFonts(mapOf("Klee One" to "KleeOne-Regular.ttf"))

        assertTrue(page.scripts.last().startsWith("window.honmaniReaderSettings && honmaniReaderSettings.fonts("))
        val font = page.lastArguments()["fonts"]!!.jsonArray.single().jsonObject
        assertEquals("Klee One", font["name"]!!.jsonPrimitive.content)
        assertEquals("KleeOne-Regular.ttf", font["fileName"]!!.jsonPrimitive.content)
    }

    @Test
    fun progressReportsAreForwarded() = runTest {
        library.attach(page)
        val reported = async { library.progress.first() }
        runCurrent()

        library.requestProgress()
        assertEquals("window.honmaniLibrary && honmaniLibrary.progress();", page.scripts.last())
        library.onMessage(LibraryMessage.Progress(listOf(TtuProgress(1, 0.5f))))
        assertEquals(listOf(TtuProgress(1, 0.5f)), reported.await())
    }
}
