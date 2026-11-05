package app.honmani.reader.readaloud

import app.honmani.reader.anki.VoiceProblem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ReadAloudPhase {
    /** Not reading aloud: no bar. */
    OFF,

    /** Connecting, or waiting for the page's first sentences. */
    STARTING,
    SPEAKING,
    PAUSED,

    /** The book's last sentence was spoken. */
    ENDED,

    /** No engine, no Japanese voice, or the engine keeps failing: see [ReadAloudState.problem]. */
    PROBLEM,
}

data class ReadAloudState(
    val phase: ReadAloudPhase = ReadAloudPhase.OFF,
    val rate: Float = 1f,
    val problem: VoiceProblem? = null,
    /** The reader took control (turned a page by hand): the mark moves on, the view stays; see [ReadAloudController.follow]. */
    val detached: Boolean = false,
) {
    /** Speech is running or about to: the screen stays on and the button offers Pause. */
    val running: Boolean get() = phase == ReadAloudPhase.STARTING || phase == ReadAloudPhase.SPEAKING
}

/** What the page sends back (`assets/honmani/read-aloud.js`). */
data class SpokenSentence(val index: Int, val text: String)

/**
 * Read aloud, the app's half: decides what is spoken and when, with the sentences the page cuts
 * (`read-aloud.js`) and a [ReadAloudVoice]. Everything runs on the main thread. [toPage] sends
 * the page a JSON message. Nothing here knows Android, so a fake voice and a fake page test it.
 *
 * The page numbers the sentences. They are queued a few ahead on the voice (QUEUE_ADD) under
 * utterance ids `ra:<gen>:<sentence>:<character offset of the piece>:<1 if the last piece>`; `gen`
 * grows with every restart, so what an older run still reports is ignored. There is no real pause
 * in a speech engine: pause stops, and resume speaks again from the start of the sentence that
 * was being read.
 */
