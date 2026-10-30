package app.honmani.reader.data.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OriginTest {
    @Test
    fun spellsOutSchemeHostAndDefaultPort() {
        assertEquals("http://nas:80", originOf("HTTP://NAS/opds"))
        assertEquals("https://books.example:443", originOf(" https://books.example/a/b "))
        assertEquals("http://nas:8083", originOf("http://nas:8083/opds"))
    }

    @Test
    fun relativeOrBrokenUrlsHaveNoOrigin() {
        assertNull(originOf("/opds"))
        assertNull(originOf("http://bad host/"))
        assertNull(originOf("file:///books"))
    }
}
