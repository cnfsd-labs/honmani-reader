package app.honmani.reader.ui.dictionaries

import app.honmani.reader.data.dictionary.ImportError
import app.honmani.reader.data.dictionary.ImportStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class ImportCardsTest {
    private val queued = ImportStatus.Queued("a")
    private val running = ImportStatus.Running("a", progress = null)
    private val failed = ImportStatus.Failed("a", ImportError.DOWNLOAD_FAILED, null)

    @Test
    fun aNewImportAndItsFailureAreBroughtIntoView() {
        assertEquals(0, firstNewCard(emptySet(), listOf(queued)))
        assertEquals(0, firstNewCard(importCards(listOf(running)), listOf(failed)))
        // A failure without a card before it (the worker was quicker than the screen).
        assertEquals(0, firstNewCard(emptySet(), listOf(failed)))
        assertEquals(1, firstNewCard(importCards(listOf(failed)), listOf(failed, ImportStatus.Queued("b"))))
    }

    @Test
    fun progressAndAFinishedImportLeaveTheListWhereItIs() {
        assertEquals(null, firstNewCard(importCards(listOf(queued)), listOf(running)))
        assertEquals(null, firstNewCard(importCards(listOf(failed)), listOf(failed)))
        assertEquals(null, firstNewCard(importCards(listOf(running)), emptyList()))
    }

    @Test
    fun anUpdateIsBroughtIntoViewOnlyWhenItFails() {
        val update = ImportStatus.Queued("a", replaces = 3)
        assertEquals(null, firstNewCard(emptySet(), listOf(update)))
        assertEquals(0, firstNewCard(importCards(listOf(update)), listOf(failed.copy(replaces = 3))))
    }
}
