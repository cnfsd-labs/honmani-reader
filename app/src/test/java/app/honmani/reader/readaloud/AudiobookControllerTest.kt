package app.honmani.reader.readaloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakePlayer : AudiobookPlayer {
    override var listener: AudiobookPlayer.Listener? = null
    var openResult: AudiobookProblem? = null
    var pendingOpen: ((AudiobookProblem?) -> Unit)? = null
    var deferOpen = false
    var opened: String? = null
    override var positionMs = 0L
    var playing = false
    var focusGranted = true
    var speed = 1f
    var released = 0

    override fun open(source: String, onResult: (AudiobookProblem?) -> Unit) {
        opened = source
        if (deferOpen) pendingOpen = onResult else onResult(openResult)
    }

    override fun seekTo(ms: Long) {
        positionMs = ms
    }

    override fun play(): Boolean {
        if (!focusGranted) return false
        playing = true
        return true
    }

    override fun pause() {
        playing = false
    }

    override fun setRate(rate: Float) {
        speed = rate
    }

    override fun release() {
        playing = false
        released++
    }
}

class AudiobookControllerTest {
    private val player = FakePlayer()
    private val page = ArrayList<String>()
    private val saved = ArrayList<Long>()
    private val rates = ArrayList<Float>()
    private val navigated = ArrayList<CueSpan>()
    private var activity = 0
    private val controller = AudiobookController(
        player = player,
        toPage = { page += it },
        saveRate = { rates += it },
        savePosition = { saved += it },
        onPlaying = { activity++ },
        navigate = { navigated += it },
    )

    // Cue 0 0-1000 found at 0..5, cue 1 1000-2000 not found, cue 2 2000-3000 found at 5..9, cue 3 3000-4000 found at 9..12.
    private val alignment = AudiobookAlignment(
        listOf(
            AlignedCue(0, 1000, CueSpan(0, 5)),
            AlignedCue(1000, 2000, null),
            AlignedCue(2000, 3000, CueSpan(5, 9)),
            AlignedCue(3000, 4000, CueSpan(9, 12)),
        ),
    )

    private fun start(resumeMs: Long = 0) {
        controller.loading()
        controller.ready(alignment, "content://audio", resumeMs)
    }

    private fun marks() = page.filter { it.contains("audiobook.mark") }

    private fun mark(start: Int, end: Int, gen: Int) = """{"type":"audiobook.mark","gen":$gen,"start":$start,"end":$end}"""

    @Test
    fun startsPlayingFromTheRememberedPosition() {
        start(resumeMs = 2_500)
        assertEquals(AudiobookPhase.PLAYING, controller.state.value.phase)
        assertEquals("content://audio", player.opened)
        assertTrue(player.playing)
        assertEquals(listOf(mark(5, 9, 1)), marks())
        assertEquals(3, controller.state.value.matched.let { it })
        assertEquals(4, controller.state.value.total)
    }

    @Test
    fun marksEachFoundCueOnceAndLeavesTheMarkOverAnUnfoundOne() {
        start()
        player.positionMs = 500
        controller.tick()
        controller.tick()
        player.positionMs = 1_500 // cue 1, not found: stays
        controller.tick()
        assertEquals(1, marks().size)
        player.positionMs = 2_100
        controller.tick()
        assertEquals(listOf(mark(0, 5, 1), mark(5, 9, 2)), marks())
    }

    @Test
    fun startingInAnUnfoundCueShowsTheFoundOneBefore() {
        start(resumeMs = 1_500)
        assertEquals(listOf(mark(0, 5, 1)), marks())
    }

    @Test
    fun nextAndPreviousSkipCuesNotFound() {
        start()
        controller.next()
        assertEquals(2_000, player.positionMs)
        controller.next()
        assertEquals(3_000, player.positionMs)
        controller.next()
        assertEquals(3_000, player.positionMs) // the last one
        player.positionMs = 3_200 // just after the start
        controller.previous()
        assertEquals(2_000, player.positionMs) // restarts nothing: 200 ms in, so the one before
        player.positionMs = 4_800
        controller.previous()
        assertEquals(3_000, player.positionMs) // far into cue 3: restarts it
        assertEquals(listOf(mark(0, 5, 1), mark(5, 9, 2), mark(9, 12, 3), mark(5, 9, 4), mark(9, 12, 5)), marks())
    }

