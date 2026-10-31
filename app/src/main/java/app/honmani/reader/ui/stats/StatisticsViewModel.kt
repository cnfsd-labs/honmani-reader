package app.honmani.reader.ui.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.honmani.reader.HonmaniApplication
import app.honmani.reader.data.difficulty.BookDifficulty
import app.honmani.reader.data.difficulty.GradingState
import app.honmani.reader.data.difficulty.StoredDifficulty
import app.honmani.reader.data.library.BookEntity
import app.honmani.reader.data.stats.BookReading
import app.honmani.reader.data.stats.ReadingDayEntity
import app.honmani.reader.data.stats.GoalProgress
import app.honmani.reader.data.stats.ReadingGoal
import app.honmani.reader.data.stats.ReadingStats
import app.honmani.reader.data.stats.PeriodSummary
import app.honmani.reader.data.stats.StatsPeriod
import app.honmani.reader.data.words.BookCoverage
import app.honmani.reader.data.words.UnknownWords
import app.honmani.reader.data.words.WordCounts
import app.honmani.reader.data.export.CsvExports
import app.honmani.reader.data.export.writeTextDocument
import app.honmani.reader.data.stats.DayTotal
import app.honmani.reader.diagnostics.logWarning
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Everything the statistics screen shows, for the period picked. */
data class StatisticsUiState(
    val summary: PeriodSummary,
    /** Books finished in the period. */
    val booksFinished: Int = 0,
    /** Lookups in the period. */
    val lookups: Int = 0,
    val words: WordCounts = WordCounts(),
    /** Titles of the books in [PeriodSummary.books], by book id. */
    val titles: Map<Long, String> = emptyMap(),
    /** Today against the daily reading goal; null while the goal is off. */
    val goal: GoalProgress? = null,
)

