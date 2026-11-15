package app.honmani.reader.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LibraryMessageTest {
    @Test
    fun parsesReplies() {
        assertEquals(
            LibraryMessage.Ready(requestId = 3, id = 7, title = "吾輩は猫である"),
            LibraryMessage.parse("""{"type":"library.ready","requestId":3,"id":7,"title":"吾輩は猫である"}"""),
        )
        assertEquals(
            LibraryMessage.Failed(requestId = 4, message = "Error importing x"),
            LibraryMessage.parse("""{"type":"library.failed","requestId":4,"message":"Error importing x"}"""),
        )
        assertEquals(LibraryMessage.ThemeChanged, LibraryMessage.parse("""{"type":"library.theme"}"""))
        assertEquals(
            LibraryMessage.Removed(requestId = 5),
            LibraryMessage.parse("""{"type":"library.removed","requestId":5}"""),
        )
        assertEquals(
            LibraryMessage.ProgressReset(requestId = 6),
            LibraryMessage.parse("""{"type":"library.reset","requestId":6}"""),
        )
        assertEquals(
            LibraryMessage.Settings(requestId = 7, values = mapOf("fontSize" to "28", "theme" to null)),
            LibraryMessage.parse("""{"type":"library.settings","requestId":7,"settings":{"fontSize":"28","theme":null}}"""),
        )
        assertEquals(
            LibraryMessage.Bookmark(requestId = 8, characters = 918, modified = 1_792_438_152_000),
            LibraryMessage.parse("""{"type":"library.bookmark","requestId":8,"characters":918,"modified":1792438152000}"""),
        )
        assertEquals(
            LibraryMessage.Bookmark(requestId = 9, characters = null, modified = 0),
            LibraryMessage.parse("""{"type":"library.bookmark","requestId":9,"characters":null,"modified":0}"""),
        )
        assertEquals(
            LibraryMessage.BookmarkSet(requestId = 10),
            LibraryMessage.parse("""{"type":"library.bookmarkSet","requestId":10}"""),
        )
    }

    @Test
    fun parsesProgress_clampedAndFiltered() {
        val message = LibraryMessage.parse(
            """{"type":"library.progress","books":[{"id":1,"progress":0.25,"characters":1200},{"id":2,"progress":1.5,"characters":null},{"id":0,"progress":0.5}]}""",
        )

        assertEquals(
            LibraryMessage.Progress(listOf(TtuProgress(1, 0.25f, 1_200), TtuProgress(2, 1f))),
            message,
        )
    }

    @Test
    fun rejectsMalformedMessages() {
        assertNull(LibraryMessage.parse(null))
        assertNull(LibraryMessage.parse("not json"))
        assertNull(LibraryMessage.parse("""{"type":"lookup","text":"猫"}"""))
        assertNull(LibraryMessage.parse("""{"type":"library.ready","requestId":3,"title":"t"}"""))
        assertNull(LibraryMessage.parse("""{"type":"library.ready","requestId":3,"id":-1,"title":"t"}"""))
        assertNull(LibraryMessage.parse("""{"type":"library.ready","id":1,"title":"t"}"""))
        assertNull(LibraryMessage.parse("""{"type":"library.removed"}"""))
    }

    @Test
    fun bridgeMessage_routesLibraryMessages() {
        assertEquals(
            BridgeMessage.Library(LibraryMessage.Removed(1)),
            BridgeMessage.parse("""{"type":"library.removed","requestId":1}"""),
        )
    }
}
