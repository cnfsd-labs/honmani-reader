package app.honmani.reader.ui.opds

import app.honmani.reader.data.opds.OpdsException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ThumbnailCacheTest {
    private val cache = ThumbnailCache<String>(2)
    private var requests = 0

    private suspend fun get(url: String, load: () -> String?) = cache.get(url) { requests++; load() }

    @Test
    fun aMissingCoverIsRequestedOnce() = runBlocking {
        assertNull(get("a") { throw OpdsException(OpdsException.Reason.NOT_A_CATALOGUE, "404") })
        assertNull(get("a") { "cover" })
        assertNull(get("b") { null })
        assertNull(get("b") { "cover" })
        assertEquals(2, requests)
    }

    @Test
    fun aConnectionFailureIsTriedAgain() = runBlocking {
        assertNull(get("a") { throw OpdsException(OpdsException.Reason.NETWORK, "offline") })
        assertEquals("cover", get("a") { "cover" })
        assertEquals("cover", get("a") { "other" })
        assertEquals(2, requests)
    }

    @Test
    fun failuresAreBounded() = runBlocking {
        for (url in listOf("a", "b", "c")) get(url) { null }
        assertEquals("cover", get("a") { "cover" })
        assertEquals(4, requests)
    }
}
