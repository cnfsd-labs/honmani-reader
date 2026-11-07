package app.honmani.reader.ui.review

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.honmani.reader.anki.VoiceProblem
import app.honmani.reader.data.HonmaniDatabase
import app.honmani.reader.data.review.ReviewRepository
import app.honmani.reader.data.settings.ReviewSettings
import app.honmani.reader.data.words.WordEntity
import app.honmani.reader.data.words.WordRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ReviewViewModelTest {
    private lateinit var database: HonmaniDatabase
    private lateinit var settings: ReviewSettings
    private val audio = FakeAudio()

    /** Records what the speaker buttons ask for; [gate] holds a sound back like a recording still being made. */
    private class FakeAudio : ReviewAudio {
        val played = mutableListOf<String>()
        val stops = mutableListOf<String>()
        var gate: CompletableDeferred<Unit>? = null
        var problem: VoiceProblem? = null

        override suspend fun word(text: String): VoiceProblem? {
            gate?.await()
            played += "word:$text"
            return problem
        }

        override suspend fun sentence(text: String, bookId: Long?, bookTitle: String?): VoiceProblem? {
            gate?.await()
            played += "sentence:$text:$bookId"
            return problem
        }

        override fun stop(reason: String) {
            stops += reason
        }
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, HonmaniDatabase::class.java).allowMainThreadQueries().build()
        settings = ReviewSettings(context.getSharedPreferences("review-test", Context.MODE_PRIVATE))
    }

    @After
    fun tearDown() {
        database.close()
        Dispatchers.resetMain()
    }

    private suspend fun word(expression: String, reading: String, rank: Double) {
        database.wordDao().insert(
            WordEntity(
                expression = expression, reading = reading, gloss = "g", lookupCount = 1, firstLookedUpAt = 1,
                lastLookedUpAt = 1, rankKey = rank, lastSentence = "${expression}が好き。", lastSurfaceStart = 0,
                lastSurfaceEnd = expression.length,
            ),
        )
    }

    private fun model(): ReviewViewModel {
        val reviews = ReviewRepository(database)
        return ReviewViewModel(reviews, WordRepository(database), settings, audio = audio)
    }

    private fun started(): ReviewViewModel = runBlocking {
        word("猫", "ねこ", 2.0)
        word("犬", "いぬ", 1.0)
        model().also {
            it.start()
            waitFor(it) { state -> state.card != null }
        }
    }

    private fun waitFor(model: ReviewViewModel, condition: (ReviewUiState) -> Boolean) = runBlocking {
        withTimeout(10_000) { model.state.first { condition(it) } }
    }

    @Test
    fun eachPressPlaysOnce_theWordByItsReading_theSentenceWithItsBook() {
        val model = started()

        model.speakWord()
        model.speakSentence()

        assertEquals(listOf("word:ねこ", "sentence:猫が好き。:null"), audio.played)
    }

    @Test
    fun aPressAgainPlaysAgain() {
        val model = started()

        model.speakWord()
        model.speakWord()

        assertEquals(listOf("word:ねこ", "word:ねこ"), audio.played)
    }

    @Test
    fun theSentenceButtonDoesNothingWhenTheFrontShowsTheWordAlone() {
        settings.setShowSentence(false)
        val model = started()

        model.speakSentence()

        assertEquals(emptyList<String>(), audio.played)
    }

    @Test
    fun leavingStopsTheSound_andARecordingStillOnItsWayNeverPlays() {
        val model = started()
        audio.gate = CompletableDeferred()

        model.speakWord()
        model.stopAudio("background")
        audio.gate!!.complete(Unit)

        assertEquals(emptyList<String>(), audio.played)
        assertEquals("background", audio.stops.last())
    }

    @Test
    fun closingTheScreenStopsTheSoundAndTheRecording() {
        val store = ViewModelStore()
        val model = started()
        val provider = ViewModelProvider(store, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = model as T
        })
        provider[ReviewViewModel::class.java]
        audio.gate = CompletableDeferred()
        model.speakSentence()

        store.clear()
        audio.gate!!.complete(Unit)

        assertEquals(emptyList<String>(), audio.played)
        assertEquals("cleared", audio.stops.last())
    }

    @Test
    fun aLateRecordingDoesNotPlayOverTheNextCard() {
        val model = started()
        val first = model.state.value.card!!.wordId
        audio.gate = CompletableDeferred()

        model.speakWord()
        model.reveal()
        model.gotIt()
        waitFor(model) { it.card?.wordId != first }
        audio.gate!!.complete(Unit)

        assertEquals(emptyList<String>(), audio.played)
        assertEquals("card", audio.stops.last())
    }

    @Test
    fun editingTheMeaningKeepsTheSound() {
        val model = started()
        val before = audio.stops.size

        model.editMeaning("a cat")
        waitFor(model) { it.card?.gloss == "a cat" }

        assertEquals(before, audio.stops.size)
    }

    @Test
    fun aFailedPressIsReported() {
        val model = started()
        audio.problem = VoiceProblem.NO_ENGINE

        model.speakWord()

        assertEquals(VoiceProblem.NO_ENGINE, runBlocking { withTimeout(10_000) { model.voiceProblems.first() } })
    }

    @Test
    fun theSwitchDecidesWhetherTheCardShowsSpeakers() {
        assertTrue(started().state.value.speakers)
        settings.setSpeakers(false)

        assertEquals(false, model().also { it.start() }.state.value.speakers)
    }
}
