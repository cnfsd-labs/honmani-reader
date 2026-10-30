package app.honmani.reader.ui.reader

import app.honmani.reader.data.dictionary.DictionaryEntity
import app.honmani.reader.data.dictionary.Glossary
import app.honmani.reader.data.dictionary.TermEntity
import app.honmani.reader.data.words.LookedUpWord
import app.honmani.reader.data.words.WordRepository
import app.honmani.reader.lookup.Inflection
import app.honmani.reader.lookup.KanjiInfo
import app.honmani.reader.lookup.LookupEntry
import app.honmani.reader.lookup.LookupResult
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReaderViewModelTest {
    private val dictionary =
        DictionaryEntity(id = 1, title = "JMdict", revision = "1", formatVersion = 3, importedAt = 0)
    private val pending = mutableMapOf<String, CompletableDeferred<LookupResult>>()
    private val recorded = mutableListOf<LookedUpWord>()
    private val kanji = mapOf(
        "食" to KanjiInfo("食", listOf("eat", "food"), listOf("ショク"), listOf("た.べる"), strokes = 9),
        "嘉" to KanjiInfo("嘉", listOf("applaud"), listOf("カ"), emptyList()),
    )
    private val viewModel = ReaderViewModel(
        lookup = { text, _ -> pending.getOrPut(text) { CompletableDeferred() }.await() },
        compute = Dispatchers.Unconfined,
        recordLookup = { recorded += it },
        findBook = { ttuId -> ReadingBook(id = ttuId * 10, title = "Book $ttuId").takeIf { ttuId == 4L } },
        lookupKanji = { text -> kanji.filterKeys { it in text } },
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun tap(text: String) = TapLookup(text = text, sentence = text, sentenceOffset = 0)

    private fun entry(expression: String, source: String) = LookupEntry(
        term = TermEntity(
            id = expression.hashCode().toLong(),
            dictionaryId = 1,
            expression = expression,
            reading = expression,
            definitionTags = null,
            rules = "v1",
            score = 0.0,
            glossary = Glossary.plain("""["to eat"]"""),
            sequence = 1,
            termTags = null,
        ),
        dictionary = dictionary,
        source = source,
        inflections = listOf(Inflection.STEM, Inflection.POLITE, Inflection.PAST),
        matchedExpression = true,
    )

    @Test
    fun tapShowsGroupedResults_andHighlightsTheMatch() {
        viewModel.onTap(tap("食べました。"))
        pending.getValue("食べました。").complete(LookupResult(listOf(entry("食べる", "食べました"))))

        val panel = viewModel.panel.value as LookupPanelState.Results
        assertEquals("食べる", panel.groups.single().expression)
        assertEquals(listOf("to eat"), panel.groups.single().sections.single().senses.single().glosses)
        assertEquals(5, panel.highlight.length)
    }

    @Test
    fun aReferencedWordOpensInThePanel_andBackReturns() {
        viewModel.onTap(tap("猫"))
        pending.getValue("猫").complete(LookupResult(listOf(entry("猫", "猫"))))

        viewModel.lookupWord("犬")
        pending.getValue("犬").complete(LookupResult(listOf(entry("犬", "犬"))))

        val panel = viewModel.panel.value as LookupPanelState.Results
        assertEquals("犬", panel.groups.single().expression)
        assertEquals("猫", panel.previous!!.groups.single().expression)
        // Only the word tapped in the book is saved.
        assertEquals(listOf("猫"), recorded.map { it.expression })

        assertTrue(viewModel.back())
        assertEquals("猫", (viewModel.panel.value as LookupPanelState.Results).groups.single().expression)
        assertFalse(viewModel.back())
    }

    @Test
    fun theKanjiOfTheWordsOpenInThePanel_andBackReturns() {
        viewModel.onTap(tap("食べました。"))
        pending.getValue("食べました。").complete(LookupResult(listOf(entry("食べる", "食べました"))))
        val results = viewModel.panel.value as LookupPanelState.Results
        assertEquals(setOf("食"), results.kanji.keys)

        viewModel.showKanji("食")

        val panel = viewModel.panel.value as LookupPanelState.Kanji
        assertEquals(listOf("eat", "food"), panel.kanji.meanings)
        // The word stays marked in the book.
        assertEquals(results.highlight, panel.highlight)
        assertTrue(viewModel.back())
        assertEquals(results, viewModel.panel.value)
    }

    @Test
    fun thePanelShowsBeforeTheAnkiCheckAndTheKanji_whichFollow() {
        val anki = CompletableDeferred<Set<String>>()
        val model = ReaderViewModel(
            lookup = { text, _ -> pending.getOrPut(text) { CompletableDeferred() }.await() },
            compute = Dispatchers.Unconfined,
            recordLookup = { recorded += it },
            findBook = { null },
            lookupKanji = { text -> kanji.filterKeys { it in text } },
            inAnki = { anki.await() },
        )
        model.onTap(tap("食べました。"))
        pending.getValue("食べました。").complete(LookupResult(listOf(entry("食べる", "食べました"))))

        val first = model.panel.value as LookupPanelState.Results
        assertEquals("食べる", first.groups.single().expression)
        assertEquals(emptySet<String>(), first.inAnki)

        anki.complete(setOf(WordRepository.key("食べる", "食べる")))
        val filled = model.panel.value as LookupPanelState.Results
        assertEquals(setOf(WordRepository.key("食べる", "食べる")), filled.inAnki)
        assertEquals(setOf("食"), filled.kanji.keys)
    }

    @Test
    fun aKanjiNoWordStartsWithShowsTheKanji() {
        viewModel.onTap(tap("嘉兵衛"))
        pending.getValue("嘉兵衛").complete(LookupResult(emptyList()))

        val panel = viewModel.panel.value as LookupPanelState.Kanji
        assertEquals("嘉", panel.kanji.character)
        assertEquals(1, panel.highlight!!.length)
        assertFalse(viewModel.back())

        viewModel.onTap(tap("ほげ"))
        pending.getValue("ほげ").complete(LookupResult(emptyList()))
        assertTrue(viewModel.panel.value is LookupPanelState.NothingFound)
    }

    @Test
    fun aNewTapReplacesASlowerEarlierOne() {
        viewModel.onTap(tap("食べ"))
        viewModel.onTap(tap("猫"))
        pending.getValue("猫").complete(LookupResult(emptyList()))
        // The first lookup finishing late must not overwrite the newer panel.
        pending.getValue("食べ").complete(LookupResult(listOf(entry("食べる", "食べ"))))

        assertEquals(LookupPanelState.NothingFound(tap("猫")), viewModel.panel.value)
    }

    @Test
    fun eachLookupGetsItsOwnHighlight() {
        viewModel.onTap(tap("食べる"))
        pending.getValue("食べる").complete(LookupResult(listOf(entry("食べる", "食べる"))))
        val first = (viewModel.panel.value as LookupPanelState.Results).highlight
        pending.remove("食べる")

        viewModel.onTap(tap("食べる"))
        pending.getValue("食べる").complete(LookupResult(listOf(entry("食べる", "食べる"))))
        val second = (viewModel.panel.value as LookupPanelState.Results).highlight

        // Same word and length, but a new tap: the book must be marked again.
        assertEquals(first.length, second.length)
        assertNotEquals(first, second)
    }

    @Test
    fun theHighlightEchoesTheTapNumber() {
        viewModel.onTap(tap("猫").copy(tapNumber = 5))
        pending.getValue("猫").complete(LookupResult(listOf(entry("猫", "猫"))))

        assertEquals(5, viewModel.highlight.value?.tapNumber)
    }

    @Test
    fun withoutDictionariesThePanelSaysSo() {
        viewModel.onTap(tap("猫"))
        pending.getValue("猫").complete(LookupResult(emptyList(), dictionariesAvailable = false))

        assertEquals(LookupPanelState.NoDictionaries(tap("猫")), viewModel.panel.value)
    }

    @Test
    fun theBookMarksTheWordBeforeThePanelIsBuilt_andTheMarkFollowsThePanel() {
        // Runs the work sent off the main thread one step at a time: the lookup, then the grouping.
        val compute = object : CoroutineDispatcher() {
            val steps = ArrayDeque<Runnable>()

            override fun dispatch(context: CoroutineContext, block: Runnable) {
                steps.addLast(block)
            }
        }
        val viewModel = ReaderViewModel(
            lookup = { _, _ -> LookupResult(listOf(entry("食べる", "食べました"))) },
            compute = compute,
            recordLookup = {},
            lookupKanji = { text -> kanji.filterKeys { it in text } },
        )
        viewModel.onTap(tap("食べました。"))
        compute.steps.removeFirst().run()

        // Found: the mark goes to the book while the panel's results are still being grouped.
        assertEquals(5, viewModel.highlight.value?.length)
        assertNull(viewModel.panel.value)

        compute.steps.removeFirst().run()
        val results = viewModel.panel.value as LookupPanelState.Results
        assertEquals(results.highlight, viewModel.highlight.value)
        viewModel.showKanji("食")
        assertEquals(results.highlight, viewModel.highlight.value)
        viewModel.dismiss()
        assertNull(viewModel.highlight.value)
    }

    @Test
    fun dismissClosesThePanel_andCancelsALookupInFlight() {
        viewModel.onTap(tap("猫"))
        viewModel.dismiss()
        pending.getValue("猫").complete(LookupResult(listOf(entry("猫", "猫"))))

        assertNull(viewModel.panel.value)
    }

    @Test
    fun aLookupIsRecordedAsASavedWord_withTheOpenBook() {
        viewModel.onBookChanged(4)
        viewModel.onTap(TapLookup(text = "食べました。", sentence = "猫が食べました。", sentenceOffset = 2))
        pending.getValue("食べました。").complete(LookupResult(listOf(entry("食べる", "食べました"))))

        assertEquals(
            listOf(
                LookedUpWord(
                    expression = "食べる",
                    reading = "食べる",
                    gloss = "to eat",
                    surface = "食べました",
                    sentence = "猫が食べました。",
                    surfaceStart = 2,
                    bookId = 40,
                    bookTitle = "Book 4",
                ),
            ),
            recorded,
        )
    }

    @Test
    fun lookupsOutsideALibraryBook_orWithoutResults_areNotTiedToABook() {
        viewModel.onBookChanged(4)
        viewModel.onBookChanged(null)
        viewModel.onTap(tap("猫"))
        pending.getValue("猫").complete(LookupResult(listOf(entry("猫", "猫"))))
        viewModel.onTap(tap("犬"))
        pending.getValue("犬").complete(LookupResult(emptyList()))

        assertEquals(listOf("猫"), recorded.map { it.expression })
        assertNull(recorded.single().bookId)
    }

    @Test
    fun aFailedRecordingDoesNotDisturbTheLookup() {
        val failing = ReaderViewModel(
            lookup = { _, _ -> LookupResult(listOf(entry("猫", "猫"))) },
            compute = Dispatchers.Unconfined,
            recordLookup = { error("disk full") },
        )

        failing.onTap(tap("猫"))

        assertEquals("猫", (failing.panel.value as LookupPanelState.Results).groups.single().expression)
    }
}
