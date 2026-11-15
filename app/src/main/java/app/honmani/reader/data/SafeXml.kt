package app.honmani.reader.data

import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.xml.sax.InputSource
import org.xml.sax.SAXException

/**
 * XML parsing for untrusted input (book files, catalogue feeds): no entity declarations, no
 * external entities. A plain DOCTYPE (as in NCX files) is allowed; its DTD is never fetched.
 */
object SafeXml {
    /**
     * Parses [bytes] namespace-aware; throws [org.xml.sax.SAXException] if they are not XML or
     * declare an entity ([UnsupportedXmlException]).
     */
    fun parse(bytes: ByteArray): Element {
        // Android's parser ignores the entity features below, so an entity "bomb" (billion
        // laughs) would expand until memory runs out: refuse any entity declaration up front.
        if (declaresEntity(bytes)) throw UnsupportedXmlException()
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            trySetFeature("http://xml.org/sax/features/external-general-entities", false)
            trySetFeature("http://xml.org/sax/features/external-parameter-entities", false)
            isExpandEntityReferences = false
        }
        val builder = factory.newDocumentBuilder().apply {
            setEntityResolver { _, _ -> InputSource(StringReader("")) }
            setErrorHandler(null)
        }
        return builder.parse(bytes.inputStream()).documentElement
    }

    /**
     * Whether [bytes] contain `<!ENTITY` (XML keywords are case-sensitive). Zero bytes are skipped
     * so UTF-16 and UTF-32 text matches too. Entities can only be declared in a DOCTYPE's internal
     * subset; elsewhere the text is a parse error anyway or sits in a comment or CDATA section, so a
     * whole-document scan refuses no valid file that matters.
     */
    internal fun declaresEntity(bytes: ByteArray): Boolean {
        var matched = 0
        for (b in bytes) {
            if (b.toInt() == 0) continue
            matched = when {
                b == ENTITY_DECL[matched] -> matched + 1
                b == ENTITY_DECL[0] -> 1
                else -> 0
            }
            if (matched == ENTITY_DECL.size) return true
        }
        return false
    }

    private val ENTITY_DECL = "<!ENTITY".toByteArray(Charsets.US_ASCII)

    private fun DocumentBuilderFactory.trySetFeature(name: String, value: Boolean) {
        try {
            setFeature(name, value)
        } catch (_: Exception) {
            // Not every parser knows every feature; the entity resolver still blocks fetches.
        }
    }
}

/** Thrown for XML that declares entities, which Honmani refuses to protect memory. */
class UnsupportedXmlException : SAXException("This file uses XML features Honmani does not read.")
