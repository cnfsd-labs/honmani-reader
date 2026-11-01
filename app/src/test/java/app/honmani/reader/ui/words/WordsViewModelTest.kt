package app.honmani.reader.ui.words

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.honmani.reader.data.HonmaniDatabase
import app.honmani.reader.data.dictionary.DictionaryImporter
import app.honmani.reader.data.dictionary.index
import app.honmani.reader.data.dictionary.writeZip
import app.honmani.reader.anki.AnkiResult
import app.honmani.reader.data.words.LookedUpWord
import app.honmani.reader.data.words.WordEntity
import app.honmani.reader.data.words.WordRepository
import app.honmani.reader.lookup.DictionaryLookup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import java.time.ZoneId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class WordsViewModelTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var database: HonmaniDatabase
    private lateinit var repository: WordRepository
    private val showFrequency = MutableStateFlow(true)

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            HonmaniDatabase::class.java,
        ).allowMainThreadQueries().build()
        repository = WordRepository(database)
    }

    @After
    fun tearDown() {
        database.close()
        Dispatchers.resetMain()
    }

    private fun lookedUp(expression: String) = LookedUpWord(
        expression = expression,
        reading = "よみ",
        gloss = "gloss",
        surface = expression,
        sentence = "${expression}だ。",
        surfaceStart = 0,
        bookId = null,
        bookTitle = null,
    )

    private fun viewModel(
        addCardDeferred: suspend (WordEntity, MutableMap<Long, Long>) -> AnkiResult = { _, _ -> AnkiResult.Refused },
        markInAnki: suspend (Map<Long, Long>) -> Unit = {},
        writeDocument: suspend (String, String) -> Unit = { _, _ -> },
    ): WordsViewModel {
        val lookup = DictionaryLookup(
            database.dictionaryDao(),
            database.termDao(),
            database.termMetaDao(),
            database.kanjiDao(),
        )
        return WordsViewModel(
            repository = repository,
            writeDocument = writeDocument,
            addCardDeferred = addCardDeferred,
            markInAnki = markInAnki,
            zone = { ZoneId.of("UTC") },
            showFrequency = showFrequency,
            frequencyOf = { words -> lookup.frequencies(words) },
        )
    }

    @Test
    fun savedWordsShowTheirFrequencyOnlyWhenSwitchedOnAndKnownToTheDictionary() = runTest {
        repository.record(lookedUp("猫"))
        repository.record(lookedUp("犬"))
        val files = mapOf(
            "index.json" to index("Jiten", extra = ",\"frequencyMode\":\"rank-based\""),
            "term_meta_bank_1.json" to """[["猫","freq",{"frequency":{"value":727,"displayValue":"727"}}]]""",
        )
        DictionaryImporter(database).import(writeZip(temp.root, files, "jiten.zip"))
        val viewModel = viewModel()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }

        val shown = viewModel.uiState.first { it.frequencies.isNotEmpty() }

        // Only the word the dictionary knows gets a badge.
        val cat = shown.learning!!.single { it.expression == "猫" }
        assertEquals(mapOf(cat.id to "727"), shown.frequencies.mapValues { it.value.display })

        showFrequency.value = false
        viewModel.uiState.first { it.frequencies.isEmpty() }
    }

    @Test
    fun addAllToAnkiWritesTheMarksInChunksNotOneByOne() = runTest {
        repeat(120) { repository.record(lookedUp("語$it")) }
        val written = mutableListOf<Map<Long, Long>>()
        val viewModel = viewModel(
            addCardDeferred = { word, marks ->
                marks[word.id] = word.id + 1000
                AnkiResult.Added(word.id + 1000)
            },
            markInAnki = { written += it; repository.setAnkiNotes(it) },
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        viewModel.uiState.first { it.learning?.size == 120 }

        viewModel.addAllToAnki()
        val outcome = viewModel.ankiOutcomes.first()

        assertEquals(AnkiOutcome.Many(120, null, null, 0), outcome)
        assertEquals(listOf(50, 50, 20), written.map { it.size })
        assertEquals(120, repository.learning.first().count { it.ankiNoteId != null })
    }

    @Test
    fun theCsvExportHoldsEverySavedWordNotJustTheListShown() = runTest {
        repository.record(lookedUp("猫"))
        repository.record(lookedUp("犬"))
        repository.markLearned(repository.learning.first().first { it.expression == "犬" }.id)
        var written = ""
        val viewModel = viewModel(writeDocument = { _, text -> written = text })

        viewModel.exportCsv("content://x/y")

        assertEquals(AnkiOutcome.CsvExported(2), viewModel.ankiOutcomes.first())
        val rows = written.trimEnd().split("\r\n")
        assertEquals(3, rows.size)
        assertEquals(setOf("猫", "犬"), rows.drop(1).map { it.substringBefore(',') }.toSet())
    }
}
