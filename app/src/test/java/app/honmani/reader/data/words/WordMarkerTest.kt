package app.honmani.reader.data.words

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WordMarkerTest {
    @Test
    fun staleRequestIsSkippedWithoutBuildingAnything() {
        var sourcesAsked = 0
        val marker = WordMarker(
            sources = { sourcesAsked++; null },
            lexicon = { error("not needed") },
            knowledge = { error("not needed") },
        )
        runBlocking {
            assertNull(marker.mark(listOf("本"), WordMarksMode.entries.first(), isStale = { true }))
        }
        assertEquals(0, sourcesAsked)
    }

    @Test
    fun freshRequestAsksForSources() {
        var sourcesAsked = 0
        val marker = WordMarker(
            sources = { sourcesAsked++; null },
            lexicon = { error("not needed") },
            knowledge = { error("not needed") },
        )
        runBlocking {
            assertNull(marker.mark(listOf("本"), WordMarksMode.entries.first()))
        }
        assertEquals(1, sourcesAsked)
    }
}
