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
import app.honmani.reader.data.settings.ReviewAutoPlay
import app.honmani.reader.data.settings.ReviewAutoPlayContent
import app.honmani.reader.data.settings.ReviewSettings
import app.honmani.reader.data.words.WordEntity
import app.honmani.reader.data.words.WordRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
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
    private var ringerNormal = true

    /** Records what the speaker buttons ask for; [gate] holds a sound back like a recording still being made. */
    private class FakeAudio : ReviewAudio {
        val played = java.util.concurrent.CopyOnWriteArrayList<String>()
        val stops = mutableListOf<String>()
        val ownStops = mutableListOf<String>()
        val phoneOnly = java.util.concurrent.CopyOnWriteArrayList<Boolean>()
        var gate: CompletableDeferred<Unit>? = null
        var problem: VoiceProblem? = null

        override suspend fun word(text: String, phoneOnly: Boolean): VoiceProblem? {
            gate?.await()
            played += "word:$text"
            this.phoneOnly += phoneOnly
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

        override fun stopOwn(reason: String) {
            stops += reason
            ownStops += reason
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
        return ReviewViewModel(reviews, WordRepository(database), settings, audio = audio, ringerNormal = { ringerNormal })
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

    /** What was played once at least [count] sounds have started: auto-play starts a moment after the card shows. */
    private fun played(count: Int): List<String> = runBlocking {
        withTimeout(10_000) { while (audio.played.size < count) delay(10) }
        audio.played.toList()
    }

    /** What was played after giving a sound that should not come a moment to show up. */
    private fun playedAfterAWhile(): List<String> = runBlocking {
        delay(150)
        audio.played.toList()
    }

    private fun autoPlay(mode: ReviewAutoPlay, content: ReviewAutoPlayContent = ReviewAutoPlayContent.WORD) {
        settings.setAutoPlay(mode)
        settings.setAutoPlayContent(content)
    }

    private fun nextCard(model: ReviewViewModel) {
        val first = model.state.value.card!!.wordId
        model.gotIt()
        waitFor(model) { it.card?.wordId != first }
    }

    @Test
    fun autoPlayIsOffByDefault_andOffPlaysNothing() {
        val model = started()

        model.reveal()

        assertEquals(ReviewAutoPlay.OFF, settings.autoPlay.value)
        assertEquals(emptyList<String>(), playedAfterAWhile())
    }

    @Test
    fun onReveal_theWordPlaysOnceWithThePhoneVoice_whenTheAnswerShows() {
        autoPlay(ReviewAutoPlay.ON_REVEAL)
        val model = started()
        assertEquals(emptyList<String>(), playedAfterAWhile())

        model.reveal()
        model.reveal()

        assertEquals(listOf("word:ねこ"), playedAfterAWhile())
        assertEquals(listOf(true), audio.phoneOnly)
    }

    @Test
    fun theButtonsKeepTheChosenVoice() {
        val model = started()

        model.speakWord()

        assertEquals(listOf(false), audio.phoneOnly)
    }

    @Test
    fun onReveal_aRotationOrAnEditDoesNotPlayAgain() {
        autoPlay(ReviewAutoPlay.ON_REVEAL)
        val model = started()
        model.reveal()

        model.editMeaning("a cat")
        waitFor(model) { it.card?.gloss == "a cat" }
        model.reveal()
        // The screen asks for the queue again after a rotation, and the answer is hidden again.
        model.start()
        waitFor(model) { it.card != null }
        model.reveal()

        assertEquals(listOf("word:ねこ"), playedAfterAWhile())
    }

    @Test
    fun onCardShow_theFrontPlaysAtOnce_notTheReveal_andAnEditOrRotationDoesNotRepeatIt() {
        autoPlay(ReviewAutoPlay.ON_SHOW)
        val model = started()
        assertEquals(listOf("word:ねこ"), played(1))

        model.reveal()
        model.editMeaning("a cat")
        waitFor(model) { it.card?.gloss == "a cat" }
        model.start()
        waitFor(model) { it.card != null }

        assertEquals(listOf("word:ねこ"), playedAfterAWhile())
    }

    @Test
    fun onCardShow_eachNewCardPlays_andUndoPlaysTheFrontAgain() {
        autoPlay(ReviewAutoPlay.ON_SHOW)
        val model = started()

        nextCard(model)
        assertEquals(listOf("word:ねこ", "word:いぬ"), played(2))
        model.undo()
        waitFor(model) { it.card?.expression == "猫" }

        assertEquals(listOf("word:ねこ", "word:いぬ", "word:ねこ"), played(3))
    }

    @Test
    fun onCardShow_aRecordingOfTheOldCardNeverPlaysOverTheNextOne() {
        autoPlay(ReviewAutoPlay.ON_SHOW)
        audio.gate = CompletableDeferred()
        val model = started()

        nextCard(model)
        audio.gate!!.complete(Unit)

        assertEquals(listOf("word:いぬ"), playedAfterAWhile())
    }

    @Test
    fun leavingCancelsAnAutoPlayOnItsWay() {
        autoPlay(ReviewAutoPlay.ON_SHOW)
        audio.gate = CompletableDeferred()
        val model = started()

        model.stopAudio("background")
        audio.gate!!.complete(Unit)

        assertEquals(emptyList<String>(), playedAfterAWhile())
    }

    @Test
    fun autoPlayCanSayTheSentence() {
        autoPlay(ReviewAutoPlay.ON_REVEAL, ReviewAutoPlayContent.SENTENCE)
        val model = started()

        model.reveal()

        assertEquals(listOf("sentence:猫が好き。:null"), played(1))
    }

    @Test
    fun aSentenceAutoPlayFallsBackToTheWordWhenTheFrontShowsNoSentence() {
        autoPlay(ReviewAutoPlay.ON_REVEAL, ReviewAutoPlayContent.SENTENCE)
        settings.setShowSentence(false)
        val model = started()

        model.reveal()

        assertEquals(listOf("word:ねこ"), played(1))
    }

    @Test
    fun aNewCardStopsOnlyASoundReviewStarted() {
        val model = started()

        nextCard(model)
        assertEquals(emptyList<String>(), audio.stops)

        model.speakWord()
        nextCard(model)
        assertEquals(listOf("card"), audio.ownStops.takeLast(1))
        assertEquals(emptyList<String>(), audio.stops.filter { it != "card" && it != "replaced" })
    }

    @Test
    fun gradingTwiceStopsTheSoundOnlyOnce() {
        val model = started()
        model.speakWord()
        val first = model.state.value.card!!.wordId

        model.gotIt()
        waitFor(model) { it.card?.wordId != first }
        val after = audio.stops.size
        model.undo()
        waitFor(model) { it.card?.wordId == first }

        assertEquals(after, audio.stops.size)
    }

    @Test
    fun aWordPressStopsTheSentenceStillPlaying() {
        val model = started()
        model.speakSentence()
        audio.gate = CompletableDeferred()

        model.speakWord()

        assertEquals(listOf("replaced"), audio.ownStops)
        audio.gate!!.complete(Unit)
    }

    @Test
    fun leavingStopsEverySound_evenOneReviewDidNotStart() {
        val model = started()

        model.stopAudio("background")

        assertEquals(listOf("background"), audio.stops)
        assertEquals(emptyList<String>(), audio.ownStops)
    }

    @Test
    fun theButtonsFollowTheSettingsLive() {
        val model = started()
        assertTrue(model.state.value.speakers)

        settings.setSpeakers(false)
        settings.setShowSentence(false)

        assertEquals(false, model.state.value.speakers)
        assertEquals(false, model.state.value.showSentence)
    }

    @Test
    fun aSilentPhoneSkipsAutoPlay_butNotTheButtons() {
        autoPlay(ReviewAutoPlay.ON_SHOW)
        ringerNormal = false
        val model = started()
        assertEquals(emptyList<String>(), playedAfterAWhile())

        model.speakWord()

        assertEquals(listOf("word:ねこ"), played(1))
    }

    @Test
    fun autoPlayNeedsTheSpeakers() {
        autoPlay(ReviewAutoPlay.ON_SHOW)
        settings.setSpeakers(false)
        val model = started()

        model.reveal()

        assertEquals(emptyList<String>(), playedAfterAWhile())
    }

    @Test
    fun aFailedAutoPlayOnlyGoesToTheLog() {
        autoPlay(ReviewAutoPlay.ON_SHOW)
        audio.problem = VoiceProblem.NO_ENGINE
        val model = started()

        assertEquals(listOf("word:ねこ"), played(1))
        assertEquals(null, runBlocking { withTimeoutOrNull(300) { model.voiceProblems.first() } })
    }
}
