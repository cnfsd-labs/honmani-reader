package app.honmani.reader.ui.stats

import app.honmani.reader.data.stats.ReadingDayEntity
import app.honmani.reader.data.stats.StatsPeriod
import app.honmani.reader.data.words.WordCounts
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StatisticsViewModelTest {
    private val zone = ZoneId.of("Asia/Tokyo")
    private val today = LocalDate.of(2026, 10, 19)

    /** Lookups in the database (times), as the statistics query counts them. */
    private val lookupTimes = MutableStateFlow(listOf<Long>())

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(
        days: List<ReadingDayEntity> = emptyList(),
        writeDocument: suspend (String, String) -> Unit = { _, _ -> },
        goalMinutes: Int? = null,
    ) = StatisticsViewModel(
        days = flowOf(days),
        bookDays = { flowOf(emptyList()) },
        books = flowOf(emptyList()),
        lookups = { from -> lookupTimes.map { times -> times.count { from == null || it >= from } } },
        bookLookups = { flowOf(0) },
        words = flowOf(WordCounts()),
        goalMinutes = flowOf(goalMinutes),
        today = { today },
        zone = { zone },
        writeDocument = writeDocument,
    )

    private fun millis(date: LocalDate, hour: Int) = date.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun theGoalShowsOnlyWhileItIsOn() = runTest {
        val rows = listOf(ReadingDayEntity(1, today.toString(), seconds = 600))
        val off = viewModel(rows)
        val on = viewModel(rows, goalMinutes = 20)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { off.uiState.collect {} }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { on.uiState.collect {} }

        assertNull(off.uiState.filterNotNull().first().goal)
        assertEquals(600L, on.uiState.filterNotNull().first().goal?.secondsToday)
        assertEquals(20, on.uiState.filterNotNull().first().goal?.goalMinutes)
    }

    @Test
    fun lookupsAreCountedFromTheStartOfThePeriod() = runTest {
        lookupTimes.value = listOf(
            millis(today.minusDays(30), 12),
            millis(today.minusDays(6), 0),
            millis(today.minusDays(1), 23),
            millis(today, 0),
            millis(today, 9),
        )
        val viewModel = viewModel()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }

        suspend fun lookups(period: StatsPeriod): Int {
            viewModel.setPeriod(period)
            // The period's state, which carries the period's own count (never the previous one's).
            return viewModel.uiState.filterNotNull().first { it.summary.period == period }.lookups
        }
        assertEquals(2, lookups(StatsPeriod.TODAY))
        assertEquals(4, lookups(StatsPeriod.WEEK))
        assertEquals(5, lookups(StatsPeriod.ALL))

        // A new lookup shows at once.
        lookupTimes.value += millis(today, 10)
        assertEquals(6, viewModel.uiState.filterNotNull().first { it.lookups != 5 }.lookups)
    }

    @Test
    fun theCsvHasOneRowPerDayWithAllBooksAdded() = runTest {
        var written = "" to ""
        val viewModel = viewModel(
            days = listOf(
                ReadingDayEntity(bookId = 1, day = "2026-10-18", seconds = 600, characters = 1000),
                ReadingDayEntity(bookId = 2, day = "2026-10-18", seconds = 600, characters = 2000),
                ReadingDayEntity(bookId = 1, day = "2026-10-19", seconds = 60, characters = 10),
            ),
            writeDocument = { uri, text -> written = uri to text },
        )

        viewModel.exportCsv("content://x/y")

        assertEquals(true, viewModel.exported.first())
        assertEquals("content://x/y", written.first)
        assertEquals(
            "date,seconds,characters,characters_per_hour\r\n2026-10-18,1200,3000,9000\r\n2026-10-19,60,10,600\r\n",
            written.second,
        )
    }

    @Test
    fun aFailedCsvExportIsReported() = runTest {
        val viewModel = viewModel(writeDocument = { _, _ -> throw java.io.IOException("full") })

        viewModel.exportCsv("content://x/y")

        assertEquals(false, viewModel.exported.first())
    }
}