    @Test
    fun pauseRemembersThePositionAndPlayContinues() {
        start()
        player.positionMs = 2_345
        controller.pause()
        assertEquals(AudiobookPhase.PAUSED, controller.state.value.phase)
        assertFalse(player.playing)
        assertEquals(listOf(2_345L), saved)
        controller.togglePlay()
        assertEquals(AudiobookPhase.PLAYING, controller.state.value.phase)
        assertEquals(2_345, player.positionMs)
    }

    @Test
    fun theLookupPanelPausesAndClosingItContinues() {
        start()
        controller.panelChanged(true)
        assertEquals(AudiobookPhase.PAUSED, controller.state.value.phase)
        controller.panelChanged(false)
        assertEquals(AudiobookPhase.PLAYING, controller.state.value.phase)
        // A pause by the reader is not undone by closing the panel.
        controller.togglePlay()
        controller.panelChanged(true)
        controller.panelChanged(false)
        assertEquals(AudiobookPhase.PAUSED, controller.state.value.phase)
    }

    @Test
    fun leavingTheAppPausesAndAnotherAppTakingTheAudioToo() {
        start()
        controller.appStopped()
        assertEquals(AudiobookPhase.PAUSED, controller.state.value.phase)
        controller.play()
        player.listener!!.onFocusLost()
        assertEquals(AudiobookPhase.PAUSED, controller.state.value.phase)
    }

    @Test
    fun refusedFocusLeavesItPaused() {
        player.focusGranted = false
        start()
        assertEquals(AudiobookPhase.PAUSED, controller.state.value.phase)
    }

    @Test
    fun theEndOfTheAudioSaysSoAndPlayStartsAgain() {
        start()
        player.positionMs = 3_990
        player.listener!!.onCompleted()
        assertEquals(AudiobookPhase.ENDED, controller.state.value.phase)
        assertEquals(listOf(0L), saved)
        controller.play()
        assertEquals(AudiobookPhase.PLAYING, controller.state.value.phase)
        assertEquals(0, player.positionMs)
    }

    @Test
    fun previousAfterTheEndGoesToTheLastSubtitleReadyToPlay() {
        start()
        player.positionMs = 3_990
        player.listener!!.onCompleted()
        controller.previous()
        assertEquals(AudiobookPhase.PAUSED, controller.state.value.phase)
        assertEquals(3_000, player.positionMs)
        assertEquals(3_000L, saved.last())
        controller.play()
        assertEquals(AudiobookPhase.PLAYING, controller.state.value.phase)
        assertEquals(3_000, player.positionMs)
    }

    @Test
    fun audioThatCannotBeOpenedIsAProblem() {
        player.openResult = AudiobookProblem.AUDIO_GONE
        start()
        assertEquals(AudiobookPhase.PROBLEM, controller.state.value.phase)
        assertEquals(AudiobookProblem.AUDIO_GONE, controller.state.value.problem)
        assertTrue(marks().isEmpty())
    }

    @Test
    fun aPlayerErrorWhilePlayingIsAProblemAndKeepsThePosition() {
        start()
        player.positionMs = 1_234
        player.listener!!.onError()
        assertEquals(AudiobookProblem.UNSUPPORTED, controller.state.value.problem)
        assertEquals(listOf(1_234L), saved)
    }

    @Test
    fun closingWhileTheAudioOpensDropsItsAnswer() {
        player.deferOpen = true
        start()
        controller.close()
        player.pendingOpen!!(null)
        assertEquals(AudiobookPhase.OFF, controller.state.value.phase)
        assertFalse(player.playing)
    }

