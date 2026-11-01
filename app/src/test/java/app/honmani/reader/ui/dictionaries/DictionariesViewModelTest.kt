package app.honmani.reader.ui.dictionaries

import app.honmani.reader.data.dictionary.CatalogDictionary
import app.honmani.reader.data.dictionary.DictionaryCatalog
import app.honmani.reader.data.dictionary.DictionaryDao
import app.honmani.reader.data.dictionary.DictionaryEntity
import app.honmani.reader.data.dictionary.DictionaryImportScheduler
import app.honmani.reader.data.dictionary.DictionaryRepository
import app.honmani.reader.data.dictionary.DictionaryUpdate
import app.honmani.reader.data.dictionary.DictionaryUpdateChecker
import app.honmani.reader.data.dictionary.DownloadException
import kotlinx.coroutines.CoroutineScope
import app.honmani.reader.data.dictionary.ImportError
import app.honmani.reader.data.dictionary.ImportStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DictionariesViewModelTest {
    private val dao = FakeDictionaryDao()
    private val scheduler = FakeImportScheduler()
    private val remoteIndexes = mutableMapOf<String, String>()
    private val checker = DictionaryUpdateChecker(
        dao = dao,
        fetchText = { url -> remoteIndexes[url] ?: throw DownloadException("404") },
        scope = CoroutineScope(Dispatchers.Unconfined),
    )
    private lateinit var viewModel: DictionariesViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        viewModel = DictionariesViewModel(DictionaryRepository(dao), scheduler, checker)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun uiState_isLoadingUntilCollected() {
        assertEquals(DictionariesUiState.Loading, viewModel.uiState.value)
    }

    @Test
    fun uiState_reflectsRepositoryChanges() = runTest {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        fun installed() = (viewModel.uiState.value as DictionariesUiState.Loaded).dictionaries
        assertEquals(emptyList<DictionaryEntity>(), installed())

        val id = dao.insert(dictionary("JMdict"))
        viewModel.setEnabled(id, enabled = false)

        assertEquals(listOf(false), installed().map { it.enabled })

        viewModel.delete(id)

        assertEquals(emptyList<DictionaryEntity>(), installed())
    }

    @Test
    fun movingADictionaryChangesTheOrder_andStopsAtTheEnds() = runTest {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        fun order() = (viewModel.uiState.value as DictionariesUiState.Loaded).dictionaries.map { it.title }
        dao.insert(dictionary("Jitendex"))
        dao.insert(dictionary("JMdict"))
        val wiktionary = dao.insert(dictionary("wty-ja-ja"))
        assertEquals(listOf("JMdict", "Jitendex", "wty-ja-ja"), order())

        viewModel.move(wiktionary, up = true)
        viewModel.move(wiktionary, up = true)
        viewModel.move(wiktionary, up = true)

        assertEquals(listOf("wty-ja-ja", "JMdict", "Jitendex"), order())

        viewModel.move(wiktionary, up = false)

        assertEquals(listOf("JMdict", "wty-ja-ja", "Jitendex"), order())
    }

    @Test
    fun import_enqueuesTheUri_andImportsShowInState() = runTest {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }

        viewModel.import("content://picked/jmdict.zip")

        assertEquals(listOf("content://picked/jmdict.zip"), scheduler.enqueued)
        val failure = ImportStatus.Failed("1", ImportError.INVALID, "index.json is missing")
        scheduler.imports.value = listOf(failure)
        assertEquals(listOf(failure), (viewModel.uiState.value as DictionariesUiState.Loaded).imports)

        viewModel.dismissFailures()

        assertEquals(emptyList<ImportStatus>(), (viewModel.uiState.value as DictionariesUiState.Loaded).imports)
    }

    @Test
    fun deleteShowsProgress_andIgnoresARepeatedRequest() = runTest {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        val id = dao.insert(dictionary("JMdict"))
        dao.blockDeletes()

        viewModel.delete(id)
        viewModel.delete(id)

        assertEquals(setOf(id), (viewModel.uiState.value as DictionariesUiState.Loaded).deleting)
        assertEquals(1, dao.deleteCalls)

        dao.releaseDeletes()

        val state = viewModel.uiState.value as DictionariesUiState.Loaded
        assertEquals(emptySet<Long>(), state.deleting)
        assertEquals(emptyList<DictionaryEntity>(), state.dictionaries)
    }

    @Test
    fun catalogDownloadsShowAsInProgressUntilInstalled() = runTest {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        val jitendex = DictionaryCatalog.dictionaries.single { it.id == "jitendex" }
        fun state() = (viewModel.uiState.value as DictionariesUiState.Loaded)
            .catalog.single { it.dictionary == jitendex }.state

        assertEquals(CatalogItem.State.AVAILABLE, state())

        viewModel.download(jitendex)
        viewModel.download(jitendex)

        assertEquals(listOf(jitendex), scheduler.downloads)
        assertEquals(CatalogItem.State.IN_PROGRESS, state())

        val id = dao.insert(dictionary("Jitendex.org [2026-10-03]"))

        assertEquals(CatalogItem.State.INSTALLED, state())

        // Deleted again: offered for download, not shown as installing.
        viewModel.delete(id)
        assertEquals(CatalogItem.State.AVAILABLE, state())
    }

    @Test
    fun aFailedDownloadCanBeRetried() = runTest {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        val jmdict = DictionaryCatalog.dictionaries.single { it.id == "jmdict-english" }

        viewModel.download(jmdict)
        scheduler.imports.value = listOf(ImportStatus.Failed("1", ImportError.DOWNLOAD_FAILED, "offline", jmdict.id))
        viewModel.download(jmdict)

        assertEquals(listOf(jmdict, jmdict), scheduler.downloads)
    }

    @Test
    fun retryingRequestsOnlyTheFailedDownloadsAndKeepsTheRunningOnesTracked() = runTest {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        fun state(id: String) = (viewModel.uiState.value as DictionariesUiState.Loaded).catalog
            .single { it.dictionary.id == id }.state
        val jmdict = DictionaryCatalog.dictionaries.single { it.id == "jmdict-english" }
        val other = DictionaryCatalog.dictionaries.first { it.id != jmdict.id }

        viewModel.download(jmdict)
        viewModel.download(other)
        scheduler.imports.value = listOf(
            ImportStatus.Failed("1", ImportError.DOWNLOAD_FAILED, "offline", jmdict.id),
            ImportStatus.Running("2", null),
        )
        // The failure puts only its own dictionary back on offer.
        assertEquals(CatalogItem.State.AVAILABLE, state(jmdict.id))
        assertEquals(CatalogItem.State.IN_PROGRESS, state(other.id))

        viewModel.retryFailed(listOf(jmdict, other))

        assertEquals(listOf(jmdict, other, jmdict), scheduler.downloads)
        assertEquals(CatalogItem.State.IN_PROGRESS, state(jmdict.id))
        assertEquals(CatalogItem.State.IN_PROGRESS, state(other.id))
    }

    @Test
    fun updatesAreOfferedAndShowAsUpdatingUntilTheNewVersionIsInstalled() = runTest {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        fun state() = viewModel.uiState.value as DictionariesUiState.Loaded
        val id = dao.insert(
            dictionary("JMdict [2026-10-01]").copy(
                isUpdatable = true,
                indexUrl = "https://example.org/JMdict.json",
            ),
        )
        assertEquals(UpdateCheck.NOT_CHECKED, state().updateCheck)
        remoteIndexes["https://example.org/JMdict.json"] =
            """{"title":"JMdict [2026-10-16]","revision":"2","downloadUrl":"https://example.org/JMdict.zip"}"""

        viewModel.checkForUpdates()

        assertEquals(UpdateCheck.CHECKED, state().updateCheck)
        val update = state().updates.getValue(id)
        assertEquals("2", update.revision)
        assertEquals("https://example.org/JMdict.zip", update.downloadUrl)

        viewModel.update(update)
        viewModel.update(update)
        assertEquals(listOf(update), scheduler.updates)
        assertEquals(setOf(id), state().updating)

        // The import puts the new version in place of the old one.
        dao.delete(id)
        dao.insert(dictionary("JMdict [2026-10-16]").copy(revision = "2"))
        assertTrue(state().updates.isEmpty())
        assertTrue(state().updating.isEmpty())
    }

    private fun dictionary(title: String) =
        DictionaryEntity(title = title, revision = "1", formatVersion = 3, importedAt = 0)
}

