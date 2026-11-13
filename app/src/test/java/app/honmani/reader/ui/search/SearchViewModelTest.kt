package app.honmani.reader.ui.search

import app.honmani.reader.data.dictionary.DictionaryEntity
import app.honmani.reader.data.dictionary.Glossary
import app.honmani.reader.data.dictionary.TermEntity
import app.honmani.reader.lookup.LookupEntry
import app.honmani.reader.lookup.LookupResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {
    private val dictionary = DictionaryEntity(id = 1, title = "JMdict", revision = "1", formatVersion = 3, importedAt = 0)

    private fun entry(id: Long, expression: String) = LookupEntry(
        term = TermEntity(
            id = id,
            dictionaryId = 1,
            expression = expression,
            reading = expression,
            definitionTags = null,
            rules = "",
            score = 0.0,
            glossary = Glossary.plain("""["x"]"""),
            sequence = null,
            termTags = null,
        ),
        dictionary = dictionary,
        source = expression,
        inflections = emptyList(),
        matchedExpression = true,
    )

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun theFirstResultIsTheBestRankedEntry_theListShowsFromTheTop() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val viewModel = SearchViewModel(
            search = { LookupResult(listOf(entry(1, "best"), entry(2, "second"), entry(3, "third"))) },
            compute = dispatcher,
        )
        viewModel.setQuery("b")
        advanceUntilIdle()
        // SearchContent lists the groups first to last from the top, the first one larger.
        assertEquals(listOf("best", "second", "third"), viewModel.results.value.groups.map { it.expression })
    }

    @Test
    fun aThrowingSearchIsReportedAsFailed_notAsNothingFound() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val viewModel = SearchViewModel(search = { error("damaged row") }, compute = dispatcher)
        viewModel.setQuery("b")
        advanceUntilIdle()
        assertTrue(viewModel.results.value.failed)
        assertEquals("b", viewModel.results.value.query)
    }

    @Test
    fun aSearchWithoutMatchesIsNotAFailure() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val viewModel = SearchViewModel(search = { LookupResult(emptyList()) }, compute = dispatcher)
        viewModel.setQuery("b")
        advanceUntilIdle()
        assertFalse(viewModel.results.value.failed)
        assertTrue(viewModel.results.value.groups.isEmpty())
    }
}
