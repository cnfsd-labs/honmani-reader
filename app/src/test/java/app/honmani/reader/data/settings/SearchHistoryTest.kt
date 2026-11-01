package app.honmani.reader.data.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SearchHistoryTest {
    private fun preferences() = ApplicationProvider.getApplicationContext<Context>()
        .getSharedPreferences("search-history-test", Context.MODE_PRIVATE)

    @Test
    fun keepsTheLastTwentyNewestFirst_onceEach() {
        val history = SearchHistory(preferences())
        (1..25).forEach { history.add("q$it") }
        history.add(" q24 ")
        history.add("")

        assertEquals(SearchHistory.MAX_ENTRIES, history.entries.value.size)
        assertEquals(listOf("q24", "q25", "q23"), history.entries.value.take(3))
        assertEquals(history.entries.value, SearchHistory(preferences()).entries.value)

        history.remove("q25")
        assertEquals(listOf("q24", "q23"), history.entries.value.take(2))
        history.clear()
        assertEquals(emptyList<String>(), SearchHistory(preferences()).entries.value)
    }
}
