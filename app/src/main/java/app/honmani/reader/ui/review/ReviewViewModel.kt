package app.honmani.reader.ui.review

import android.util.Log
import android.media.AudioManager
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.honmani.reader.HonmaniApplication
import app.honmani.reader.R
import app.honmani.reader.anki.VoiceProblem
import app.honmani.reader.data.review.Grade
import app.honmani.reader.data.review.ReviewFilter
import app.honmani.reader.data.review.ReviewRepository
import app.honmani.reader.data.review.ReviewSentence
import app.honmani.reader.data.review.ReviewSentences
import app.honmani.reader.data.review.ReviewSession
import app.honmani.reader.data.review.ReviewScope
import app.honmani.reader.data.review.ReviewState
import app.honmani.reader.data.settings.ReviewAutoPlay
import app.honmani.reader.data.settings.ReviewAutoPlayContent
import app.honmani.reader.data.settings.ReviewSettings
import app.honmani.reader.data.words.WordRepository
import app.honmani.reader.lookup.FuriganaOverride
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The card on screen: the word, what the back shows, and the sentence for the front. */
data class ReviewCardUi(
    val wordId: Long,
    val expression: String,
    val reading: String,
    val gloss: String,
    /** The reader's note, shown on the back only; empty if none. */
    val note: String = "",
    /** The furigana correction (`WordEntity.furigana`); empty if none. */
    val furigana: String = "",
    val sentence: ReviewSentence?,
    val isNew: Boolean,
) {
    /** The reading to show: the one the furigana correction spells, else the word's own. */
    val shownReading: String
        get() = FuriganaOverride.parse(furigana, expression)?.let(FuriganaOverride::reading) ?: reading
}

data class ReviewUiState(
    val loading: Boolean = true,
    val card: ReviewCardUi? = null,
    val revealed: Boolean = false,
    val remaining: Int = 0,
    val canUndo: Boolean = false,
    val forgotten: Int = 0,
    val remembered: Int = 0,
    /** Settings: the front shows the sentence (when the word has one) rather than the word alone. */
    val showSentence: Boolean = true,
    /** Settings: the card shows the speaker buttons. */
    val speakers: Boolean = true,
)

/** An answer the screen should let the reader feel (and, if switched on, hear); [finished] when it was the session's last card. */
data class ReviewCue(val gotIt: Boolean, val finished: Boolean)

/** A line for the snackbar; [undoable] adds the Undo button. */
class ReviewNotice(@StringRes val message: Int, val word: String, val undoable: Boolean = true)

/** No sound (the default where there is no player). */
object NoReviewAudio : ReviewAudio {
    override suspend fun word(text: String, phoneOnly: Boolean): VoiceProblem? = null

    override suspend fun sentence(text: String, bookId: Long?, bookTitle: String?): VoiceProblem? = null

    override fun stop(reason: String) = Unit

    override fun stopOwn(reason: String) = Unit
}

