package app.honmani.reader.data.net

import app.honmani.reader.anki.GoogleCloudVoice
import app.honmani.reader.data.dictionary.DictionaryCatalog
import app.honmani.reader.data.fonts.FontCatalog
import java.io.File
import java.net.URI
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * Plain http and the user's own CAs are for home servers; the public services Honmani downloads
 * from itself stay on https with public certificates, including any added later.
 */
class NetworkSecurityConfigTest {
    private val config = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        .parse(File("src/main/res/xml/network_security_config.xml")).documentElement

    private val publicDomains: Element =
        config.getElementsByTagName("domain-config").item(0) as Element

    private val domains = publicDomains.getElementsByTagName("domain").let { list ->
        (0 until list.length).map { (list.item(it) as Element).textContent.trim() }
    }

    @Test
    fun everyBuiltInDownloadIsAPublicDomain() {
        val urls = DictionaryCatalog.dictionaries.map { it.url } +
            FontCatalog.fonts.map { it.url } +
            GoogleCloudVoice.BASE_URL
        for (url in urls) {
            val host = URI(url).host
            assertTrue("$host is not in the domain-config", domains.any { host == it || host.endsWith(".$it") })
        }
    }

    @Test
    fun publicDomainsAllowNeitherHttpNorUserCas() {
        assertEquals("false", publicDomains.getAttribute("cleartextTrafficPermitted"))
        val anchors = publicDomains.getElementsByTagName("certificates").let { list ->
            (0 until list.length).map { (list.item(it) as Element).getAttribute("src") }
        }
        assertEquals(listOf("system"), anchors)
    }
}
