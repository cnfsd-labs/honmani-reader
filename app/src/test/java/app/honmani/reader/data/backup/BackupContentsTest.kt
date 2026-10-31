package app.honmani.reader.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupContentsTest {
    private fun details() = BackupBookDetails(title = "t", format = "EPUB", source = "LOCAL", addedAt = 1)

    private fun word(expression: String) = BackupWord(
        expression = expression, reading = "r", gloss = "g", status = "LEARNING", lookupCount = 1,
        firstLookedUpAt = 1, lastLookedUpAt = 1, rankKey = 1.0, lastSentence = "s", lastSurfaceStart = 0, lastSurfaceEnd = 1,
    )

    private val file = BackupFile(
        exportedAt = 1,
        readerSettings = mapOf("fontSize" to "30"),
        books = listOf(BackupBook("a", details()), BackupBook("b", details()), BackupBook("removed")),
        words = listOf(word("猫"), word("犬")),
        readingDays = listOf(
            BackupReadingDay("a", "2026-01-01", 10, 10),
            BackupReadingDay("b", "2026-01-01", 10, 10),
            BackupReadingDay("a", "2026-01-02", 10, 10),
        ),
        catalogs = listOf(
            BackupCatalog("Home", "http://nas.local:8080/opds"),
            BackupCatalog("Odd", "not a url"),
        ),
    )

    @Test
    fun theContentsCountWhatTheFileHolds() {
        val contents = file.contents()
        assertEquals(2, contents.books)
        assertEquals(2, contents.words)
        assertEquals(2, contents.readingDays)
        assertTrue(contents.hasReaderSettings)
        assertEquals(listOf(BackupCatalogInfo("Home", "nas.local"), BackupCatalogInfo("Odd", "not a url")), contents.catalogs)
    }

    @Test
    fun noReaderSettingsAreReportedAsNone() {
        assertFalse(file.copy(readerSettings = null).contents().hasReaderSettings)
        assertFalse(file.copy(readerSettings = emptyMap()).contents().hasReaderSettings)
    }

    @Test
    fun leavingOutCatalogsKeepsTheRest() {
        val without = file.withoutCatalogs()
        assertTrue(without.catalogs.isEmpty())
        assertEquals(file.words, without.words)
    }

    @Test
    fun badReaderValuesAreDropped() {
        val valid = ReaderSettingsCheck.valid(
            mapOf(
                "fontSize" to "99999",
                "lineHeight" to "NaN",
                "writingMode" to "sideways",
                "viewMode" to "paginated",
                "theme" to "black-theme",
                "hideFurigana" to "yes",
                "showPercentage" to "1",
                "fontFamilyGroupOne" to "serif; } body { display:none",
                "honmaniWordMarks" to "nonsense",
                "unknownKey" to "1",
                "enableReaderWakeLock" to null,
            ),
        )
        assertEquals(mapOf("viewMode" to "paginated", "theme" to "black-theme", "showPercentage" to "1"), valid)
    }

    @Test
    fun sensibleReaderValuesPass() {
        val values = mapOf("fontSize" to "28", "lineHeight" to "1.65", "fontFamilyGroupOne" to "Honmani Klee One", "writingMode" to "vertical-rl")
        assertEquals(values, ReaderSettingsCheck.valid(values))
    }

    @Test
    fun honmaniSwitchesPassOnlyAsZeroOrOne() {
        val valid = ReaderSettingsCheck.valid(
            mapOf("honmaniSelectionLookup" to "0", "honmaniVolumeKeys" to "1", "honmaniHideKnownFurigana" to "maybe", "honmaniWordMarks" to "off"),
        )
        assertEquals(mapOf("honmaniSelectionLookup" to "0", "honmaniVolumeKeys" to "1", "honmaniWordMarks" to "off"), valid)
    }
}
