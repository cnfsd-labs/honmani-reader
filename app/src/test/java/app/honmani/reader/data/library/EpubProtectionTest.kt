package app.honmani.reader.data.library

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class EpubProtectionTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val encrypted = ByteArray(4000) { (it * 37 + 11).toByte() }
    private val page = "<?xml version=\"1.0\"?><html><body><p>吾輩は猫である。</p></body></html>".toByteArray()

    private fun encryption(vararg uris: String): ByteArray = uris.joinToString(
        "",
        """<?xml version="1.0"?><encryption xmlns="urn:oasis:names:tc:opendocument:xmlns:container" xmlns:enc="http://www.w3.org/2001/04/xmlenc#">""",
        "</encryption>",
    ) {
        """<enc:EncryptedData><enc:EncryptionMethod Algorithm="http://www.w3.org/2001/04/xmlenc#aes128-cbc"/>
           <enc:CipherData><enc:CipherReference URI="$it"/></enc:CipherData></enc:EncryptedData>"""
    }.toByteArray()

    private fun book(files: Map<String, ByteArray>): File =
        folder.newFile().apply { writeBytes(TestBooks.epub(files = files)) }

    @Test
    fun encryptedPagesAreCopyProtection() {
        assertTrue(isCopyProtected(book(mapOf("META-INF/encryption.xml" to encryption("OEBPS/ch1.xhtml"), "OEBPS/ch1.xhtml" to encrypted))))
    }

    @Test
    fun scrambledFontsAreNot() {
        val files = mapOf("META-INF/encryption.xml" to encryption("OEBPS/a.otf"), "OEBPS/a.otf" to encrypted, "OEBPS/ch1.xhtml" to page)
        assertFalse(isCopyProtected(book(files)))
    }

    @Test
    fun aListLeftOverInADecryptedBookIsNot() {
        assertFalse(isCopyProtected(book(mapOf("META-INF/encryption.xml" to encryption("OEBPS/ch1.xhtml"), "OEBPS/ch1.xhtml" to page))))
        // Saved as UTF-16 with a byte order mark.
        val utf16 = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + String(page).toByteArray(Charsets.UTF_16LE)
        assertFalse(isCopyProtected(book(mapOf("META-INF/encryption.xml" to encryption("OEBPS/ch1.xhtml"), "OEBPS/ch1.xhtml" to utf16))))
    }

    @Test
    fun aBookWithoutAListOrAFileThatIsNoZipIsNot() {
        assertFalse(isCopyProtected(book(mapOf("OEBPS/ch1.xhtml" to page))))
        assertFalse(isCopyProtected(folder.newFile().apply { writeText("PK no zip") }))
    }
}
