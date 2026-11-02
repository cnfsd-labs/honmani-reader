package app.honmani.reader.ui.progress

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.honmani.reader.HonmaniApplication
import app.honmani.reader.data.difficulty.StoredDifficulty
import app.honmani.reader.data.library.BookEntity
import app.honmani.reader.data.review.ReviewSummary
import app.honmani.reader.data.stats.DayTotal
import app.honmani.reader.data.stats.Milestone
import app.honmani.reader.data.stats.MilestoneStore
import app.honmani.reader.data.stats.Progression
import app.honmani.reader.data.stats.ProgressionData
import app.honmani.reader.data.stats.ReadingDayEntity
import app.honmani.reader.data.stats.ReadingLevel
import app.honmani.reader.data.stats.ReadingStats
import app.honmani.reader.data.stats.Skill
import app.honmani.reader.data.stats.SkillEstimate
import app.honmani.reader.data.stats.SkillTracker
import app.honmani.reader.data.stats.StatsPeriod
import app.honmani.reader.data.words.WordKnowledge
import app.honmani.reader.data.words.WordProgress
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn

/** Everything the progress screen shows. */
data class ProgressUiState(
    val level: ReadingLevel,
    /** Days in a row with reading, and days read in the last 7. */
    val streak: Int,
    val daysThisWeek: Int,
    /** Every milestone, in [Progression.TIERS] order. */
    val milestones: List<Milestone>,
    val next: List<Milestone>,
    val anyReading: Boolean,
    val skill: SkillEstimate = SkillEstimate(null, emptyList(), 0),
    /** Unfinished books that fit the reader's skill, best first. */
    val fitting: List<FittingBook> = emptyList(),
    /** Seconds read per day, all books together, for the year calendar. */
    val calendar: Map<LocalDate, Long> = emptyMap(),
    val today: LocalDate = LocalDate.now(),
    val reviews: ReviewSummary = ReviewSummary(),
)

private data class Library(
    val books: List<BookEntity>,
    val grades: Map<String, StoredDifficulty>,
    val skill: SkillEstimate,
    val reviews: ReviewSummary,
)

data class FittingBook(val id: Long, val title: String, val difficulty: Int)

class ProgressViewModel(
    private val days: Flow<List<ReadingDayEntity>>,
    private val books: Flow<List<BookEntity>>,
    private val wordsLearned: Flow<Int>,
    private val ankiCards: Flow<Int>,
    private val store: MilestoneStore,
    /** Book grades by content hash. */
    private val grades: Flow<Map<String, StoredDifficulty>> = flowOf(emptyMap()),
    private val today: () -> LocalDate = LocalDate::now,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    /** The skill; by default from speed alone (no words read). */
    private val skill: Flow<SkillEstimate> = SkillTracker(days, books, grades, flowOf(WordKnowledge.EMPTY), today = today).estimate,
    private val reviews: Flow<ReviewSummary> = flowOf(ReviewSummary()),
) : ViewModel() {
    private val library = combine(books, grades, skill, reviews, ::Library)

    val uiState: StateFlow<ProgressUiState?> =
        combine(days, library, wordsLearned, ankiCards, store.seen) { rows, (books, grades, skill, reviews), learned, anki, seen ->
            val today = today()
            val zone = zone()
            val data = ProgressionData(
                days = rows.map { DayTotal(LocalDate.parse(it.day), it.seconds, it.characters) },
                finished = books.filter { it.finishedAt != null || it.progress >= 1f }.map { book ->
                    book.finishedAt?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
                },
                wordsLearned = learned,
                ankiCards = anki,
            )
            val milestones = Progression.milestones(data, seen, today)
            val week = ReadingStats.period(rows, today, StatsPeriod.WEEK)
            val scores = books.mapNotNull { book ->
                grades[book.contentHash]?.difficulty?.score?.takeIf { it > 0 }?.let { book to it }
            }
            val unfinished = scores.filter { (book, _) -> book.finishedAt == null && book.progress < 1f && !book.hidden }
            ProgressUiState(
                level = Progression.level(rows.sumOf { it.characters }),
                streak = week.streak,
                daysThisWeek = week.daysRead,
                milestones = milestones,
                next = Progression.next(milestones),
                anyReading = week.anyReading,
                skill = skill,
                reviews = reviews,
                calendar = rows.groupBy { LocalDate.parse(it.day) }.mapValues { (_, list) -> list.sumOf { it.seconds } },
                today = today,
                fitting = skill.current?.let { current ->
                    val titles = unfinished.associate { (book, score) -> book.id to (book.title to score) }
                    Skill.fitting(unfinished.map { (book, score) -> book.id to score }, current)
                        .map { id -> titles.getValue(id).let { (title, score) -> FittingBook(id, title, score) } }
                }.orEmpty(),
            )
        }
            .onEach { state -> store.record(state.milestones.filter { it.reached }.map { it.id }, today()) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Milestones reached since the library last showed them, in [Progression.TIERS] order. */
    val pending: StateFlow<List<Milestone>> =
        combine(uiState, store.pending) { state, pending ->
            state?.milestones?.filter { it.id in pending }.orEmpty()
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun shown(milestones: List<Milestone>) = store.shown(milestones.map { it.id })

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val container = (this[APPLICATION_KEY] as HonmaniApplication).container
                ProgressViewModel(
                    days = container.readingDao.observeAll(),
                    books = container.bookDao.observeAll(),
                    // Looked-up words that became known, by reading or by hand.
                    wordsLearned = container.wordKnowledge.map { knowledge ->
                        knowledge.progress.values.count { it.steps >= WordProgress.KNOWN }
                    },
                    ankiCards = container.wordRepository.ankiCards,
                    store = container.milestoneStore,
                    grades = container.difficultyStore.grades,
                    skill = container.skillTracker.estimate,
                    reviews = container.reviewRepository.observeSummary(),
                )
            }
        }
    }
}
