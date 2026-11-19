package app.honmani.reader.ui.anki

import app.honmani.reader.R
import app.honmani.reader.anki.AnkiCheck
import app.honmani.reader.anki.AnkiResult
import app.honmani.reader.anki.CustomProblem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AnkiProblemNoticeTest {
    @Test
    fun anUnusableOwnNoteType_opensTheDialog_withItsProblem() {
        assertEquals(
            AnkiProblemNotice("Lapis", CustomProblem.MISSING),
            AnkiResult.CustomUnusable("Lapis", CustomProblem.MISSING).problemNotice(),
        )
        assertEquals(
            AnkiProblemNotice("Lapis", CustomProblem.NO_WORD_FIELD),
            AnkiCheck.CustomUnusable("Lapis", CustomProblem.NO_WORD_FIELD).problemNotice(),
        )
    }

    @Test
    fun otherResults_stayToasts() {
        assertNull(AnkiResult.Added(1).problemNotice())
        assertNull(AnkiResult.NotInstalled.problemNotice())
        assertNull(AnkiCheck.Done(0, 0).problemNotice())
        assertNull(AnkiCheck.Failed("x").problemNotice())
    }

    @Test
    fun eachProblem_hasItsOwnMessage() {
        assertEquals(R.string.anki_custom_not_chosen, AnkiProblemNotice("", CustomProblem.NOT_CHOSEN).message)
        assertEquals(R.string.anki_custom_missing, AnkiProblemNotice("", CustomProblem.MISSING).message)
        assertEquals(R.string.anki_custom_no_word, AnkiProblemNotice("", CustomProblem.NO_WORD_FIELD).message)
    }
}
