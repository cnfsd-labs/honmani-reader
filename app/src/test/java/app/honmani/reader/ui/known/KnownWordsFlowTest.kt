package app.honmani.reader.ui.known

import app.honmani.reader.R
import app.honmani.reader.data.dictionary.ImportError
import app.honmani.reader.data.dictionary.ImportStatus
import app.honmani.reader.known.QuizSetup
import app.honmani.reader.known.WordListTooLargeException
import java.io.FileNotFoundException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KnownWordsFlowTest {
    private val setup = QuizSetup("JPDB", 10_000, emptyList())

    @Test
    fun backInTheQuizUndoesTheLastAnswer() {
        val step = quizStepBack(KnownWordsFlow.Step.Quiz(setup, listOf(true, false)))
        assertEquals(listOf(true), (step as KnownWordsFlow.Step.Quiz).answers)
    }

    @Test
    fun backAtTheFirstQuestionLeaves() {
        assertNull(quizStepBack(KnownWordsFlow.Step.Quiz(setup)))
    }

    @Test
    fun backFromTheResultReturnsToTheLastQuestion() {
        val step = quizStepBack(KnownWordsFlow.Step.QuizResult(setup, listOf(true, true, false), 3000))
        assertEquals(listOf(true, true), (step as KnownWordsFlow.Step.Quiz).answers)
    }

    @Test
    fun aFailedOrFinishedDownloadIsNotPending() {
        assertFalse(frequencyPending(emptyList()))
        assertFalse(frequencyPending(listOf(ImportStatus.Failed("1", ImportError.DOWNLOAD_FAILED, null, "jiten"))))
        assertFalse(frequencyPending(listOf(ImportStatus.Queued("2", replaces = 4))))
    }

    @Test
    fun aQueuedOrRunningDownloadIsPending() {
        assertTrue(frequencyPending(listOf(ImportStatus.Queued("1"))))
        assertTrue(frequencyPending(listOf(ImportStatus.Running("1", progress = null))))
    }

    @Test
    fun fileErrorsSayTheFileCouldNotBeRead() {
        assertEquals(R.string.known_file_unreadable, fileProblem(SecurityException("revoked")))
        assertEquals(R.string.known_file_unreadable, fileProblem(FileNotFoundException("gone")))
        assertEquals(R.string.known_file_too_large, fileProblem(WordListTooLargeException()))
        assertNull(fileProblem(IllegalStateException()))
    }
}