/** One review session: the queue from [ReviewSession], the card on screen, and what a tap does. */
class ReviewViewModel(
    private val reviews: ReviewRepository,
    private val words: WordRepository,
    private val settings: ReviewSettings,
    /** The words the session takes (the Review card's chip); null: due cards, then new ones. */
    private val filter: suspend () -> ReviewFilter? = { null },
    /** False for the Reviews only chip: the session holds due cards and no new ones. */
    private val withNew: () -> Boolean = { true },
    /** What the speaker buttons play. */
    private val audio: ReviewAudio = NoReviewAudio,
    /** True while the phone's ringer is on: auto-play stays silent on a phone set to vibrate or silent. */
    private val ringerNormal: () -> Boolean = { true },
) : ViewModel() {
    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<ReviewUiState> = _state.asStateFlow()

    private val _notices = Channel<ReviewNotice>(Channel.BUFFERED)
    val notices: Flow<ReviewNotice> = _notices.receiveAsFlow()

    private val _voiceProblems = Channel<VoiceProblem>(Channel.BUFFERED)

    /** Why a speaker button press made no sound (the screen says it). */
    val voiceProblems: Flow<VoiceProblem> = _voiceProblems.receiveAsFlow()

    private val _cues = Channel<ReviewCue>(Channel.BUFFERED)
    val cues: Flow<ReviewCue> = _cues.receiveAsFlow()

    // The session isn't thread safe: one action at a time.
    private val lock = Mutex()
    private var session: ReviewSession? = null

    init {
        // The buttons follow the settings at once, so coming back from Settings agrees with the auto-play gate.
        viewModelScope.launch {
            combine(settings.showSentence, settings.speakers) { sentence, speakers -> sentence to speakers }
                .collect { (sentence, speakers) -> _state.update { it.copy(showSentence = sentence, speakers = speakers) } }
        }
    }

    private fun initialState() =
        ReviewUiState(showSentence = settings.showSentence.value, speakers = settings.speakers.value)

    /** Builds a fresh queue; the screen calls it each time it opens. */
    fun start() {
        viewModelScope.launch {
            lock.withLock {
                _state.value = initialState()
                val next = ReviewSession(
                    reviews, words, settings.newPerDay.value, settings.knownAfterDays.value, settings.reviewsPerDay.value,
                )
                next.start(filter(), withNew())
                session = next
                publish(next, restart = true)
            }
        }
    }

    fun reveal() {
        if (_state.value.card == null) return
        _state.update { it.copy(revealed = true) }
        autoPlay(AutoTrigger.REVEAL, _state.value)
    }

    fun answer(grade: Grade) = act(answered = true, gotIt = grade != Grade.AGAIN) { it.answer(grade) }

    fun forgot() = answer(Grade.AGAIN)

    fun gotIt() = answer(Grade.GOOD)

    fun bury() = act(R.string.review_buried) { it.bury() }

    fun suspend() = act(R.string.review_suspended) { it.suspend() }

    fun markKnown() = act(R.string.review_known) { it.markKnown() }

    fun reviewNow() = act(R.string.review_asked_again) { it.reviewNow() }

    fun remove() = act(R.string.word_deleted) { it.removeWord() }

    fun ignore() = act(R.string.word_ignored) { it.ignoreWord() }

    fun editMeaning(gloss: String) = act(keepRevealed = true) { it.editMeaning(gloss) }

    fun editNote(note: String) = act(keepRevealed = true) { it.editNote(note) }

    fun editFurigana(furigana: String) = act(keepRevealed = true) { it.editFurigana(furigana) }

    fun undo() = act { it.undo() }

    // One job at a time: a recording that is still being made never plays over the next card.
    private var audioJob: Job? = null

    /** The word's speaker button: says the reading as corrected by the reader's furigana. */
    fun speakWord() {
        val card = _state.value.card ?: return
        val text = card.shownReading.ifBlank { card.expression }
        playing { audio.word(text) }
    }

    /** The sentence's speaker button (only shown when the card shows a sentence). */
    fun speakSentence() {
        val state = _state.value
        val sentence = state.card?.sentence?.takeIf { state.showSentence } ?: return
        playing { audio.sentence(sentence.text, sentence.bookId, sentence.bookTitle) }
    }

    private fun playing(play: suspend () -> VoiceProblem?) {
        beginSound()
        audioJob = viewModelScope.launch { play()?.let { _voiceProblems.send(it) } }
    }

    // Whether review has started a sound since it last stopped one. The player is shared with the lookup panel, so
    // review stops it only for a sound of its own, never one the panel started.
    private var reviewSound = false

    /** A new review sound replaces the one review made before, even while that is still being recorded. */
    private fun beginSound() {
        stopOwnAudio("replaced")
        reviewSound = true
    }

    /** Ends review's own sound, if any; the panel's sound is left alone. */
    private fun stopOwnAudio(reason: String) {
        audioJob?.cancel()
        audioJob = null
        if (reviewSound) audio.stopOwn(reason)
        reviewSound = false
    }

    private enum class AutoTrigger { SHOW, REVEAL }

    // What auto-play has already said, per card and trigger. It outlives start(), which the screen calls again after a
    // rotation, so turning the phone or editing a card never says it twice.
    private val autoPlayed = mutableSetOf<Pair<Long, AutoTrigger>>()
    private var shownWordId: Long? = null

    /** Says the card by itself when the setting asks for [trigger]: once per card and trigger, the phone's voice only. */
    private fun autoPlay(trigger: AutoTrigger, state: ReviewUiState) {
        val card = state.card ?: return
        val wanted = when (settings.autoPlay.value) {
            ReviewAutoPlay.OFF -> return
            ReviewAutoPlay.ON_REVEAL -> AutoTrigger.REVEAL
            ReviewAutoPlay.ON_SHOW -> AutoTrigger.SHOW
        }
        if (wanted != trigger || !settings.speakers.value) return
        if (!autoPlayed.add(card.wordId to trigger)) return
        if (!ringerNormal()) {
            Log.d(AUDIO_TAG, "auto skipped ringer")
            return
        }
        val sentence = card.sentence?.takeIf { state.showSentence }
        val text = card.shownReading.ifBlank { card.expression }
        // A card with no sentence on show still has its word to say.
        val sayWord = settings.autoPlayContent.value == ReviewAutoPlayContent.WORD || sentence == null
        beginSound()
        // A failed auto-play only goes to the log; the reader didn't ask for it.
        audioJob = viewModelScope.launch {
            val problem = if (sayWord) audio.word(text, phoneOnly = true) else audio.sentence(sentence!!.text, sentence.bookId, sentence.bookTitle)
            problem?.let { Log.w(AUDIO_TAG, "auto-play failed: $it") }
        }
    }

    /** Stops every sound and any recording on its way: the app went to the background or the screen closed. */
    fun stopAudio(reason: String) {
        audioJob?.cancel()
        audioJob = null
        reviewSound = false
        audio.stop(reason)
    }

    override fun onCleared() = stopAudio("cleared")

    /** The book of the current card's sentence, if it has one. */
    val currentBookId: Long? get() = _state.value.card?.sentence?.bookId

    private fun act(notice: Int? = null, keepRevealed: Boolean = false, answered: Boolean = false, gotIt: Boolean = true, action: suspend (ReviewSession) -> Any?) {
        viewModelScope.launch {
            lock.withLock {
                val active = session ?: return@withLock
                val word = active.current?.word?.expression.orEmpty()
                action(active)
                publish(active, keepRevealed)
                if (answered) _cues.send(ReviewCue(gotIt, finished = active.current == null))
                if (notice != null && word.isNotEmpty()) _notices.send(ReviewNotice(notice, word))
            }
        }
    }

    private suspend fun publish(active: ReviewSession, keepRevealed: Boolean = false, restart: Boolean = false) {
        // An edit keeps the card (and its sound); an answer or an undo shows another front.
        // The same card again after an edit or a restart of the screen is not a new showing.
        val sameCard = active.current?.word?.id == shownWordId && (keepRevealed || restart)
        if (!keepRevealed && !sameCard) stopOwnAudio("card")
        val item = active.current
        val card = item?.let {
            val lookups = words.latestLookups(it.word.id)
            ReviewCardUi(
                wordId = it.word.id,
                expression = it.word.expression,
                reading = it.word.reading,
                gloss = it.word.gloss,
                note = it.word.note,
                furigana = it.word.furigana,
                sentence = ReviewSentences.pick(it.word, lookups, it.card.reps),
                isNew = it.card.state == ReviewState.NEW,
            )
        }
        if (!sameCard) autoPlayed.clear()
        shownWordId = card?.wordId
        val shown = _state.value.let {
            it.copy(
                loading = false,
                card = card,
                // The front of a card: a new one, or the same one after an undo.
                revealed = keepRevealed && it.revealed,
                remaining = active.remaining,
                canUndo = active.canUndo,
                forgotten = active.forgotten,
                remembered = active.remembered,
            )
        }
        // The sound is asked for before the card shows, so whoever sees the card knows the sound is on its way.
        if (!keepRevealed) autoPlay(AutoTrigger.SHOW, shown)
        _state.value = shown
    }

    companion object {
        private const val AUDIO_TAG = "ReviewAudio"

        val Factory = viewModelFactory {
            initializer {
                val container = (this[APPLICATION_KEY] as HonmaniApplication).container
                ReviewViewModel(
                    container.reviewRepository,
                    container.wordRepository,
                    container.reviewSettings,
                    filter = { container.reviewFilter(container.reviewScope.value) },
                    withNew = { container.reviewScope.value != ReviewScope.DueOnly },
                    audio = WordAudioReview(container.wordAudio) { id, title, text -> container.sentenceClips.clip(id, title, text) },
                    ringerNormal = {
                        val manager = (this[APPLICATION_KEY] as HonmaniApplication).getSystemService(AudioManager::class.java)
                        manager == null || manager.ringerMode == AudioManager.RINGER_MODE_NORMAL
                    },
                )
            }
        }
    }
}
