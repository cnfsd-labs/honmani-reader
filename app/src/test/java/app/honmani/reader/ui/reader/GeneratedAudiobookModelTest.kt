package app.honmani.reader.ui.reader

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.honmani.reader.anki.CloudVoiceInfo
import app.honmani.reader.anki.VoiceException
import app.honmani.reader.anki.VoiceProblem
import app.honmani.reader.readaloud.AudiobookStore
import app.honmani.reader.readaloud.CueSpan
import app.honmani.reader.readaloud.generate.AudiobookMaker
import app.honmani.reader.readaloud.generate.FakeAudiobookScheduler
import app.honmani.reader.readaloud.generate.MakerStatus
import app.honmani.reader.readaloud.generate.PlannedSentence
import app.honmani.reader.readaloud.generate.SpeechPlan
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class GeneratedAudiobookModelTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val scheduler = FakeAudiobookScheduler()
    private var key: String? = "key"
    private var voicesCalled = 0
    private var voicesFailure: VoiceException? = null
    private var free = Long.MAX_VALUE
    private var voices = listOf(
        CloudVoiceInfo("ja-JP-Chirp3-HD-Aoede", "FEMALE"),
        CloudVoiceInfo("ja-JP-Neural2-B", "FEMALE"),
        CloudVoiceInfo("ja-JP-Chirp-HD-F", "FEMALE"),
    )

    /** 100,000 spoken characters over 100 sentences: $3 with the default Chirp 3 voice. */
    private val plan = SpeechPlan(List(100) { i -> PlannedSentence("あ".repeat(1_000), "原文$i", CueSpan(i * 10, i * 10 + 9)) })

    private val maker by lazy {
        val store = AudiobookStore(
            ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("model-test", Context.MODE_PRIVATE),
            File(folder.root, "audiobook"),
        )
        AudiobookMaker(File(folder.root, "generated"), store, Dispatchers.Unconfined, { }, { })
    }

    private fun model(epub: Boolean = true) = GeneratedAudiobookModel(
        bookHash = "book",
        epub = epub,
        key = { key },
        loadVoices = { voicesCalled++; voicesFailure?.let { throw it }; voices },
        readPlan = { plan },
        maker = maker,
        scheduler = scheduler,
        freeBytes = { free },
        savedVoice = { null },
        scope = CoroutineScope(Dispatchers.Unconfined),
        io = Dispatchers.Unconfined,
    )

    @Test
    fun withoutAKeyNothingIsAskedOrCounted() {
        key = null
        val model = model()
        assertEquals(MakeBlock.NO_KEY, model.block())
        model.open()
        assertEquals(0, voicesCalled)
        assertFalse(model.state.value.loading)
        assertFalse(model.start())
        assertTrue(scheduler.starts.isEmpty())
    }

    @Test
    fun aTextBookIsEpubOnly() {
        val model = model(epub = false)
        assertEquals(MakeBlock.EPUB_ONLY, model.block())
        model.open()
        assertEquals(0, voicesCalled)
    }

    @Test
    fun theSheetCountsAndOffersTheDefaultVoiceWithACapOfTheEstimatePlusTenPercent() {
        val model = model()
        assertNull(model.block())
        model.open()
        val state = model.state.value
        assertEquals(100_000L, state.chars)
        assertEquals("ja-JP-Chirp3-HD-Aoede", state.voice)
        assertEquals(3.0, state.estimateUsd!!, 1e-9)
        assertEquals("3.30", state.capText)
        assertTrue(state.canStart)
    }

    @Test
    fun anEstimateAboveTheCapKeepsStartOff() {
        val model = model()
        model.open()
        model.typeCap("2.50")
        assertTrue(model.state.value.overCap)
        assertFalse(model.state.value.canStart)
        assertFalse(model.start())
        // A cheaper voice brings the estimate under the cap: 100,000 characters at $16 per million is $1.60.
        model.chooseVoice("ja-JP-Neural2-B")
        assertEquals("2.50", model.state.value.capText)
        assertTrue(model.state.value.canStart)
    }

    @Test
    fun aVoiceWithoutAPriceNeedsOneTypedBeforeStart() {
        val model = model()
        model.open()
        model.chooseVoice("ja-JP-Chirp-HD-F")
        assertTrue(model.state.value.needsPrice)
        assertNull(model.state.value.estimateUsd)
        assertFalse(model.state.value.canStart)
        model.typePrice("20,5")
        assertEquals(2.05, model.state.value.estimateUsd!!, 1e-9)
        assertEquals("2.26", model.state.value.capText)
        assertTrue(model.state.value.canStart)
        model.typePrice("0")
        assertFalse(model.state.value.canStart)
    }

    @Test
    fun tooLittleFreeSpaceKeepsStartOff() {
        free = 1_000_000
        val model = model()
        model.open()
        assertTrue(model.state.value.spaceShort)
        assertFalse(model.state.value.canStart)
    }

    @Test
    fun aRejectedKeyIsShownAndNothingIsStarted() {
        voicesFailure = VoiceException(VoiceProblem.KEY_REJECTED)
        val model = model()
        model.open()
        assertEquals(VoiceProblem.KEY_REJECTED, model.state.value.problem)
        assertFalse(model.state.value.canStart)
        assertFalse(model.start())
        assertTrue(scheduler.starts.isEmpty())
    }

    @Test
    fun startFreezesThePlanThenStartsOnceHoweverOftenItIsPressed() {
        val model = model()
        model.open()
        model.setMobileData(true)
        assertTrue(model.start())
        assertFalse(model.start())
        assertFalse(model.start())
        assertEquals(1, scheduler.starts.size)
        val start = scheduler.starts.single()
        assertEquals(FakeAudiobookScheduler.Start("book", "ja-JP-Chirp3-HD-Aoede", 3.3, true), start)
        // The plan was frozen before the job was queued, with the price, the cap and the choice.
        val frozen = maker.files("book").readPlan()!!
        assertEquals(30.0, frozen.pricePerMillionUsd, 0.0)
        assertEquals(3.3, frozen.capUsd, 1e-9)
        assertTrue(frozen.mobileData)
        assertEquals(100, frozen.sentences.size)
        assertEquals(MakerStatus.Partial(0, 100, 0.0, 3.3), maker.status("book"))
    }

    @Test
    fun theCapToOfferAfterACapStopProjectsTheWholeBook() {
        // 40 of 100 sentences cost $2 of a $2 cap: the whole book is projected at $5, plus 10%.
        assertEquals("5.50", GeneratedManage.raisedCapText(MakerStatus.Partial(40, 100, 2.0, 2.0)))
        // Never less than one and a half times the old cap.
        assertEquals("3.00", GeneratedManage.raisedCapText(MakerStatus.Partial(90, 100, 1.0, 2.0)))
        assertEquals("3.00", GeneratedManage.raisedCapText(MakerStatus.Partial(0, 100, 0.0, 2.0)))
    }
}
