package app.honmani.reader.data.library

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EpubMetadataTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun read(bytes: ByteArray): EpubMetadata {
        val file = File(folder.root, "book.epub")
        file.writeBytes(bytes)
        return EpubMetadata.read(file)
    }

    @Test
    fun readsTitleAndAuthor() {
        val metadata = read(TestBooks.epub(title = " 吾輩は\n  猫である ", author = "夏目漱石"))

        assertEquals("吾輩は 猫である", metadata.title)
        assertEquals("夏目漱石", metadata.author)
        assertNull(metadata.cover)
    }

    @Test
    fun missingTitleAndAuthorAreNull() {
        val metadata = read(TestBooks.epub(title = null, author = null))

        assertNull(metadata.title)
        assertNull(metadata.author)
    }

    @Test
    fun readsTheEpub3CoverImage() {
        val metadata = read(
            TestBooks.epub(
                manifestExtra = """<item id="c" href="images/cover%20art.png" media-type="image/png" properties="cover-image"/>""",
                files = mapOf("OEBPS/images/cover art.png" to TestBooks.PNG),
            ),
        )

        assertEquals("png", metadata.cover?.extension)
        assertArrayEquals(TestBooks.PNG, metadata.cover?.bytes)
    }

    @Test
    fun readsTheEpub2CoverMeta_relativeToThePackageDocument() {
        val metadata = read(
            TestBooks.epub(
                opfPath = "item/standard.opf",
                metadataExtra = """<meta name="cover" content="cover-img"/>""",
                manifestExtra = """<item id="cover-img" href="../image/cover.png" media-type="image/png"/>""",
                files = mapOf("image/cover.png" to TestBooks.PNG),
            ),
        )

        assertArrayEquals(TestBooks.PNG, metadata.cover?.bytes)
    }

    @Test
    fun aCoverThatIsNotAKnownImageIsSkipped() {
        val metadata = read(
            TestBooks.epub(
                manifestExtra = """<item id="c" href="cover.png" media-type="image/png" properties="cover-image"/>""",
                files = mapOf("OEBPS/cover.png" to "not an image".toByteArray()),
            ),
        )

        assertNull(metadata.cover)
        assertEquals("吾輩は猫である", metadata.title)
    }

    @Test
    fun aMissingCoverFileIsSkipped() {
        val metadata = read(
            TestBooks.epub(
                manifestExtra = """<item id="c" href="gone.png" media-type="image/png" properties="cover-image"/>""",
            ),
        )

        assertNull(metadata.cover)
    }

    @Test(expected = InvalidEpubException::class)
    fun aFileThatIsNotAZipIsInvalid() {
        read("吾輩は猫である".toByteArray())
    }

    @Test(expected = InvalidEpubException::class)
    fun aZipWithoutContainerIsInvalid() {
        read(TestBooks.zip(mapOf("hello.txt" to "hi".toByteArray())))
    }

    @Test(expected = InvalidEpubException::class)
    fun doctypesAreRejected() {
        // External entities must never be resolved from book files.
        val container = """<?xml version="1.0"?>
<!DOCTYPE container [<!ENTITY x SYSTEM "file:///etc/passwd">]>
<container><rootfiles><rootfile full-path="&x;"/></rootfiles></container>"""
        read(TestBooks.zip(mapOf("META-INF/container.xml" to container.toByteArray())))
    }

    @Test
    fun anOpfOtherReadersForgiveIsReadable() {
        val metadata = read(TestBooks.epub(title = "猫 & 犬", author = "A&hellip;B"))

        assertEquals("猫 & 犬", metadata.title)
        assertEquals("A…B", metadata.author)
    }
}
