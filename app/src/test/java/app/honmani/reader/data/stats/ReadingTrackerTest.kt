package app.honmani.reader.data.stats

import java.time.ZoneOffset
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReadingTrackerTest {
    private class FakeStore : ReadingStore {
        val seconds = HashMap<Pair<Long, String>, Long>()
        val characters = HashMap<Pair<Long, String>, Long>()
        val positions = HashMap<Long, Long>()
        val read = ArrayList<Pair<Float, Float>>()

        override suspend fun add(ttuId: Long, day: String, seconds: Long, characters: Long) {
            val key = ttuId to day
            this.seconds[key] = (this.seconds[key] ?: 0) + seconds
            this.characters[key] = (this.characters[key] ?: 0) + characters
        }

        override suspend fun position(ttuId: Long) = positions[ttuId]

        override suspend fun setPosition(ttuId: Long, characters: Long) {
            positions[ttuId] = characters
        }

        override suspend fun addRead(ttuId: Long, from: Float, to: Float) {
            read += from to to
        }
    }

    private val store = FakeStore()
    private var now = DAY_START

    private fun TestScope.tracker() = ReadingTracker(store, this, clock = { now }, zone = { ZoneOffset.UTC })

    private fun minutes(count: Int) {
        now += count * 60_000L
    }

    @Test
    fun countsTimeOnlyWhileTheBookIsOnScreenAndTheAppInFront() = runTest {
        val tracker = tracker()
        tracker.setForeground(true)
        tracker.setBook(7)
        minutes(3) // still covered by the library
        tracker.setVisible(true)
        minutes(2)
        tracker.onActivity()
        minutes(1)
        tracker.setForeground(false)
        minutes(10) // in the background
        tracker.setForeground(true)
        minutes(1)
        tracker.setVisible(false)
        advanceUntilIdle()

        assertEquals(4 * 60L, store.seconds[7L to DAY])
    }

    @Test
    fun aLongStretchWithoutActivityCountsFiveMinutes() = runTest {
        val tracker = tracker()
        tracker.setForeground(true)
        tracker.setVisible(true)
        tracker.setBook(7)
        minutes(30)
        tracker.onActivity()
        minutes(1)
        tracker.setBook(null)
        advanceUntilIdle()

        assertEquals(6 * 60L, store.seconds[7L to DAY])
    }

    @Test
    fun charactersCountUpToWhatTheReadingTimeAllows() = runTest {
        val tracker = tracker()
        store.positions[7] = 1_000
        tracker.setForeground(true)
        tracker.setVisible(true)
        tracker.setBook(7)
        minutes(2)
        tracker.setVisible(false)
        tracker.onPositions(mapOf(7L to 1_800L))
        advanceUntilIdle()
        assertEquals(800L, store.characters[7L to DAY])
        assertEquals(1_800L, store.positions[7])

        // A jump far ahead after a minute: only what a minute of reading allows (plus a little).
        tracker.setVisible(true)
        minutes(1)
        tracker.setVisible(false)
        tracker.onPositions(mapOf(7L to 50_000L))
        advanceUntilIdle()
        assertEquals(800L + 60 * ReadingTracker.MAX_CHARACTERS_PER_SECOND + ReadingTracker.SLACK_CHARACTERS, store.characters[7L to DAY])

        // Going back counts nothing, and the position follows.
        tracker.onPositions(mapOf(7L to 10_000L))
        advanceUntilIdle()
        assertEquals(10_000L, store.positions[7])
        assertEquals(800L + 60 * ReadingTracker.MAX_CHARACTERS_PER_SECOND + ReadingTracker.SLACK_CHARACTERS, store.characters[7L to DAY])
    }

    private fun seconds(count: Int) {
        now += count * 1_000L
    }

    @Test
    fun onlyPagesReadAddStretches() = runTest {
        // A book of 10,000 characters: the position is a tenth of the characters.
        val tracker = tracker()
        store.positions[7] = 1_000
        tracker.setForeground(true)
        tracker.setVisible(true)
        tracker.setBook(7)
        seconds(30)
        tracker.onPage(7, 1_400, 0.14f)
        advanceUntilIdle()
        // Flicked past.
        seconds(2)
        tracker.onPage(7, 1_800, 0.18f)
        advanceUntilIdle()
        // A jump through the table of contents after ten seconds: what ten seconds allow.
        seconds(10)
        tracker.onPage(7, 6_000, 0.6f)
        advanceUntilIdle()
        // Back, then a page read without the position's share (nothing to place it with).
        tracker.onPage(7, 2_000, 0.2f)
        advanceUntilIdle()
        seconds(30)
        tracker.onPage(7, 2_400)
        advanceUntilIdle()

        val expected = listOf(0.1f to 0.14f, 0.18f to 0.18f + 10 * ReadingTracker.MAX_CHARACTERS_PER_SECOND / 10_000f)
        assertEquals(expected.size, store.read.size)
        for ((want, got) in expected.zip(store.read)) {
            assertEquals(want.first, got.first, 0.0001f)
            assertEquals(want.second, got.second, 0.0001f)
        }
    }

    @Test
    fun timeGoesToThePageItWasSpentOnWhenTurnsCome_beforeTheWritesRun() = runTest {
        val tracker = tracker()
        store.positions[7] = 1_000
        tracker.setForeground(true)
        tracker.setVisible(true)
        tracker.setBook(7)
        // Flicked past after a second, then a page read for forty: no advance in between.
        seconds(1)
        tracker.onPage(7, 1_400)
        seconds(40)
        tracker.onPage(7, 1_700)
        advanceUntilIdle()

        assertEquals(300L, store.characters[7L to DAY])
        assertEquals(1_700L, store.positions[7])
    }

    @Test
    fun pagesCountWhenTheyWereOnScreenLongEnough() = runTest {
        val tracker = tracker()
        store.positions[7] = 1_000
        tracker.setForeground(true)
        tracker.setVisible(true)
        tracker.setBook(7)
        seconds(40)
        tracker.onPage(7, 1_300)
        advanceUntilIdle()
        assertEquals(300L, store.characters[7L to DAY])

        // Flicked past in two seconds each: not read, but the position follows.
        seconds(2)
        tracker.onPage(7, 1_600)
        seconds(2)
        tracker.onPage(7, 1_900)
        advanceUntilIdle()
        assertEquals(300L, store.characters[7L to DAY])
        assertEquals(1_900L, store.positions[7])

        // A page turn within one long paragraph keeps the position; the time adds up.
        seconds(20)
        tracker.onPage(7, 1_900)
        seconds(20)
        tracker.onPage(7, 2_500)
        advanceUntilIdle()
        assertEquals(900L, store.characters[7L to DAY])

        // A jump far ahead: only what the time on the page allows.
        seconds(10)
        tracker.onPage(7, 90_000)
        advanceUntilIdle()
        assertEquals(900L + 10 * ReadingTracker.MAX_CHARACTERS_PER_SECOND, store.characters[7L to DAY])

        // The library's report of the same position adds nothing.
        tracker.onPositions(mapOf(7L to 90_000L))
        advanceUntilIdle()
        assertEquals(900L + 10 * ReadingTracker.MAX_CHARACTERS_PER_SECOND, store.characters[7L to DAY])
    }

    @Test
    fun aPageTurnWithoutAStoredPositionCountsNothingAndStoresIt() = runTest {
        val tracker = tracker()
        tracker.setForeground(true)
        tracker.setVisible(true)
        tracker.setBook(7)
        seconds(60)
        tracker.onPage(7, 40_000, progress = 0.4f)
        advanceUntilIdle()

        assertEquals(null, store.characters[7L to DAY])
        assertEquals(0, store.read.size)
        assertEquals(40_000L, store.positions[7])

        // The next page is measured from there.
        seconds(20)
        tracker.onPage(7, 40_300)
        advanceUntilIdle()
        assertEquals(300L, store.characters[7L to DAY])
    }

    @Test
    fun aNewBookStartsAtTheBeginning() = runTest {
        val tracker = tracker()
        tracker.setForeground(true)
        tracker.setVisible(true)
        tracker.setBook(9)
        minutes(5)
        tracker.setVisible(false)
        tracker.onPositions(mapOf(9L to 2_000L))
        advanceUntilIdle()

        assertEquals(2_000L, store.characters[9L to DAY])
    }

    @Test
    fun timeIsWrittenEveryMinuteWhileReading() = runTest {
        val tracker = tracker()
        tracker.setForeground(true)
        tracker.setVisible(true)
        tracker.setBook(7)
        minutes(2)
        tracker.onActivity()
        advanceUntilIdle()

        assertEquals(2 * 60L, store.seconds[7L to DAY])
    }

    @Test
    fun partsOfASecondAddUpAcrossWrites() = runTest {
        val tracker = tracker()
        tracker.setForeground(true)
        tracker.setBook(7)
        // Ten short looks at the book, 1.5 s each, every one written when the reader is covered.
        repeat(10) {
            tracker.setVisible(true)
            now += 1_500
            tracker.setVisible(false)
        }
        advanceUntilIdle()

        assertEquals(15L, store.seconds[7L to DAY])
    }

    private companion object {
        /** 2026-10-17 08:00 UTC. */
        const val DAY_START = 1_792_224_000_000L
        const val DAY = "2026-10-17"
    }
}
