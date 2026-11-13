package app.honmani.reader.readaloud

import app.honmani.reader.anki.VoiceProblem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeVoice : ReadAloudVoice {
    override var listener: ReadAloudVoice.Listener? = null
    override var maxInputLength = 4000
    var problem: VoiceProblem? = null
    var connects = 0
    var shutdowns = 0
    var pendingConnect: ((VoiceProblem?) -> Unit)? = null
    var autoConnect = true
    var currentRate = 1f
    val spoken = mutableListOf<Pair<String, String>>()
    var stops = 0

    override fun connect(onResult: (VoiceProblem?) -> Unit) {
        connects++
        if (autoConnect) onResult(problem) else pendingConnect = onResult
    }

    override fun setRate(rate: Float) {
        currentRate = rate
    }

    override fun speak(utteranceId: String, text: String) {
        spoken.add(utteranceId to text)
    }

    override fun stop() {
        stops++
        spoken.clear()
    }

    override fun shutdown() {
        shutdowns++
    }
}

class ReadAloudControllerTest {
    private val voice = FakeVoice()
    private val sent = mutableListOf<String>()
    private val savedRates = mutableListOf<Float>()
    private var spokenCount = 0
    private val controller = ReadAloudController(
        voice = voice,
        toPage = { sent.add(it) },
        saveRate = { savedRates.add(it) },
        onSpoken = { spokenCount++ },
    )

