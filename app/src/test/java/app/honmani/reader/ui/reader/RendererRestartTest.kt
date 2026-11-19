package app.honmani.reader.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class RendererRestartTest {
    private val library = "$READER_ORIGIN/manage"

    @Test
    fun reopensTheBookThatWasOpen() {
        assertEquals("$READER_ORIGIN/b?id=7", RendererRestart().startUrl(7, nowMs = 1_000, libraryUrl = library))
    }

    @Test
    fun withoutABook_startsAtTheLibrary() {
        assertEquals(library, RendererRestart().startUrl(null, nowMs = 1_000, libraryUrl = library))
    }

    @Test
    fun aSecondLossOnTheSameBookSoonAfter_startsAtTheLibrary() {
        val restart = RendererRestart()
        restart.startUrl(7, nowMs = 1_000, libraryUrl = library)
        assertEquals(library, restart.startUrl(7, nowMs = 20_000, libraryUrl = library))
    }

    @Test
    fun aLaterLossOrAnotherBook_reopensTheBook() {
        val restart = RendererRestart()
        restart.startUrl(7, nowMs = 1_000, libraryUrl = library)
        assertEquals("$READER_ORIGIN/b?id=8", restart.startUrl(8, nowMs = 2_000, libraryUrl = library))
        assertEquals("$READER_ORIGIN/b?id=8", restart.startUrl(8, nowMs = 2_000 + RendererRestart.REPEAT_WINDOW_MS, libraryUrl = library))
    }
}
