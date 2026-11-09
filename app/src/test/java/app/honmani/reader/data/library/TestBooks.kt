package app.honmani.reader.data.library

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Builds small EPUBs for tests. */
object TestBooks {
    private const val FIXED_TIME = 1_700_000_000_000L

    /** A 1×1 PNG. */
    val PNG: ByteArray = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
        0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01, 0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15,
        0xC4.toByte(), 0x89.toByte(),
        0x00, 0x00, 0x00, 0x0A, 0x49, 0x44, 0x41, 0x54, 0x78, 0x9C.toByte(), 0x63, 0x00, 0x01, 0x00,
        0x00, 0x05, 0x00, 0x01, 0x0D, 0x0A, 0x2D, 0xB4.toByte(),
        0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4E, 0x44, 0xAE.toByte(), 0x42, 0x60, 0x82.toByte(),
    )

    fun epub(
        title: String? = "吾輩は猫である",
        author: String? = "夏目漱石",
        opfPath: String = "OEBPS/content.opf",
        metadataExtra: String = "",
        manifestExtra: String = "",
        files: Map<String, ByteArray> = emptyMap(),
    ): ByteArray {
        val opf = """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:identifier id="id">urn:uuid:1</dc:identifier>
    ${title?.let { "<dc:title>$it</dc:title>" }.orEmpty()}
    ${author?.let { "<dc:creator>$it</dc:creator>" }.orEmpty()}
    $metadataExtra
  </metadata>
  <manifest>
    <item id="ch1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
    $manifestExtra
  </manifest>
  <spine><itemref idref="ch1"/></spine>
</package>"""
        val container = """<?xml version="1.0"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles><rootfile full-path="$opfPath" media-type="application/oebps-package+xml"/></rootfiles>
</container>"""
        return zip(
            mapOf(
                "mimetype" to "application/epub+zip".toByteArray(),
                "META-INF/container.xml" to container.toByteArray(),
                opfPath to opf.toByteArray(),
            ) + files,
        )
    }

    fun zip(entries: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, bytes) in entries) {
                // A fixed time: the same book built twice must hash the same (a ZipEntry without a
                // time takes the clock, so two builds across a 2 s tick were different files).
                zip.putNextEntry(ZipEntry(name).apply { time = FIXED_TIME })
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }
}
