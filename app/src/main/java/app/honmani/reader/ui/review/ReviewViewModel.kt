package app.honmani.reader.ui.review

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.honmani.reader.HonmaniApplication
import app.honmani.reader.R
import app.honmani.reader.data.review.Grade
import app.honmani.reader.data.review.ReviewFilter
import app.honmani.reader.data.review.ReviewRepository
import app.honmani.reader.data.review.ReviewSentence
import app.honmani.reader.data.review.ReviewSentences
import app.honmani.reader.data.review.ReviewSession
import app.honmani.reader.data.review.ReviewState
import app.honmani.reader.data.settings.ReviewSettings
import app.honmani.reader.data.words.WordRepository
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    val sentence: ReviewSentence?,
    val isNew: Boolean,
)

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
)

/** A line for the snackbar; [undoable] adds the Undo button. */
class ReviewNotice(@StringRes val message: Int, val word: String, val undoable: Boolean = true)

/** One review session: the queue from [ReviewSession], the card on screen, and what a tap does. */
class ReviewViewModel(
    private val reviews: ReviewRepository,
    private val words: WordRepository,
    private val settings: ReviewSettings,
    /** The words the session takes (the Review card's chip); null: due cards, then new ones. */
    private val filter: suspend () -> ReviewFilter? = { null },
) : ViewModel() {
    private val _state = MutableStateFlow(ReviewUiState(showSentence = settings.showSentence.value))
    val state: StateFlow<ReviewUiState> = _state.asStateFlow()

    private val _notices = Channel<ReviewNotice>(Channel.BUFFERED)
    val notices: Flow<ReviewNotice> = _notices.receiveAsFlow()

    // The session isn't thread safe: one action at a time.
    private val lock = Mutex()
    private var session: ReviewSession? = null

    /** Builds a fresh queue; the screen calls it each time it opens. */
    fun start() {
        viewModelScope.launch {
            lock.withLock {
                _state.value = ReviewUiState(showSentence = settings.showSentence.value)
                val next = ReviewSession(
                    reviews, words, settings.newPerDay.value, settings.knownAfterDays.value, settings.reviewsPerDay.value,
                )
                next.start(filter())
                session = next
                publish(next)
            }
        }
    }

    fun reveal() = _state.update { if (it.card != null) it.copy(revealed = true) else it }

    fun answer(grade: Grade) = act { it.answer(grade) }

    fun forgot() = answer(Grade.AGAIN)

    fun gotIt() = answer(Grade.GOOD)

    fun bury() = act(R.string.review_buried) { it.bury() }

    fun suspend() = act(R.string.review_suspended) { it.suspend() }

    fun markKnown() = act(R.string.review_known) { it.markKnown() }

    fun reviewNow() = act(R.string.review_asked_again) { it.reviewNow() }

    fun remove() = act(R.string.word_deleted) { it.removeWord() }

    fun editMeaning(gloss: String) = act(keepRevealed = true) { it.editMeaning(gloss) }

    fun editNote(note: String) = act(keepRevealed = true) { it.editNote(note) }

    fun undo() = act { it.undo() }

    /** The book of the current card's sentence, if it has one. */
    val currentBookId: Long? get() = _state.value.card?.sentence?.bookId

    private fun act(notice: Int? = null, keepRevealed: Boolean = false, action: suspend (ReviewSession) -> Any?) {
        viewModelScope.launch {
            lock.withLock {
                val active = session ?: return@withLock
                val word = active.current?.word?.expression.orEmpty()
                action(active)
                publish(active, keepRevealed)
                if (notice != null && word.isNotEmpty()) _notices.send(ReviewNotice(notice, word))
            }
        }
    }

    private suspend fun publish(active: ReviewSession, keepRevealed: Boolean = false) {
        val item = active.current
        val card = item?.let {
            val lookups = words.latestLookups(it.word.id)
            ReviewCardUi(
                wordId = it.word.id,
                expression = it.word.expression,
                reading = it.word.reading,
                gloss = it.word.gloss,
                note = it.word.note,
                sentence = ReviewSentences.pick(it.word, lookups, it.card.reps),
                isNew = it.card.state == ReviewState.NEW,
            )
        }
        _state.update {
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
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val container = (this[APPLICATION_KEY] as HonmaniApplication).container
                ReviewViewModel(container.reviewRepository, container.wordRepository, container.reviewSettings) {
                    container.reviewFilter(container.reviewScope.value)
                }
            }
        }
    }
}