class ReadAloudController(
    private val voice: ReadAloudVoice,
    private val toPage: (String) -> Unit,
    initialRate: Float = 1f,
    private val saveRate: (Float) -> Unit = {},
    /** A sentence began: counts as reading activity. */
    private val onSpoken: () -> Unit = {},
    private val log: (String) -> Unit = {},
    /** The book's "read this as that" list, asked for each sentence so a change applies at once. */
    private val pronunciations: () -> PronunciationRules = { PronunciationRules.EMPTY },
) : ReadAloudVoice.Listener {
    private val _state = MutableStateFlow(ReadAloudState(rate = initialRate))
    val state: StateFlow<ReadAloudState> = _state.asStateFlow()

    private var gen = 0
    private var connected = false
    private var onConnected: (() -> Unit)? = null

    /** The sentence being spoken, or -1 before the first one starts. */
    private var current = -1
    private var lastReceived = -1
    private var finishedThrough = -1
    private var pageEnd: String? = null

    /** A request to the page is out and its answer not in. */
    private var awaiting = false
    private var errors = 0
    private var emptyAnswers = 0
    private var lastRange = -1
    private var resumeAfterPanel = false

    /** Where in the sentence (as the book has it) the page was last told the voice is. */
    private var markOffset = 0

    /** The run the page knows while paused: Pause moves [gen] on without telling the page. */
    private var pausedPageGen = 0

    /** The sentences spoken with a replacement in them: where the voice's characters came from. */
    private val spoken = HashMap<Int, SpokenText>()

    init {
        voice.listener = this
    }

    private val phase get() = _state.value.phase

    // The reader's controls.

    /** Starts at the first sentence that begins on the page on screen. */
    fun start() {
        if (phase == ReadAloudPhase.STARTING || phase == ReadAloudPhase.SPEAKING) return
        resumeAfterPanel = false
        if (phase == ReadAloudPhase.PAUSED && current >= 0) restartAt(current) else begin()
    }

    fun pause() {
        if (!state.value.running) return
        pausedPageGen = gen
        gen++
        voice.stop()
        awaiting = false
        onConnected = null
        _state.value = _state.value.copy(phase = ReadAloudPhase.PAUSED)
        log("paused at $current")
    }

    /** Play: from the start of the sentence that was being read (or the page, if none was). */
    fun resume() = start()

    fun togglePlay() {
        if (state.value.running) {
            resumeAfterPanel = false
            pause()
        } else {
            start()
        }
    }

    fun next() = jump(current + 1)

    fun previous() = jump(maxOf(current - 1, 0))

    private fun jump(target: Int) {
        if (phase != ReadAloudPhase.SPEAKING && phase != ReadAloudPhase.PAUSED) return
        resumeAfterPanel = false
        when {
            current < 0 -> return
            target <= lastReceived -> restartAt(target)
            state.value.running && pageEnd != null -> {
                // The last sentence the page sent: skip what is left of it and go on.
                gen++
                voice.stop()
                finishedThrough = lastReceived
                afterQueue()
            }
        }
    }

    /** The page says the reader turned a page or scrolled by hand ([detached]), or that it took the view along again. */
    fun viewDetached(detached: Boolean) {
        if (phase == ReadAloudPhase.OFF || _state.value.detached == detached) return
        _state.value = _state.value.copy(detached = detached)
        log(if (detached) "view detached" else "view attached")
    }

    /** Brings the view back to the sentence being read and follows it again. */
    fun follow() {
        if (!_state.value.detached) return
        _state.value = _state.value.copy(detached = false)
        toPage(FOLLOW_ATTACH)
        if (current >= 0) toPage(markMessage(current, markOffset, if (phase == ReadAloudPhase.PAUSED) pausedPageGen else gen))
        log("view attached again at $current")
    }

    fun setRate(rate: Float) {
        _state.value = _state.value.copy(rate = rate)
        saveRate(rate)
        voice.setRate(rate)
        if (state.value.running && current >= 0) restartAt(current)
    }

    /** Stops, forgets the book position and lets the engine go: the bar goes away. */
    fun close() {
        gen++
        onConnected = null
        awaiting = false
        resumeAfterPanel = false
        connected = false
        voice.stop()
        voice.shutdown()
        if (phase != ReadAloudPhase.OFF) toPage("""{"type":"readAloud.stop"}""")
        current = -1
        _state.value = ReadAloudState(rate = _state.value.rate)
    }

    // The reader's surroundings.

    /** The lookup panel opened or closed: opening pauses, closing continues what it paused. */
    fun panelChanged(open: Boolean) {
        if (open) {
            if (state.value.running) {
                resumeAfterPanel = true
                pause()
            }
        } else if (resumeAfterPanel) {
            resumeAfterPanel = false
            if (phase == ReadAloudPhase.PAUSED) start()
        }
    }

    /** The app left the screen: read aloud is foreground only. */
    fun appStopped() {
        resumeAfterPanel = false
        pause()
    }

    /** The page was loaded again (a font change): its cursor is gone, so wait for Play. */
    fun pageReloaded() {
        if (phase != ReadAloudPhase.OFF) {
            resumeAfterPanel = false
            _state.value = _state.value.copy(detached = false)
            pause()
        }
    }

    // The page's answers.

    fun onSentences(gen: Int, items: List<SpokenSentence>, end: String?) {
        if (gen != this.gen || !state.value.running) return
        awaiting = false
        var queued = 0
        for (item in items) {
            if (item.index <= lastReceived || item.text.isBlank()) continue
            lastReceived = item.index
            enqueue(item)
            queued++
        }
        if (end != null) pageEnd = end
        emptyAnswers = if (queued == 0) emptyAnswers + 1 else 0
        if (emptyAnswers > MAX_EMPTY_ANSWERS) {
            finish()
            return
        }
        afterQueue()
    }

    fun onLost(gen: Int) {
        if (gen != this.gen || !awaiting || !state.value.running) return
        log("lost: starting again from the page")
        begin()
    }

    // The voice's reports.

    override fun onStart(utteranceId: String) {
        val id = UtteranceId.parse(utteranceId) ?: return
        if (id.gen != gen) return
        current = id.sentence
        lastRange = -1
        if (phase == ReadAloudPhase.STARTING) _state.value = _state.value.copy(phase = ReadAloudPhase.SPEAKING)
        log("start ${id.sentence}+${id.offset}")
        if (id.offset == 0) onSpoken()
        spoken.keys.removeAll { it < id.sentence - 1 }
        markOffset = originalOffset(id.sentence, id.offset)
        toPage(markMessage(id.sentence, markOffset))
        if (!awaiting && pageEnd == null && lastReceived - id.sentence < 2) requestMore()
    }

    override fun onRange(utteranceId: String, start: Int) {
        val id = UtteranceId.parse(utteranceId) ?: return
        if (id.gen != gen || id.sentence != current) return
        val offset = id.offset + start
        if (offset == lastRange || start == 0) return
        lastRange = offset
        markOffset = originalOffset(id.sentence, offset)
        toPage(markMessage(id.sentence, markOffset))
    }

    override fun onDone(utteranceId: String) {
        val id = UtteranceId.parse(utteranceId) ?: return
        if (id.gen != gen) return
        errors = 0
        if (id.last) sentenceFinished(id.sentence)
    }

    override fun onError(utteranceId: String) {
        val id = UtteranceId.parse(utteranceId) ?: return
        if (id.gen != gen) return
        log("error in ${id.sentence}")
        if (++errors >= MAX_ERRORS) {
            fail(VoiceProblem.FAILED)
            return
        }
        if (id.last) sentenceFinished(id.sentence)
    }

    override fun onFocusLost() {
        resumeAfterPanel = false
        log("audio focus lost")
        pause()
    }

    // Inside.

    private fun sentenceFinished(sentence: Int) {
        finishedThrough = maxOf(finishedThrough, sentence)
        afterQueue()
    }

    /** What to do when the voice has spoken everything the page sent so far. */
    private fun afterQueue() {
        if (awaiting || finishedThrough < lastReceived || !state.value.running) return
        when (pageEnd) {
            "section" -> {
                pageEnd = null
                awaiting = true
                toPage("""{"type":"readAloud.advance","gen":$gen,"count":$LOOKAHEAD}""")
            }
            "book" -> finish()
            else -> requestMore()
        }
    }

    private fun requestMore() {
        awaiting = true
        toPage("""{"type":"readAloud.more","gen":$gen,"count":$LOOKAHEAD}""")
    }

    private fun finish() {
        log("end of the book")
        voice.stop()
        awaiting = false
        _state.value = _state.value.copy(phase = ReadAloudPhase.ENDED)
    }

    private fun fail(problem: VoiceProblem) {
        gen++
        voice.stop()
        awaiting = false
        onConnected = null
        if (problem == VoiceProblem.NO_ENGINE || problem == VoiceProblem.NO_JAPANESE) {
            voice.shutdown()
            connected = false
        }
        log("problem $problem")
        _state.value = _state.value.copy(phase = ReadAloudPhase.PROBLEM, problem = problem)
    }

    private fun enqueue(item: SpokenSentence) {
        val max = minOf(voice.maxInputLength, MAX_PIECE)
        var offset = 0
        val text = pronunciations().apply(item.text)
        if (text.changed) spoken[item.index] = text else spoken.remove(item.index)
        val pieces = splitForSpeech(text.text, max)
        for ((n, piece) in pieces.withIndex()) {
            val last = if (n == pieces.lastIndex) 1 else 0
            log("speak ${item.index}+$offset: $piece")
            voice.speak("ra:$gen:${item.index}:$offset:$last", piece)
            offset += piece.length
        }
    }

    private fun originalOffset(sentence: Int, offset: Int) = spoken[sentence]?.originalOffset(offset) ?: offset

    private fun resetRun(first: Int) {
        gen++
        spoken.clear()
        voice.stop()
        current = -1
        lastReceived = first - 1
        finishedThrough = first - 1
        pageEnd = null
        errors = 0
        emptyAnswers = 0
        lastRange = -1
        awaiting = true
        _state.value = _state.value.copy(phase = ReadAloudPhase.STARTING, problem = null)
    }

    /** Starts from the page on screen. */
    private fun begin() {
        resetRun(0)
        _state.value = _state.value.copy(detached = false)
        awaiting = false
        whenConnected {
            resetRun(0)
            toPage("""{"type":"readAloud.begin","gen":$gen,"count":$LOOKAHEAD}""")
        }
    }

    /** Speaks from the start of a sentence the page sent before. */
    private fun restartAt(index: Int) {
        resetRun(index)
        toPage("""{"type":"readAloud.seek","gen":$gen,"index":$index,"count":$LOOKAHEAD}""")
    }

    private fun whenConnected(action: () -> Unit) {
        if (connected) {
            action()
            return
        }
        onConnected = action
        voice.connect { problem ->
            val wanted = onConnected
            onConnected = null
            if (problem == null) {
                connected = true
                voice.setRate(_state.value.rate)
            }
            // Nothing wanted it any more (paused or closed while connecting).
            if (wanted == null) return@connect
            if (problem != null) fail(problem) else wanted()
        }
    }

    private fun markMessage(sentence: Int, offset: Int, pageGen: Int = gen) =
        """{"type":"readAloud.mark","gen":$pageGen,"index":$sentence,"offset":$offset}"""

    private data class UtteranceId(val gen: Int, val sentence: Int, val offset: Int, val last: Boolean) {
        companion object {
            fun parse(id: String): UtteranceId? {
                val parts = id.split(':')
                if (parts.size != 5 || parts[0] != "ra") return null
                return UtteranceId(
                    parts[1].toIntOrNull() ?: return null,
                    parts[2].toIntOrNull() ?: return null,
                    parts[3].toIntOrNull() ?: return null,
                    parts[4] == "1",
                )
            }
        }
    }

    companion object {
        /** Sentences asked of the page at a time; the engine always has one or two queued ahead. */
        const val LOOKAHEAD = 3
        private const val FOLLOW_ATTACH = """{"type":"follow.attach"}"""
        private const val MAX_PIECE = 4_000
        private const val MAX_ERRORS = 3
        private const val MAX_EMPTY_ANSWERS = 50
    }
}
