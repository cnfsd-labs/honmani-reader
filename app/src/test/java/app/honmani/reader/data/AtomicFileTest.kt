package app.honmani.reader.data

import java.io.File
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AtomicFileTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun writesNewContentAndRemovesTheTemporaryFile() {
        val file = File(folder.root, "sub/data.txt")
        assertTrue(writeTextAtomically(file, "one"))
        assertTrue(writeTextAtomically(file, "two"))
        assertEquals("two", file.readText())
        assertFalse(File(file.path + ".tmp").exists())
    }

    @Test
    fun failedRenameReportsFalseAndRemovesTheTemporaryFile() {
        // A non-empty directory cannot be replaced by a file.
        val target = folder.newFolder("target")
        File(target, "inner").writeText("x")
        assertFalse(writeTextAtomically(target, "new"))
        assertTrue(File(target, "inner").exists())
        assertFalse(File(target.path + ".tmp").exists())
    }

    @Test
    fun failedWriteLeavesTheOldContentAndNoTemporaryFile() {
        val file = folder.newFile("data.txt").apply { writeText("old") }
        try {
            writeAtomically(file) {
                it.writeText("partial")
                throw IOException("disk full")
            }
            fail("expected an exception")
        } catch (_: IOException) {
        }
        assertEquals("old", file.readText())
        assertFalse(File(file.path + ".tmp").exists())
    }

    @Test
    fun readStoredFallsBackToTheDefaultForMissingAndCorruptFiles() {
        assertEquals(7, readStored(File(folder.root, "none"), 7) { it.readText().toInt() })
        val corrupt = folder.newFile("bad").apply { writeText("abc") }
        assertEquals(7, readStored(corrupt, 7) { it.readText().toInt() })
        val good = folder.newFile("good").apply { writeText("5") }
        assertEquals(5, readStored(good, 7) { it.readText().toInt() })
    }
}
