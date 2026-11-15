package app.honmani.reader.data.opds

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StaleDownloadSweepTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun downloadsAKillInterruptedAreRemoved_oneRunningNowStays() {
        val dir = folder.newFolder("downloads")
        val start = 1_000_000_000_000L
        val left = File(dir, "a.part").apply { writeText("x"); setLastModified(start - 60_000) }
        val running = File(dir, "b.part").apply { writeText("x"); setLastModified(start + 5_000) }
        val other = File(dir, "c.epub").apply { writeText("x"); setLastModified(start - 60_000) }
        OpdsAutoImport.sweepStaleDownloads(dir, start)
        assertFalse(left.exists())
        assertTrue(running.exists())
        assertTrue(other.exists())
        // No folder yet: nothing to do.
        OpdsAutoImport.sweepStaleDownloads(File(dir, "missing"), start)
    }
}
