package app.honmani.reader.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** The times in a sync file: the latest one, and setting the ones past a ceiling back. */
class SyncStampsTest {
    private val ceiling = 10_000L

    private fun word(updatedAt: Long, lookedUpAt: Long) = BackupWord(
        expression = "猫", reading = "ねこ", gloss = "cat", status = "NEW", lookupCount = 1, firstLookedUpAt = 1,
        lastLookedUpAt = 1, rankKey = 1.0, lastSentence = "", lastSurfaceStart = 0, lastSurfaceEnd = 1, updatedAt = updatedAt,
        lookups = listOf(BackupWordLookup("猫", "s", 0, 1, lookedUpAt = lookedUpAt)),
    )

    private fun file(updatedAt: Long = 5, deletedAt: Long = 6, folderTime: Long = 7, settingAt: Long = 8, lookedUpAt: Long = 50_000) = BackupFile(
        exportedAt = 9,
        words = listOf(word(updatedAt, lookedUpAt)),
        tombstones = listOf(BackupTombstone("word", "k", deletedAt)),
        folders = listOf("F"),
        folderTimes = mapOf("F" to folderTime),
        syncSettings = mapOf("s" to BackupSyncSetting(1, settingAt)),
    )

    @Test
    fun latestIsTheLargestEditOrDeletionTime() {
        assertEquals(9L, SyncStamps.latest(file()))
        assertEquals(70_000L, SyncStamps.latest(file(updatedAt = 70_000)))
        assertEquals(80_000L, SyncStamps.latest(file(deletedAt = 80_000)))
        assertEquals(90_000L, SyncStamps.latest(file(folderTime = 90_000)))
        assertEquals(95_000L, SyncStamps.latest(file(settingAt = 95_000)))
    }

    @Test
    fun momentsOfLookupsAreHistoryNotEdits() {
        assertEquals(9L, SyncStamps.latest(file(lookedUpAt = 999_999)))
    }

    @Test
    fun clampSetsEveryTimePastTheCeilingBackAndKeepsTheRest() {
        val clamped = SyncStamps.clamp(file(updatedAt = 70_000, deletedAt = 80_000, folderTime = 90_000, settingAt = 95_000), ceiling)

        assertEquals(ceiling, clamped.words.single().updatedAt)
        assertEquals(ceiling, clamped.tombstones.single().deletedAt)
        assertEquals(ceiling, clamped.folderTimes.getValue("F"))
        assertEquals(ceiling, clamped.syncSettings.getValue("s").at)
        assertEquals(9L, clamped.exportedAt)
        assertEquals(50_000L, clamped.words.single().lookups.single().lookedUpAt)
        assertEquals(1, clamped.syncSettings.getValue("s").value)
    }

    @Test
    fun aFileWithinTheCeilingIsReturnedAsItIs() {
        val file = file()
        assertSame(file, SyncStamps.clamp(file, ceiling))
    }
}
