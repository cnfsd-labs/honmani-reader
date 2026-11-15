package app.honmani.reader.data.stats

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MilestoneStoreTest {
    private val today = LocalDate.of(2026, 10, 21)

    private fun preferences() = ApplicationProvider.getApplicationContext<Context>()
        .getSharedPreferences("milestones-test", Context.MODE_PRIVATE)

    @Test
    fun milestonesReachedBeforeTheFirstRecordAreSeenButNotNews() {
        val store = MilestoneStore(preferences())

        store.record(listOf("characters_10000"), today)

        assertEquals(mapOf("characters_10000" to today), store.seen.value)
        assertEquals(emptySet<String>(), store.pending.value)
    }

    @Test
    fun newMilestonesStayPendingUntilShown_andKeepTheirFirstDay() {
        MilestoneStore(preferences()).record(emptyList(), today.minusDays(1))
        val store = MilestoneStore(preferences())

        store.record(listOf("books_1"), today)
        store.record(listOf("books_1", "anki_1"), today.plusDays(2))

        val reopened = MilestoneStore(preferences())
        assertEquals(mapOf("books_1" to today, "anki_1" to today.plusDays(2)), reopened.seen.value)
        assertEquals(setOf("books_1", "anki_1"), reopened.pending.value)

        reopened.shown(listOf("books_1"))

        assertEquals(setOf("anki_1"), MilestoneStore(preferences()).pending.value)
    }

    @Test
    fun aRestoreKeepsTheEarlierDay_andAnnouncesNothing() {
        val store = MilestoneStore(preferences())
        store.record(listOf("books_1"), today)

        store.restore(mapOf("books_1" to today.minusDays(30), "streak_7" to today.minusDays(9)))
        store.record(listOf("books_1", "streak_7"), today.plusDays(1))

        val reopened = MilestoneStore(preferences())
        assertEquals(mapOf("books_1" to today.minusDays(30), "streak_7" to today.minusDays(9)), reopened.seen.value)
        assertEquals(emptySet<String>(), reopened.pending.value)
    }

    @Test
    fun aRestoreWithoutMilestonesOnANewInstall_leavesTheFirstRecordQuiet() {
        val store = MilestoneStore(preferences())

        store.restore(emptyMap())
        store.record(listOf("books_1", "characters_10000"), today)

        assertEquals(emptySet<String>(), MilestoneStore(preferences()).pending.value)
    }
}