private class FakeImportScheduler : DictionaryImportScheduler {
    val enqueued = mutableListOf<String>()
    val downloads = mutableListOf<CatalogDictionary>()
    override val imports = MutableStateFlow<List<ImportStatus>>(emptyList())

    override fun enqueue(uri: String) {
        enqueued += uri
    }

    override fun download(dictionary: CatalogDictionary) {
        downloads += dictionary
    }

    val updates = mutableListOf<DictionaryUpdate>()

    override fun update(update: DictionaryUpdate) {
        updates += update
    }

    override fun clearFailures() {
        imports.update { list -> list.filterNot { it is ImportStatus.Failed } }
    }
}

private class FakeDictionaryDao : DictionaryDao {
    private val rows = MutableStateFlow<List<DictionaryEntity>>(emptyList())
    private var nextId = 1L

    override fun observeReady(): Flow<List<DictionaryEntity>> =
        rows.map { list -> list.sortedWith(compareBy({ it.priority }, { it.title })) }

    override suspend fun enabledReady() = rows.value.filter { it.enabled }

    override suspend fun allReady() = rows.value

    override suspend fun findByTitle(title: String) = rows.value.firstOrNull { it.title == title }

    override suspend fun findById(id: Long) = rows.value.firstOrNull { it.id == id }

    override suspend fun setEnabledAndPriority(id: Long, enabled: Boolean, priority: Int) {
        rows.update { list -> list.map { if (it.id == id) it.copy(enabled = enabled, priority = priority) else it } }
    }

    override suspend fun setPriority(id: Long, priority: Int) {
        rows.update { list -> list.map { if (it.id == id) it.copy(priority = priority) else it } }
    }

    override suspend fun nextPriority() = (rows.value.maxOfOrNull { it.priority } ?: -1) + 1

    override suspend fun markReady(id: Long, termCount: Int, metaCount: Int, kanjiCount: Int) {
        rows.update { list -> list.map { if (it.id == id) it.copy(termCount = termCount) else it } }
    }

    override suspend fun deleteIncomplete() {
        rows.update { list -> list.filterNot { it.status == DictionaryEntity.Status.IMPORTING } }
    }

    override suspend fun insert(dictionary: DictionaryEntity): Long {
        val id = nextId++
        rows.update { it + dictionary.copy(id = id) }
        return id
    }

    override suspend fun setEnabled(id: Long, enabled: Boolean) {
        rows.update { list -> list.map { if (it.id == id) it.copy(enabled = enabled) else it } }
    }

    var deleteCalls = 0
    private var deleteGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null

    fun blockDeletes() {
        deleteGate = kotlinx.coroutines.CompletableDeferred()
    }

    fun releaseDeletes() {
        deleteGate?.complete(Unit)
    }

    override suspend fun delete(id: Long) {
        deleteCalls++
        deleteGate?.await()
        rows.update { list -> list.filterNot { it.id == id } }
    }
}