    @Test
    fun closeSavesThePositionClearsThePageAndKeepsTheSpeed() {
        start()
        controller.setRate(1.5f)
        assertEquals(1.5f, player.speed)
        assertEquals(listOf(1.5f), rates)
        player.positionMs = 2_222
        controller.close()
        assertEquals(AudiobookPhase.OFF, controller.state.value.phase)
        assertEquals(1.5f, controller.state.value.rate)
        assertEquals(listOf(2_222L), saved)
        assertEquals("""{"type":"audiobook.clear"}""", page.last())
        assertFalse(player.playing)
    }

    @Test
    fun aPassageOutOfReachOpensTheBookThereOncePerPassage() {
        start(resumeMs = 2_500)
        controller.onLost(1)
        assertEquals(listOf(CueSpan(5, 9)), navigated)
        controller.onLost(1)
        assertEquals(1, navigated.size)
        // The answer for an older mark is not ours.
        player.positionMs = 3_100
        controller.tick()
        controller.onLost(1)
        assertEquals(1, navigated.size)
        // After the page came back the audio goes on and the mark is drawn again.
        controller.pageReloaded()
        assertEquals(AudiobookPhase.PLAYING, controller.state.value.phase)
        assertEquals(mark(9, 12, 3), marks().last())
    }

    @Test
    fun aReloadOfThePageWithoutAJumpPauses() {
        start()
        controller.pageReloaded()
        assertEquals(AudiobookPhase.PAUSED, controller.state.value.phase)
        controller.play()
        assertEquals(mark(0, 5, 2), marks().last())
    }

    @Test
    fun listeningCountsAsReading() {
        start()
        val before = activity
        controller.tick()
        controller.tick()
        assertEquals(before + 2, activity)
    }

    @Test
    fun theMatchedCountAndTheDoubt() {
        assertFalse(alignment.doubtful)
        val bad = AudiobookAlignment(List(10) { AlignedCue(it * 1000L, it * 1000L + 900, if (it < 4) CueSpan(it, it + 1) else null) })
        assertTrue(bad.doubtful)
        assertEquals(4, bad.matched)
    }

    @Test
    fun theAlignmentSurvivesTheCacheFile() {
        val back = AudiobookAlignment.parse(alignment.serialize())!!
        assertEquals(alignment.cues, back.cues)
        assertEquals(null, AudiobookAlignment.parse("junk"))
        assertEquals(null, AudiobookAlignment.parse("honmani-audiobook-alignment 1\n1 2 x y\n"))
    }

    @Test
    fun aPageTurnByHandDetaches_followMarksTheHeardPassageAgainAndAttaches() {
        start()
        player.positionMs = 2100
        controller.tick()
        controller.viewDetached(true)
        assertTrue(controller.state.value.detached)
        // The audio goes on and the mark moves on, the controller still just tells the page.
        player.positionMs = 3100
        controller.tick()
        assertEquals(""""start":9""", Regex(""""start":\d+""").find(marks().last())!!.value)
        page.clear()
        controller.follow()
        assertFalse(controller.state.value.detached)
        assertEquals(2, page.size)
        assertTrue(page[0].contains("follow.attach"))
        assertTrue(page[1].contains(""""start":9,"end":12"""))
        assertTrue(controller.state.value.running)
    }

    @Test
    fun detachedIsNotKept_onCloseRestartOrReloadedPage() {
        start()
        controller.viewDetached(true)
        controller.pause()
        assertTrue(controller.state.value.detached)
        controller.close()
        assertFalse(controller.state.value.detached)
        controller.viewDetached(true)
        assertFalse(controller.state.value.detached)
        start()
        assertFalse(controller.state.value.detached)
        controller.viewDetached(true)
        controller.pageReloaded()
        assertFalse(controller.state.value.detached)
        controller.viewDetached(true)
        start()
        assertFalse(controller.state.value.detached)
        page.clear()
        controller.follow()
        assertTrue(page.isEmpty())
    }
}
