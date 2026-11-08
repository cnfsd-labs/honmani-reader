package app.honmani.reader.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.xml.sax.SAXException

class SafeXmlTest {
    @get:Rule val folder = TemporaryFolder()

    private fun parse(xml: String) = SafeXml.parse(xml.toByteArray(Charsets.UTF_8))

    @Test
    fun parsesNamespacedXml() {
        val root = parse(
            """<?xml version="1.0" encoding="UTF-8"?>
            <feed xmlns="http://www.w3.org/2005/Atom" xmlns:dc="http://purl.org/dc/elements/1.1/">
              <title>本</title><dc:creator>A &amp; B</dc:creator>
            </feed>""",
        )
        assertEquals("feed", root.localName)
        assertEquals("http://www.w3.org/2005/Atom", root.namespaceURI)
        assertEquals("本", root.getElementsByTagNameNS("*", "title").item(0).textContent)
        assertEquals("A & B", root.getElementsByTagNameNS("http://purl.org/dc/elements/1.1/", "creator").item(0).textContent)
    }

    @Test
    fun rejectsADocumentTypeDeclaration() {
        assertRejected("""<!DOCTYPE a [<!ENTITY e "x">]><a>&e;</a>""")
        assertRejected("""<!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.1//EN" "http://www.w3.org/TR/xhtml11/DTD/xhtml11.dtd"><html/>""")
    }

    @Test
    fun doesNotReadLocalFilesThroughAnExternalEntity() {
        val secret = folder.newFile("secret.txt").apply { writeText("TOP-SECRET") }
        val xml = """<?xml version="1.0"?><!DOCTYPE a [<!ENTITY e SYSTEM "${secret.toURI()}">]><a>&e;</a>"""
        try {
            val text = parse(xml).textContent
            assertFalse(text, "TOP-SECRET" in text)
        } catch (_: SAXException) {
            // Rejected outright: also safe.
        }
    }

    @Test
    fun doesNotExpandEntitiesBombs() {
        val xml = """<!DOCTYPE lolz [<!ENTITY a "aaaaaaaaaa"><!ENTITY b "&a;&a;&a;&a;&a;&a;&a;&a;&a;&a;">]><lolz>&b;</lolz>"""
        try {
            assertTrue(parse(xml).textContent.length < 1000)
        } catch (_: SAXException) {
        }
    }

    @Test
    fun throwsSaxExceptionForNonXml() {
        for (bad in listOf("", "not xml", "<a><b></a>", "<a>")) {
            try {
                parse(bad)
                fail("accepted: $bad")
            } catch (_: SAXException) {
            }
        }
    }

    private fun assertRejected(xml: String) {
        try {
            parse(xml)
            fail("accepted: $xml")
        } catch (_: SAXException) {
        }
    }
}
