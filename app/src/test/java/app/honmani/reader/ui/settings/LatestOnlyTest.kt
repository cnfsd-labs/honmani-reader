package app.honmani.reader.ui.settings

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LatestOnlyTest {
    @Test
    fun aNewLaunchCancelsTheRunningWork() = runTest {
        val latest = LatestOnly(this)
        val results = mutableListOf<String>()
        var firstCancelled = false
        latest.launch({
            try {
                CompletableDeferred<String>().await()
            } catch (e: kotlinx.coroutines.CancellationException) {
                firstCancelled = true
                throw e
            }
        }, { results += it })
        advanceUntilIdle()

        latest.launch({ "second" }, { results += it })
        advanceUntilIdle()

        assertEquals(true, firstCancelled)
        assertEquals(listOf("second"), results)
    }

    @Test
    fun aStaleResultFromWorkThatIgnoresCancellationIsDropped() = runTest {
        val latest = LatestOnly(this)
        val results = mutableListOf<String>()
        val release = CompletableDeferred<Unit>()
        // Like a blocking request: it finishes with a result even though it was cancelled.
        latest.launch({ withContext(NonCancellable) { release.await(); "first" } }, { results += it })
        advanceUntilIdle()

        latest.launch({ "second" }, { results += it })
        advanceUntilIdle()
        release.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf("second"), results)
    }
}
