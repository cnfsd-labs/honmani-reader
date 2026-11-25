package app.honmani.reader.data.backup

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** The times in a sync file: the latest one, and setting the ones past a ceiling back. */
@OptIn(ExperimentalSerializationApi::class)
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

    /** Every number in the file that could be a time, with whether it is an edit time [SyncStamps] knows. */
    @Test
    fun everyTimeFieldOfTheFileIsEitherHandledOrHistory() {
        val edits = setOf(
            "exportedAt",
            "books.book.addedAt", "books.book.lastOpenedAt", "books.book.finishedAt", "books.book.updatedAt",
            "finishedBooks.finishedAt", "bookmarks.updatedAt", "highlights.updatedAt", "pronunciations.updatedAt",
            "words.lastLookedUpAt", "words.updatedAt", "words.card.addedAt", "words.card.updatedAt",
            "tombstones.deletedAt", "folderTimes", "syncSettings.at",
        )
        val history = setOf(
            "removedBooks.removedAt", "bookmarks.createdAt", "highlights.createdAt", "pronunciations.createdAt",
            "lookupEvents.at", "words.firstLookedUpAt", "words.card.lastReviewAt", "words.lookups.at",
            "words.reviews.at", "knownBatches.createdAt",
        )
        val found = HashSet<String>()
        longs(BackupFile.serializer().descriptor, "", found)
        val times = found.filter { it.endsWith("At") || it.endsWith(".at") || it == "folderTimes" }.toSet()

        assertEquals(edits + history, times)
    }

    private fun longs(descriptor: SerialDescriptor, path: String, found: MutableSet<String>) {
        when (descriptor.kind) {
            PrimitiveKind.LONG -> found += path
            StructureKind.LIST, StructureKind.MAP -> longs(descriptor.getElementDescriptor(if (descriptor.kind == StructureKind.MAP) 1 else 0), path, found)
            StructureKind.CLASS -> for (i in 0 until descriptor.elementsCount) {
                val name = descriptor.getElementName(i)
                longs(descriptor.getElementDescriptor(i), if (path.isEmpty()) name else "$path.$name", found)
            }
            else -> Unit
        }
    }
}
