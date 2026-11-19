package app.honmani.reader.ui.reader

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Assert.assertEquals
import org.junit.Test

class MessageOrderTest {
    @Test
    fun aMessageAfterAPictureWaitsForIt() {
        val scope = TestScope(StandardTestDispatcher())
        val order = MessageOrder(scope)
        val handled = mutableListOf<String>()
        val parsed = CompletableDeferred<String>()
        order.later({ parsed.await() }) { handled += it }
        order.now { handled += "dismiss" }
        scope.advanceUntilIdle()
        assertEquals(emptyList<String>(), handled)
        parsed.complete("picture")
        scope.advanceUntilIdle()
        assertEquals(listOf("picture", "dismiss"), handled)
    }

    @Test
    fun withNothingPending_handlesAtOnce() {
        val scope = TestScope(StandardTestDispatcher())
        val order = MessageOrder(scope)
        val handled = mutableListOf<String>()
        order.later({ "picture" }) { handled += it }
        scope.advanceUntilIdle()
        order.now { handled += "lookup" }
        assertEquals(listOf("picture", "lookup"), handled)
    }

    @Test
    fun anUnreadablePictureDoesNotHoldUpLaterMessages() {
        val scope = TestScope(StandardTestDispatcher())
        val order = MessageOrder(scope)
        val handled = mutableListOf<String>()
        order.later({ null as String? }) { handled += it }
        order.now { handled += "lookup" }
        scope.advanceUntilIdle()
        assertEquals(listOf("lookup"), handled)
    }
}
