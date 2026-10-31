package app.honmani.reader.data.settings

import app.honmani.reader.data.words.WordMarksMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReaderSettingsTest {
    @Test
    fun nothingStoredMeansTheDefaults() {
        assertEquals(ReaderSettings(), ReaderSettings.fromStorage(emptyMap()))
        assertEquals(ReaderSettings(), ReaderSettings.fromStorage(mapOf("theme" to null, "fontSize" to null)))
    }

    @Test
    fun readsWhatTtuStores() {
        val settings = ReaderSettings.fromStorage(
            mapOf(
                "theme" to "ecru-theme",
                "fontFamilyGroupOne" to "sans-serif",
                "fontSize" to "32",
                "lineHeight" to "1.8",
                "writingMode" to "horizontal-tb",
                "viewMode" to "continuous",
                "hideFurigana" to "1",
                "showCharacterCounter" to "0",
                "showPercentage" to "0",
                "enableReaderWakeLock" to "1",
                "honmaniProgressBar" to "0",
                "honmaniWordMarks" to "new",
                "honmaniVolumeKeys" to "0",
                "honmaniSelectionLookup" to "0",
                "honmaniHideKnownFurigana" to "1",
                "honmaniTintOneUnknown" to "1",
            ),
        )

        assertEquals(
            ReaderSettings(ReaderTheme.SEPIA, ReaderFont.SANS.storage, 32, 1.8f, vertical = false, paginated = false, furigana = false, showProgress = false, progressBar = false, wordMarks = WordMarksMode.NEW, keepScreenOn = true, volumeKeys = false, selectionLookup = false, hideKnownFurigana = true, tintOneUnknown = true),
            settings,
        )
    }

    @Test
    fun othersPickedInTtusFullSettingsAreLeftAlone() {
        val settings = ReaderSettings.fromStorage(mapOf("theme" to "water-theme", "fontFamilyGroupOne" to "Klee One"))

        assertNull(settings.theme)
        // Not written back, so saving another setting keeps it.
        assertNull(settings.toStorage()["theme"])
        // A font is kept as stored (a downloaded one or ttu's), so writing it back changes nothing.
        assertEquals("Klee One", settings.font)
        assertEquals("Klee One", settings.toStorage()["fontFamilyGroupOne"])
    }

    @Test
    fun writesAsTtuDoes_andReadsBackTheSame() {
        val settings = ReaderSettings(ReaderTheme.AUTO, ReaderFont.SERIF.storage, 24, 2.0f, vertical = true, paginated = true, furigana = true, showProgress = true, keepScreenOn = false)
        val stored = settings.toStorage()

        assertEquals("auto", stored["theme"])
        assertEquals("2", stored["lineHeight"])
        assertEquals("vertical-rl", stored["writingMode"])
        assertEquals("0", stored["hideFurigana"])
        assertEquals("1", stored["honmaniProgressBar"])
        assertEquals("learning", stored["honmaniWordMarks"])
        assertEquals("1", stored["honmaniVolumeKeys"])
        assertEquals("1", stored["honmaniSelectionLookup"])
        assertEquals("0", stored["honmaniHideKnownFurigana"])
        assertEquals("0", stored["honmaniTintOneUnknown"])
        assertEquals(settings, ReaderSettings.fromStorage(stored))
        assertEquals("1.65", ReaderSettings().toStorage()["lineHeight"])
    }
}