/** One book's statistics, for its sheet in the library. */
data class BookStatistics(
    val reading: BookReading,
    val lookups: Int,
    /** Characters read up to the reading position, and in the whole book if it can be told. */
    val position: Long,
    val bookCharacters: Long?,
    val progress: Float,
    val finishedAt: Long?,
    /** How hard the book is; null until it has been graded (or if it can't be). */
    val difficulty: BookDifficulty? = null,
    /** Whether grading is running or can't run, for when [difficulty] is null. */
    val grading: GradingState = GradingState.IDLE,
    /** The reader's speed (characters per hour), recent if there is enough, else overall. */
    val readerSpeed: Long? = null,
    /** How much of the book's words the reader knows; null until graded and the vocabulary is estimated. */
    val coverage: BookCoverage? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
class StatisticsViewModel(
    private val days: Flow<List<ReadingDayEntity>>,
    private val bookDays: (bookId: Long) -> Flow<List<ReadingDayEntity>>,
    private val books: Flow<List<BookEntity>>,
    /** Lookups since a time (epoch milliseconds; null: ever). */
    private val lookups: (from: Long?) -> Flow<Int>,
    private val bookLookups: (bookId: Long) -> Flow<Int>,
    private val words: Flow<WordCounts>,
    /** Book grades by content hash. */
    private val grades: Flow<Map<String, StoredDifficulty>> = flowOf(emptyMap()),
    private val grading: Flow<GradingState> = flowOf(GradingState.IDLE),
    /** How much of a book (by content hash) the reader knows. */
    private val coverage: (contentHash: String) -> Flow<BookCoverage?> = { flowOf(null) },
    /** The words of a book (by content hash) the reader does not know yet. */
    private val unknownWords: (contentHash: String) -> Flow<UnknownWords?> = { flowOf(null) },
    /** The daily reading goal in minutes; null while it is off. */
    private val goalMinutes: Flow<Int?> = flowOf(null),
    private val today: () -> LocalDate = LocalDate::now,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    /** Writes text to a document the reader picked (a content URI). */
    private val writeDocument: suspend (uri: String, text: String) -> Unit = { _, _ -> },
) : ViewModel() {
    private val _exported = Channel<Boolean>(Channel.BUFFERED)

    /** Whether each CSV export worked. */
    val exported: Flow<Boolean> = _exported.receiveAsFlow()

    private val _period = MutableStateFlow(StatsPeriod.TODAY)
    val period: StateFlow<StatsPeriod> = _period.asStateFlow()

    /** The period with its lookups, counted in the database. */
    private val periodLookups: Flow<Pair<StatsPeriod, Int>> = _period.flatMapLatest { period ->
        val from = firstDay(period, today())?.atStartOfDay(zone())?.toInstant()?.toEpochMilli()
        lookups(from).map { period to it }
    }

    val uiState: StateFlow<StatisticsUiState?> =
        combine(days, books, periodLookups, words, goalMinutes) { days, books, (period, lookups), words, goalMinutes ->
            val today = today()
            val zone = zone()
            val from = firstDay(period, today)
            fun inPeriod(millis: Long) = from == null || !Instant.ofEpochMilli(millis).atZone(zone).toLocalDate().isBefore(from)
            StatisticsUiState(
                summary = ReadingStats.period(days, today, period),
                booksFinished = books.count { book ->
                    val finishedAt = book.finishedAt
                    if (finishedAt != null) inPeriod(finishedAt) else from == null && book.progress >= 1f
                },
                lookups = lookups,
                words = words,
                titles = books.associate { it.id to it.title },
                goal = goalMinutes?.let { ReadingGoal.progress(days, today, it) },
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private fun firstDay(period: StatsPeriod, today: LocalDate): LocalDate? = when (period) {
        StatsPeriod.TODAY -> today
        StatsPeriod.WEEK -> today.minusDays(6)
        StatsPeriod.ALL -> null
    }

    /** Writes the reading of every day, all books together, to [uri] as a CSV file. */
    fun exportCsv(uri: String) {
        viewModelScope.launch {
            val worked = try {
                val totals = days.first().groupBy { LocalDate.parse(it.day) }.map { (day, rows) ->
                    DayTotal(day, rows.sumOf { it.seconds }, rows.sumOf { it.characters })
                }
                writeDocument(uri, CsvExports.reading(totals))
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logWarning("Honmani.stats", "CSV export failed", e)
                false
            }
            _exported.send(worked)
        }
    }

    fun setPeriod(period: StatsPeriod) {
        _period.value = period
    }

    /** Statistics of book [bookId]; nothing while the book isn't in the library. */
    fun book(bookId: Long): Flow<BookStatistics?> =
        combine(
            bookDays(bookId),
            books,
            bookLookups(bookId),
            combine(grades, grading) { grades, grading -> grades to grading },
            this.days,
        ) { days, books, lookups, (grades, grading), allDays ->
            val book = books.firstOrNull { it.id == bookId } ?: return@combine null
            BookStatistics(
                reading = ReadingStats.book(days),
                lookups = lookups,
                position = book.characters,
                // ttu's progress is the share of characters read, so the whole book follows.
                bookCharacters = if (book.progress > 0.01f && book.characters > 0) {
                    (book.characters / book.progress).toLong()
                } else {
                    null
                },
                progress = book.progress,
                finishedAt = book.finishedAt,
                // Score 0: too little text to grade.
                difficulty = grades[book.contentHash]?.difficulty?.takeIf { it.score > 0 },
                grading = grading,
                readerSpeed = ReadingStats.period(allDays, today(), StatsPeriod.ALL).charactersPerHour
                    ?: ReadingStats.book(allDays).charactersPerHour,
            )
        }.combine(
            books.map { all -> all.firstOrNull { it.id == bookId }?.contentHash }
                .distinctUntilChanged()
                .flatMapLatest { hash -> hash?.let(coverage) ?: flowOf(null) },
        ) { statistics, coverage -> statistics?.copy(coverage = coverage) }

    /** The words of the book with [contentHash] the reader does not know yet; null before they can be told. */
    fun unknownWords(contentHash: String): Flow<UnknownWords?> = unknownWords.invoke(contentHash)

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val application = this[APPLICATION_KEY] as HonmaniApplication
                val container = application.container
                StatisticsViewModel(
                    days = container.readingDao.observeAll(),
                    bookDays = container.readingDao::observeBook,
                    books = container.bookDao.observeAll(),
                    lookups = container.wordRepository::lookupCount,
                    bookLookups = container.wordRepository::bookLookupCount,
                    words = container.wordRepository.counts,
                    grades = container.difficultyStore.grades,
                    grading = container.bookGrader.state,
                    coverage = container::bookCoverage,
                    unknownWords = container::unknownWords,
                    goalMinutes = container.readingGoalSettings.goalMinutes,
                    writeDocument = { uri, text -> writeTextDocument(application, uri, text) },
                )
            }
        }
    }
}
