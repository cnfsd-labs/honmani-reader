package app.honmani.reader.ui.progress

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.honmani.reader.data.library.BookEntity
import app.honmani.reader.data.library.BookFormat
import app.honmani.reader.data.stats.FinishedBookEntity
import app.honmani.reader.data.stats.MilestoneKind
import app.honmani.reader.data.stats.MilestoneStore
import app.honmani.reader.data.stats.ReadingDayEntity
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
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
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ProgressViewModelTest {
    private val today = LocalDate.of(2026, 10, 21)
    private val days = MutableStateFlow(listOf(ReadingDayEntity(1, today.minusDays(1).toString(), 3_600, 20_000)))
    private val store = MilestoneStore(
        ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("progress-test", Context.MODE_PRIVATE),
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(
        books: List<BookEntity> = emptyList(),
        recorded: List<FinishedBookEntity> = emptyList(),
        goalMinutes: Int? = null,
    ) = ProgressViewModel(
        days = days,
        books = flowOf(books),
        recordedFinished = flowOf(recorded),
        wordsLearned = flowOf(0),
        ankiCards = flowOf(0),
        store = store,
        goalMinutes = flowOf(goalMinutes),
        today = { today },
        zone = { ZoneId.of("Asia/Tokyo") },
    )

    @Test
    fun removedFinishedBooksStillCountTowardsTheBookMilestones() = runTest {
        val kept = BookEntity(contentHash = "a", title = "a", format = BookFormat.EPUB, fileName = "a.epub", addedAt = 0, finishedAt = 1L)
        val alone = viewModel(books = listOf(kept))
        val withRemoved = viewModel(books = listOf(kept), recorded = listOf(FinishedBookEntity("a", "a", 1L), FinishedBookEntity("b", "b", 2L)))
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { alone.uiState.collect {} }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { withRemoved.uiState.collect {} }

        fun finished(vm: ProgressViewModel) = vm.uiState.value!!.milestones.first { it.kind == MilestoneKind.BOOKS }.current

        // "a" is in the library and recorded: once. "b" was removed.
        assertEquals(1L, finished(alone))
        assertEquals(2L, finished(withRemoved))
    }

    @Test
    fun onlyMilestonesReachedAfterTheFirstLookAreNews() = runTest {
        val viewModel = viewModel()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.pending.collect {} }

        assertEquals(4, viewModel.uiState.value?.level?.level)
        assertEquals(emptyList<String>(), viewModel.pending.value.map { it.id })

        days.value += ReadingDayEntity(1, today.toString(), 600, 90_000)

        assertEquals(listOf("characters_100000"), viewModel.pending.value.map { it.id })
        viewModel.shown(viewModel.pending.value)
        assertEquals(emptyList<String>(), viewModel.pending.value.map { it.id })
    }

    @Test
    fun theGoalCardHasAStateOnlyWhileTheGoalIsOn() = runTest {
        days.value += ReadingDayEntity(1, today.toString(), 600, 9_000)
        val off = viewModel()
        val on = viewModel(goalMinutes = 20)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { off.uiState.collect {} }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { on.uiState.collect {} }

        assertNull(off.uiState.value?.goal)
        assertEquals(600L, on.uiState.value?.goal?.secondsToday)
        assertEquals(20, on.uiState.value?.goal?.goalMinutes)
    }
}