    private val gen get() = Regex(""""gen":(\d+)""").find(sent.last { "gen" in it })!!.groupValues[1].toInt()
    private fun types() = sent.map { Regex(""""type":"([^"]+)"""").find(it)!!.groupValues[1] }
    private fun sentences(vararg range: Int) = range.map { SpokenSentence(it, "文$it。") }
    private fun id(sentence: Int, gen: Int = this.gen, offset: Int = 0, last: Int = 1) = "ra:$gen:$sentence:$offset:$last"
    private fun startAndReceive(vararg range: Int = intArrayOf(0, 1, 2), end: String? = null) {
        controller.start()
        controller.onSentences(gen, sentences(*range), end)
    }

    @Test
    fun startAsksThePageAndSpeaksWhatComesBack() {
        controller.start()
        assertEquals(ReadAloudPhase.STARTING, controller.state.value.phase)
        assertEquals(listOf("readAloud.begin"), types())
        controller.onSentences(gen, sentences(0, 1, 2), null)
        assertEquals(listOf("文0。", "文1。", "文2。"), voice.spoken.map { it.second })
        controller.onStart(id(0))
        assertEquals(ReadAloudPhase.SPEAKING, controller.state.value.phase)
        assertTrue(controller.state.value.running)
        assertEquals("readAloud.mark", types().last())
        assertEquals(1, spokenCount)
    }

    @Test
    fun aPronunciationIsSpokenInsteadOfTheText_andTheMarkStaysOnTheBooksWord() {
        val rules = PronunciationRules(listOf("雨宮" to "あめみや"))
        val replacing = ReadAloudController(voice = voice, toPage = { sent.add(it) }, pronunciations = { rules })
        replacing.start()
        replacing.onSentences(gen, listOf(SpokenSentence(0, "雨宮は笑った。")), null)
        assertEquals(listOf("あめみやは笑った。"), voice.spoken.map { it.second })
        replacing.onStart(id(0))
        replacing.onRange(id(0), 4)
        // The voice is at は (spoken 4), which is character 2 of the page's sentence.
        assertEquals(""""offset":2""", Regex(""""offset":\d+""").find(sent.last())!!.value)
    }

    @Test
    fun asksForMoreAsTheQueueRunsLow() {
        startAndReceive()
        controller.onStart(id(0))
        controller.onDone(id(0))
        controller.onStart(id(1))
        assertEquals("readAloud.more", types().last())
    }

    @Test
    fun movingOnFromTheEndOfASectionAdvances() {
        startAndReceive(0, 1, end = "section")
        controller.onStart(id(0))
        controller.onDone(id(0))
        controller.onStart(id(1))
        assertFalse("advance" in types().joinToString())
        controller.onDone(id(1))
        assertEquals("readAloud.advance", types().last())
        controller.onSentences(gen, sentences(2, 3), null)
        assertEquals(listOf("文2。", "文3。"), voice.spoken.takeLast(2).map { it.second })
    }

    @Test
    fun theEndOfTheBookEndsReading() {
        startAndReceive(0, end = "book")
        controller.onStart(id(0))
        controller.onDone(id(0))
        assertEquals(ReadAloudPhase.ENDED, controller.state.value.phase)
        assertFalse(controller.state.value.running)
    }

    @Test
    fun pauseStopsAndPlaySeeksToTheSentenceStart() {
        startAndReceive()
        controller.onStart(id(1))
        val before = gen
        controller.togglePlay()
        assertEquals(ReadAloudPhase.PAUSED, controller.state.value.phase)
        assertTrue(voice.spoken.isEmpty())
        // What the old run still reports is ignored.
        controller.onStart(id(2, gen = before))
        controller.onDone(id(1, gen = before))
        assertEquals(ReadAloudPhase.PAUSED, controller.state.value.phase)
        controller.togglePlay()
        assertEquals("readAloud.seek", types().last())
        assertTrue(sent.last().contains(""""index":1"""))
        assertTrue(gen > before)
    }

    @Test
    fun aLostCursorStartsAgainFromThePage() {
        startAndReceive()
        controller.onStart(id(1))
        controller.pause()
        controller.start()
        controller.onLost(gen)
        assertEquals("readAloud.begin", types().last())
    }

    @Test
    fun nextAndPreviousMoveBySentenceAndSpeakEvenWhenPaused() {
        startAndReceive()
        controller.onStart(id(1))
        controller.next()
        assertEquals("readAloud.seek", types().last())
        assertTrue(sent.last().contains(""""index":2"""))
        controller.onSentences(gen, sentences(2, 3, 4), null)
        controller.onStart(id(2))
        controller.pause()
        controller.previous()
        assertTrue(sent.last().contains(""""index":1"""))
        assertEquals(ReadAloudPhase.STARTING, controller.state.value.phase)
    }

    @Test
    fun nextAtTheEndOfASectionSkipsTheRestAndAdvances() {
        startAndReceive(0, 1, end = "section")
        controller.onStart(id(1))
        controller.next()
        assertEquals("readAloud.advance", types().last())
    }

    @Test
    fun nextWhilePausedOnTheLastSentenceOfASectionAdvancesAndSpeaks() {
        startAndReceive(0, 1, end = "section")
        controller.onStart(id(1))
        controller.pause()
        controller.next()
        assertEquals("readAloud.advance", types().last())
        assertEquals(ReadAloudPhase.STARTING, controller.state.value.phase)
        controller.onSentences(gen, sentences(2, 3), null)
        assertEquals(listOf("文2。", "文3。"), voice.spoken.map { it.second })
    }

    @Test
    fun nextWhilePausedOnTheLastSentenceReceivedAsksForMore() {
        startAndReceive(0, 1)
        controller.onStart(id(1))
        controller.pause()
        controller.next()
        assertEquals("readAloud.more", types().last())
        controller.onSentences(gen, sentences(2), null)
        assertEquals(listOf("文2。"), voice.spoken.map { it.second })
    }

    @Test
    fun theLookupPanelPausesAndClosingItResumes() {
        startAndReceive()
        controller.onStart(id(1))
        controller.panelChanged(true)
        assertEquals(ReadAloudPhase.PAUSED, controller.state.value.phase)
        controller.panelChanged(false)
        assertEquals("readAloud.seek", types().last())
        assertTrue(controller.state.value.running)
    }

    @Test
    fun aPanelOpenedWhilePausedStaysPausedWhenClosed() {
        startAndReceive()
        controller.onStart(id(1))
        controller.pause()
        controller.panelChanged(true)
        controller.panelChanged(false)
        assertEquals(ReadAloudPhase.PAUSED, controller.state.value.phase)
    }

    @Test
    fun pausingByHandDuringThePanelDoesNotResume() {
        startAndReceive()
        controller.onStart(id(1))
        controller.panelChanged(true)
        controller.start()
        controller.pause()
        controller.panelChanged(false)
        assertEquals(ReadAloudPhase.PAUSED, controller.state.value.phase)
    }

    @Test
    fun losingAudioFocusOrLeavingTheAppPauses() {
        startAndReceive()
        controller.onStart(id(0))
        controller.onFocusLost()
        assertEquals(ReadAloudPhase.PAUSED, controller.state.value.phase)
        controller.start()
        controller.appStopped()
        assertEquals(ReadAloudPhase.PAUSED, controller.state.value.phase)
    }

    @Test
    fun threeErrorsInARowAreAProblem() {
        startAndReceive()
        controller.onError(id(0))
        controller.onError(id(1))
        assertEquals(ReadAloudPhase.STARTING, controller.state.value.phase)
        controller.onError(id(2))
        assertEquals(ReadAloudPhase.PROBLEM, controller.state.value.phase)
        assertEquals(VoiceProblem.FAILED, controller.state.value.problem)
    }

    @Test
    fun noJapaneseVoiceIsAProblemAndTheEngineLetsGo() {
        voice.problem = VoiceProblem.NO_JAPANESE
        controller.start()
        assertEquals(ReadAloudPhase.PROBLEM, controller.state.value.phase)
        assertEquals(VoiceProblem.NO_JAPANESE, controller.state.value.problem)
        assertEquals(1, voice.shutdowns)
        assertEquals(emptyList<String>(), types())
        voice.problem = null
        controller.start()
        assertEquals(listOf("readAloud.begin"), types())
    }

    @Test
    fun pausingWhileConnectingStartsNothing() {
        voice.autoConnect = false
        controller.start()
        controller.pause()
        voice.pendingConnect!!(null)
        assertEquals(emptyList<String>(), types())
        assertEquals(ReadAloudPhase.PAUSED, controller.state.value.phase)
    }

    @Test
    fun playingAgainWhileConnectingWaitsForTheSameEngine() {
        voice.autoConnect = false
        controller.start()
        controller.pause()
        controller.start()
        assertEquals(1, voice.connects)
        voice.pendingConnect!!(null)
        assertEquals(listOf("readAloud.begin"), types())
    }

    @Test
    fun closingWhileConnectingLetsTheNextPlayConnectAgain() {
        voice.autoConnect = false
        controller.start()
        controller.close()
        controller.start()
        assertEquals(2, voice.connects)
    }

    @Test
    fun changingTheSpeedIsSavedAndRestartsTheSentence() {
        startAndReceive()
        controller.onStart(id(1))
        controller.setRate(1.5f)
        assertEquals(listOf(1.5f), savedRates)
        assertEquals(1.5f, voice.currentRate)
        assertEquals(1.5f, controller.state.value.rate)
        assertEquals("readAloud.seek", types().last())
        assertTrue(sent.last().contains(""""index":1"""))
    }

    @Test
    fun closingStopsEverythingAndKeepsTheSpeed() {
        startAndReceive()
        controller.setRate(0.75f)
        controller.close()
        assertEquals(ReadAloudPhase.OFF, controller.state.value.phase)
        assertEquals(0.75f, controller.state.value.rate)
        assertEquals("readAloud.stop", types().last())
        assertEquals(1, voice.shutdowns)
        assertTrue(voice.spoken.isEmpty())
        // Closing again says nothing more to the page.
        val count = sent.size
        controller.close()
        assertEquals(count, sent.size)
    }

    @Test
    fun answersFromAnOlderRunAreIgnored() {
        controller.start()
        val old = gen
        controller.pause()
        controller.start()
        controller.onSentences(old, sentences(0, 1), null)
        assertTrue(voice.spoken.isEmpty())
    }

    @Test
    fun aLongSentenceIsSplitIntoPiecesOfOneSentence() {
        voice.maxInputLength = 10
        controller.start()
        controller.onSentences(gen, listOf(SpokenSentence(0, "あいうえお、かきくけこさしすせそ")), null)
        assertEquals(listOf("ra:$gen:0:0:0", "ra:$gen:0:6:1"), voice.spoken.map { it.first })
        // Only the last piece finishes the sentence.
        controller.onStart(id(0, offset = 0, last = 0))
        val asked = sent.size
        controller.onDone(id(0, offset = 0, last = 0))
        assertEquals(asked, sent.size)

    }

    @Test
    fun rangesMoveTheMarkWithinTheSentence() {
        startAndReceive()
        controller.onStart(id(0))
        val before = sent.size
        controller.onRange(id(0), 0)
        assertEquals(before, sent.size)
        controller.onRange(id(0), 3)
        assertTrue(sent.last().contains(""""offset":3"""))
    }

    @Test
    fun emptySentencesAreSkippedAndNeverEndlessly() {
        controller.start()
        controller.onSentences(gen, listOf(SpokenSentence(0, "  ")), null)
        assertTrue(voice.spoken.isEmpty())
        repeat(60) { controller.onSentences(gen, emptyList(), null) }
        assertEquals(ReadAloudPhase.ENDED, controller.state.value.phase)
    }

    @Test
    fun aPageTurnByHandDetaches_followSendsTheViewBackToTheSpokenSentenceAndAttaches() {
        startAndReceive()
        controller.onStart(id(1))
        controller.viewDetached(true)
        assertTrue(controller.state.value.detached)
        // Speech goes on and the mark moves; the controller keeps telling the page where it is.
        controller.onStart(id(2))
        assertTrue(controller.state.value.running)
        assertEquals(""""index":2""", Regex(""""index":\d+""").find(sent.last())!!.value)
        sent.clear()
        controller.follow()
        assertFalse(controller.state.value.detached)
        assertEquals(listOf("follow.attach", "readAloud.mark"), types())
        assertEquals(""""index":2""", Regex(""""index":\d+""").find(sent.last())!!.value)
    }

    @Test
    fun followWhilePausedSendsTheRunThePageKnows_elseThePageDropsTheMarkAsStale() {
        startAndReceive()
        val pageGen = gen
        controller.onStart(id(1))
        controller.viewDetached(true)
        controller.pause()
        sent.clear()
        controller.follow()
        assertEquals(listOf("follow.attach", "readAloud.mark"), types())
        assertEquals(pageGen, gen)
        assertEquals(ReadAloudPhase.PAUSED, controller.state.value.phase)
    }

    @Test
    fun followOnlyActsWhileDetached_andTheDetachedStateIsNotKept() {
        startAndReceive()
        controller.onStart(id(0))
        sent.clear()
        controller.follow()
        assertTrue(sent.isEmpty())
        controller.viewDetached(true)
        controller.pause()
        assertTrue(controller.state.value.detached)
        controller.close()
        assertFalse(controller.state.value.detached)
        // The page's message after closing changes nothing.
        controller.viewDetached(true)
        assertFalse(controller.state.value.detached)
        // A new start is attached, as is a page that was loaded again.
        startAndReceive()
        controller.onStart(id(0))
        controller.viewDetached(true)
        controller.pageReloaded()
        assertFalse(controller.state.value.detached)
        // Pause and Play stay detached: it is the same listening.
        controller.viewDetached(true)
        controller.start()
        assertTrue(controller.state.value.detached)
    }
}
