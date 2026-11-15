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
    fun rejectsEntityDeclarations() {
        assertRejected("""<!DOCTYPE a [<!ENTITY e "x">]><a>&e;</a>""")
        assertRejected("""<!DOCTYPE a [<!ENTITY % p "x">]><a/>""")
        // UTF-16 text is scanned too.
        try {
            SafeXml.parse("""<?xml version="1.0" encoding="UTF-16"?><!DOCTYPE a [<!ENTITY e "x">]><a>&e;</a>""".toByteArray(Charsets.UTF_16))
            fail("accepted UTF-16 entity")
        } catch (_: UnsupportedXmlException) {
        }
    }

    @Test
    fun refusesABillionLaughsDocumentBeforeParsing() {
        val entities = (1..9).joinToString("") { i ->
            val prev = "&l${i - 1};"
            """<!ENTITY l$i "${prev.repeat(10)}">"""
        }
        val xml = """<?xml version="1.0"?><!DOCTYPE lolz [<!ENTITY l0 "lol">$entities]><lolz>&l9;</lolz>"""
        try {
            parse(xml)
            fail("accepted billion laughs")
        } catch (e: UnsupportedXmlException) {
            assertEquals("This file uses XML features Honmani does not read.", e.message)
        }
    }

    @Test
    fun parsesANcxWithAPlainDoctype() {
        val root = parse(
            """<?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE ncx PUBLIC "-//NISO//DTD ncx 2005-1//EN" "http://www.daisy.org/z3986/2005/ncx-2005-1.dtd">
            <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
              <docTitle><text>本</text></docTitle><navMap/>
            </ncx>""",
        )
        assertEquals("ncx", root.localName)
        assertEquals("本", root.getElementsByTagNameNS("*", "text").item(0).textContent)
    }

    @Test
    fun doesNotReadAnExternalDtd() {
        val dtd = folder.newFile("secret.dtd").apply { writeText("<!ENTITY e \"TOP-SECRET\">") }
        val xml = """<?xml version="1.0"?><!DOCTYPE a SYSTEM "${dtd.toURI()}"><a>&e;</a>"""
        try {
            val text = parse(xml).textContent
            assertFalse(text, "TOP-SECRET" in text)
        } catch (_: SAXException) {
            // Undefined entity: also safe.
        }
    }

    @Test
    fun allowsTheWordEntityInText() {
        assertFalse(SafeXml.declaresEntity("<a>&lt;!ENTITY is escaped; <!ENTIT></a>".toByteArray()))
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
