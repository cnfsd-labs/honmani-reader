package app.honmani.reader.ui.aozora

import app.honmani.reader.data.aozora.AozoraException
import app.honmani.reader.data.aozora.AozoraRepository
import app.honmani.reader.data.aozora.AozoraSort
import app.honmani.reader.data.aozora.FakeAozoraSource
import app.honmani.reader.data.aozora.indexJson
import app.honmani.reader.data.aozora.work
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class AozoraViewModelTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val source = FakeAozoraSource(
        json = indexJson(
            works = listOf(
                work("1", "吾輩は猫である", "わがはいはねこである", 0, "nn"),
                work("2", "こゝろ", "こころ", 0, "no"),
                work("3", "羅生門", "らしょうもん", 1, "nn"),
            ).joinToString(","),
        ),
    )
    private val repository get() = AozoraRepository(File(folder.root, "aozora"), source)

    @Before
    fun setUp() = Dispatchers.setMain(Dispatchers.Unconfined)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() = AozoraViewModel(repository, source, worker = Dispatchers.Unconfined, searchDelayMs = 0).also { settle(it) }

    /** The repository works on the IO dispatcher: wait until the model is neither starting nor loading. */
    private fun settle(vm: AozoraViewModel) {
        runBlocking { withTimeout(5_000) { vm.uiState.first { !it.starting && !it.loading } } }
    }

    /** Loads the list and waits for it to show (searching starts after loading ends when the model runs unconfined). */
    private fun load(vm: AozoraViewModel) {
        vm.load()
        runBlocking { withTimeout(5_000) { vm.uiState.first { !it.loading && it.hasList && it.results.isNotEmpty() } } }
    }

    @Test
    fun withoutAKeptListItWaitsForTheUserAndFetchesNothing() {
        val vm = viewModel()
        val state = vm.uiState.value
        assertFalse(state.starting)
        assertFalse(state.hasList)
        assertTrue(source.asked.isEmpty())
    }

    @Test
    fun aKeptListOpensWithoutTheNetwork() {
        runBlocking { repository.refresh() }
        source.asked.clear()
        val vm = viewModel()
        val state = vm.uiState.value
        assertTrue(state.hasList)
        assertEquals(3, state.total)
        // Old kana is hidden by default; without a skill or difficulties the order is alphabetical by title reading.
        assertEquals(listOf("3", "1"), state.results.map { it.id })
        assertTrue(source.asked.isEmpty())
    }

    @Test
    fun loadingTheListShowsIt() {
        val vm = viewModel()
        load(vm)
        val state = vm.uiState.value
        assertTrue(state.hasList)
        assertFalse(state.loading)
        assertNull(state.error)
        assertEquals("2026-11-07", state.generated)
        assertEquals(listOf("3", "1"), state.results.map { it.id })
        assertFalse(state.hasDifficulty)
    }

    @Test
    fun aFailedLoadShowsTheReason_andRetryWorks() {
        val vm = viewModel()
        source.failure = IOException("offline")
        vm.load()
        settle(vm)
        assertEquals(AozoraException.Reason.NETWORK, vm.uiState.value.error)
        assertFalse(vm.uiState.value.loading)
        assertFalse(vm.uiState.value.hasList)

        source.failure = AozoraException(AozoraException.Reason.REFUSED, "no")
        vm.load()
        settle(vm)
        assertEquals(AozoraException.Reason.REFUSED, vm.uiState.value.error)

        source.failure = null
        load(vm)
        assertNull(vm.uiState.value.error)
        assertTrue(vm.uiState.value.hasList)
    }

    @Test
    fun searchingNarrowsTheResultsAndTheSwitchAddsOldKana() {
        val vm = viewModel()
        load(vm)
        vm.setQuery("ネコ")
        assertEquals(listOf("1"), vm.uiState.value.results.map { it.id })
        vm.setQuery("ころ")
        assertEquals(emptyList<String>(), vm.uiState.value.results.map { it.id })
        vm.setIncludeOld(true)
        assertEquals(listOf("2"), vm.uiState.value.results.map { it.id })
        vm.setQuery("")
        assertEquals(listOf("2", "3", "1"), vm.uiState.value.results.map { it.id })
    }

    @Test
    fun aQueryTypedBeforeTheListArrivesAppliesToIt() {
        val vm = viewModel()
        vm.setQuery("羅生")
        load(vm)
        assertEquals(listOf("3"), vm.uiState.value.results.map { it.id })
    }

    @Test
    fun addingABookHandsOverItsTextOnce() {
        val vm = viewModel()
        load(vm)
        val work = vm.uiState.value.results.first { it.id == "1" }
        source.books[work.file] = "本文".toByteArray()
        vm.select(work)
        assertEquals(work, vm.uiState.value.selected)
        vm.add()
        val book = runBlocking { withTimeout(5_000) { vm.downloaded.first() } }
        assertEquals("吾輩は猫である.txt", book.fileName)
        assertArrayEquals("本文".toByteArray(), book.bytes)
        assertNull(vm.uiState.value.selected)
        assertNull(vm.uiState.value.download)
        assertEquals(listOf(work.file), source.requestedBooks)
    }

    @Test
    fun aFailedDownloadShowsTheReason_andCanBeRetried() {
        val vm = viewModel()
        load(vm)
        val work = vm.uiState.value.results.first()
        vm.select(work)
        source.bookFailure = IOException("dropped")
        vm.add()
        assertEquals(AozoraDownload.Failed(AozoraException.Reason.NETWORK), vm.uiState.value.download)
        assertEquals(work, vm.uiState.value.selected)

        source.bookFailure = null
        source.books[work.file] = byteArrayOf(1)
        vm.add()
        val book = runBlocking { withTimeout(5_000) { vm.downloaded.first() } }
        assertEquals(1, book.bytes.size)
    }

    @Test
    fun progressShowsWhileDownloading_andCancelStopsIt() {
        val vm = viewModel()
        load(vm)
        val work = vm.uiState.value.results.first()
        source.gate = CompletableDeferred()
        vm.select(work)
        vm.add()
        assertEquals(AozoraDownload.Running(0.5f), vm.uiState.value.download)
        // A running download keeps the details open and ignores another selection.
        vm.select(vm.uiState.value.results.last())
        assertEquals(work, vm.uiState.value.selected)

        vm.cancelDownload()
        assertNull(vm.uiState.value.download)
        source.gate!!.complete(Unit)
        source.books[work.file] = byteArrayOf(1)
        // Nothing arrives from the cancelled download.
        assertEquals(1, source.requestedBooks.size)
        vm.add()
        assertEquals(2, source.requestedBooks.size)
    }

    @Test
    fun closingTheDetailsStopsTheDownload() {
        val vm = viewModel()
        load(vm)
        val work = vm.uiState.value.results.first()
        source.gate = CompletableDeferred()
        vm.select(work)
        vm.add()
        vm.deselect()
        assertNull(vm.uiState.value.selected)
        assertNull(vm.uiState.value.download)
        source.books[work.file] = byteArrayOf(1)
        source.gate!!.complete(Unit)
        assertNull(vm.uiState.value.download)
    }

    @Test
    fun fileNamesAreTheTitleWithTxt() {
        val vm = viewModel()
        load(vm)
        assertEquals("羅生門.txt", AozoraViewModel.fileName(vm.uiState.value.results.first { it.id == "3" }))
    }

    private fun gradedViewModel(skill: Flow<Int?>): AozoraViewModel {
        val graded = FakeAozoraSource(
            json = indexJson(
                works = listOf(
                    work("1", "あ", "あ", 0, "nn", difficulty = 80),
                    work("2", "い", "い", 0, "nn", difficulty = 30),
                    work("3", "う", "う", 0, "nn"),
                    work("4", "え", "え", 0, "nn", difficulty = 52),
                ).joinToString(","),
            ),
        )
        val vm = AozoraViewModel(
            AozoraRepository(File(folder.root, "graded"), graded),
            graded,
            worker = Dispatchers.Unconfined,
            searchDelayMs = 0,
            skill = skill,
        )
        settle(vm)
        vm.load()
        runBlocking { withTimeout(5_000) { vm.uiState.first { !it.loading && it.hasList && it.results.isNotEmpty() } } }
        return vm
    }

    private fun AozoraViewModel.order() = uiState.value.results.map { it.id }

    @Test
    fun theOrderPickerNeedsDifficulties() {
        assertTrue(gradedViewModel(flowOf(null)).uiState.value.hasDifficulty)
    }

    @Test
    fun bestFitIsTheDefaultAndFollowsTheSkill() {
        val skill = MutableStateFlow<Int?>(null)
        val vm = gradedViewModel(skill)
        assertEquals(AozoraSort.BEST_FIT, vm.uiState.value.sort)
        // No skill yet: alphabetical.
        assertEquals(listOf("1", "2", "3", "4"), vm.order())
        // Skill 50: a little above it (52) is the best fit, then 30, 80; no difficulty last.
        skill.value = 50
        assertEquals(50, vm.uiState.value.skill)
        assertEquals(listOf("4", "2", "1", "3"), vm.order())
        skill.value = 90
        assertEquals(listOf("1", "4", "2", "3"), vm.order())
    }

    @Test
    fun theChosenOrderAppliesWithTheSearch() {
        val vm = gradedViewModel(flowOf(50))
        vm.setSort(AozoraSort.EASIEST)
        assertEquals(listOf("2", "4", "1", "3"), vm.order())
        vm.setSort(AozoraSort.HARDEST)
        assertEquals(listOf("1", "4", "2", "3"), vm.order())
        vm.setSort(AozoraSort.ALPHABETICAL)
        assertEquals(listOf("1", "2", "3", "4"), vm.order())
        vm.setSort(AozoraSort.EASIEST)
        vm.setQuery("え")
        assertEquals(listOf("4"), vm.order())
    }
}
