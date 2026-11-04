package app.honmani.reader.readaloud

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AudiobookPhase {
    /** No audiobook: no bar. */
    OFF,

    /** Reading the files, matching the subtitles and opening the audio. */
    LOADING,
    PLAYING,
    PAUSED,

    /** The audio played to its end. */
    ENDED,

    /** See [AudiobookState.problem]. */
    PROBLEM,
}

data class AudiobookState(
    val phase: AudiobookPhase = AudiobookPhase.OFF,
    val rate: Float = 1f,
    val problem: AudiobookProblem? = null,
    /** Subtitles found in the book, and all of them (0 before they are matched). */
    val matched: Int = 0,
    val total: Int = 0,
) {
    /** The audio plays: the screen stays on and the button offers Pause. */
    val running: Boolean get() = phase == AudiobookPhase.PLAYING

    /** Fewer than half of the subtitles were found. */
    val doubtful: Boolean get() = total > 0 && matched * 2 < total
}

/**
 * The audiobook read-along, the app's half: plays the audio ([AudiobookPlayer]) and, as it goes,
 * tells the page which passage of the book is being heard (`audiobook.js`), using the subtitles
 * matched to the book ([AudiobookAlignment]). Everything runs on the main thread; [tick] is called
 * about five times a second while the audio plays. Nothing here knows Android, so a fake player and
 * a fake page test it.
 *
 * A subtitle that was not found in the book changes nothing: the mark stays where it was until
 * the next subtitle that was found.
 */
class AudiobookController(
    private val player: AudiobookPlayer,
    private val toPage: (String) -> Unit,
    initialRate: Float = 1f,
    private val saveRate: (Float) -> Unit = {},
    /** Where the audio is, in milliseconds, to remember (when it pauses, ends or closes, and now and then). */
    private val savePosition: (Long) -> Unit = {},
    /** The audio is playing: counts as reading activity. */
    private val onPlaying: () -> Unit = {},
    /** The page can't reach this passage by turning pages: the app opens the book there. */
    private val navigate: (CueSpan) -> Unit = {},
    private val log: (String) -> Unit = {},
) : AudiobookPlayer.Listener {
    private val _state = MutableStateFlow(AudiobookState(rate = initialRate))
    val state: StateFlow<AudiobookState> = _state.asStateFlow()

    private var alignment: AudiobookAlignment? = null
    private var gen = 0
    private var openToken = 0
    private var resumeAfterPanel = false

    /** The cue the page was last told about, and the generation of that message. */
    private var marked = -1
    private var markGen = -1
    private var navigatedFor = -1
    private var waitingForPage = false
    private var lastSaveMs = 0L
    private var ticks = 0

    init {
        player.listener = this
    }

    private val phase get() = _state.value.phase

    // Starting.

    /** The files are being read: the bar appears. */
    fun loading() {
        close()
        _state.value = _state.value.copy(phase = AudiobookPhase.LOADING, problem = null, matched = 0, total = 0)
    }

    /** The files could not be used. */
    fun fail(problem: AudiobookProblem) {
        openToken++
        player.pause()
        player.release()
        _state.value = _state.value.copy(phase = AudiobookPhase.PROBLEM, problem = problem)
    }

    /** The subtitles are matched: opens [source] and plays from [resumeMs]. */
    fun ready(alignment: AudiobookAlignment, source: String, resumeMs: Long) {
        if (phase != AudiobookPhase.LOADING) return
        this.alignment = alignment
        _state.value = _state.value.copy(matched = alignment.matched, total = alignment.total)
        val token = ++openToken
        player.open(source) { problem ->
            if (token != openToken || phase != AudiobookPhase.LOADING) return@open
            if (problem != null) {
                fail(problem)
                return@open
            }
            player.setRate(_state.value.rate)
            player.seekTo(resumeMs.coerceAtLeast(0))
            _state.value = _state.value.copy(phase = AudiobookPhase.PAUSED)
            play()
        }
    }

    // The reader's controls.

    fun play() {
        if (phase != AudiobookPhase.PAUSED && phase != AudiobookPhase.ENDED) return
        resumeAfterPanel = false
        if (phase == AudiobookPhase.ENDED) player.seekTo(0)
        if (!player.play()) {
            _state.value = _state.value.copy(phase = AudiobookPhase.PAUSED)
            return
        }
        _state.value = _state.value.copy(phase = AudiobookPhase.PLAYING)
        marked = -1
        lastSaveMs = player.positionMs
        onPlaying()
        tick()
    }

    fun pause() {
        if (phase != AudiobookPhase.PLAYING) return
        player.pause()
        _state.value = _state.value.copy(phase = AudiobookPhase.PAUSED)
        savePosition(player.positionMs)
        log("paused at ${player.positionMs}")
    }

    fun togglePlay() {
        if (phase == AudiobookPhase.PLAYING) {
            resumeAfterPanel = false
            pause()
        } else {
            play()
        }
    }

    fun next() {
        val a = alignment ?: return
        if (phase != AudiobookPhase.PLAYING && phase != AudiobookPhase.PAUSED) return
        resumeAfterPanel = false
        val target = a.nextMatched(a.cueAt(player.positionMs))
        if (target >= 0) goTo(target)
    }

    fun previous() {
        val a = alignment ?: return
        if (phase != AudiobookPhase.PLAYING && phase != AudiobookPhase.PAUSED) return
        resumeAfterPanel = false
        val position = player.positionMs
        val here = a.cueAt(position)
        val restart = here >= 0 && a.cues[here].span != null && position - a.cues[here].startMs > RESTART_AFTER_MS
        val target = if (restart) here else a.previousMatched(here)
        if (target >= 0) goTo(target)
    }

    private fun goTo(index: Int) {
        val a = alignment ?: return
        player.seekTo(a.cues[index].startMs)
        marked = -1
        navigatedFor = -1
        show(index)
        if (phase == AudiobookPhase.PAUSED) savePosition(a.cues[index].startMs)
    }

    fun setRate(rate: Float) {
        _state.value = _state.value.copy(rate = rate)
        saveRate(rate)
        player.setRate(rate)
    }

    /** Stops, remembers where the audio was and lets the player go: the bar goes away. */
    fun close() {
        openToken++
        resumeAfterPanel = false
        waitingForPage = false
        val had = phase
        if (had == AudiobookPhase.PLAYING || had == AudiobookPhase.PAUSED) savePosition(player.positionMs)
        player.pause()
        player.release()
        if (had != AudiobookPhase.OFF) toPage("""{"type":"audiobook.clear"}""")
        alignment = null
        marked = -1
        navigatedFor = -1
        _state.value = AudiobookState(rate = _state.value.rate)
    }

    // The reader's surroundings.

    /** The lookup panel opened or closed: opening pauses, closing continues what it paused. */
    fun panelChanged(open: Boolean) {
        if (open) {
            if (phase == AudiobookPhase.PLAYING) {
                resumeAfterPanel = true
                pause()
            }
        } else if (resumeAfterPanel) {
            resumeAfterPanel = false
            if (phase == AudiobookPhase.PAUSED) play()
        }
    }

    /** The app left the screen: the audiobook is foreground only. */
    fun appStopped() {
        resumeAfterPanel = false
        pause()
    }

    /**
     * The book was laid out on screen again (opened at a far passage, or a font change): the
     * mark is gone and is drawn again. After a jump the audio kept playing; after a font change
     * (nothing was jumping) it waits for Play.
     */
    fun pageReloaded() {
        if (phase == AudiobookPhase.OFF) return
        marked = -1
        if (waitingForPage) {
            waitingForPage = false
            if (phase == AudiobookPhase.PLAYING) tick()
        } else if (phase == AudiobookPhase.PLAYING) {
            resumeAfterPanel = false
            pause()
        }
    }

    // The page's answer.

    /** The page could not bring passage [gen] on screen by turning pages. */
    fun onLost(gen: Int) {
        if (gen != markGen || marked < 0) return
        val a = alignment ?: return
        val span = a.cues[marked].span ?: return
        if (navigatedFor == marked) return
        navigatedFor = marked
        waitingForPage = true
        log("passage $marked is out of reach, opening the book there")
        navigate(span)
    }

    // The player.

    /** Called about five times a second while the audio plays. */
    fun tick() {
        val a = alignment ?: return
        if (phase != AudiobookPhase.PLAYING) return
        onPlaying()
        val position = player.positionMs
        val index = a.cueAt(position)
        if (index >= 0 && index != marked) {
            // Between found subtitles nothing changes; with no mark yet (just started, or the page was
            // laid out again) the last found one before is shown.
            val target = if (a.cues[index].span != null) index else if (marked < 0) a.previousMatched(index) else -1
            if (target >= 0) show(target)
        }
        if (++ticks % SAVE_EVERY_TICKS == 0 && position != lastSaveMs) {
            lastSaveMs = position
            savePosition(position)
        }
    }

    private fun show(index: Int) {
        val span = alignment?.cues?.get(index)?.span ?: return
        marked = index
        markGen = ++gen
        toPage("""{"type":"audiobook.mark","gen":$markGen,"start":${span.start},"end":${span.end}}""")
    }

    override fun onCompleted() {
        if (phase != AudiobookPhase.PLAYING) return
        savePosition(0)
        _state.value = _state.value.copy(phase = AudiobookPhase.ENDED)
    }

    override fun onError() {
        if (phase == AudiobookPhase.OFF || phase == AudiobookPhase.PROBLEM) return
        savePosition(player.positionMs)
        fail(AudiobookProblem.UNSUPPORTED)
    }

    override fun onFocusLost() {
        resumeAfterPanel = false
        pause()
    }

    companion object {
        /** "Previous" restarts the subtitle being heard when it is further along than this. */
        const val RESTART_AFTER_MS = 1_500L
        private const val SAVE_EVERY_TICKS = 25
    }
}
