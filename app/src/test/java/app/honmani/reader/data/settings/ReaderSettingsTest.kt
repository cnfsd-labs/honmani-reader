package app.honmani.reader.data.settings

import app.honmani.reader.data.words.WordMarksMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
                "honmaniEdgeTaps" to "1",
                "honmaniEdgeZone" to "wide",
                "honmaniEdgeForward" to "1",
                "honmaniSelectionLookup" to "0",
                "honmaniHideKnownFurigana" to "1",
                "honmaniTintOneUnknown" to "1",
                "honmaniImmersive" to "1",
                "honmaniEInk" to "1",
            ),
        )

        assertEquals(
            ReaderSettings(ReaderTheme.SEPIA, ReaderFont.SANS.storage, 32, 1.8f, vertical = false, paginated = false, furigana = false, showProgress = false, progressBar = false, wordMarks = WordMarksMode.NEW, keepScreenOn = true, volumeKeys = false, edgeTaps = true, edgeZone = EdgeZone.WIDE, edgeForward = true, selectionLookup = false, hideKnownFurigana = true, tintOneUnknown = true, immersive = true, eInk = true),
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
        assertEquals("0", stored["honmaniEdgeTaps"])
        // Medium is the width edge taps always had; an unknown value means it too.
        assertEquals("medium", stored["honmaniEdgeZone"])
        assertEquals("0", stored["honmaniEdgeForward"])
        assertEquals(EdgeZone.MEDIUM, ReaderSettings.fromStorage(mapOf("honmaniEdgeZone" to "huge")).edgeZone)
        assertFalse(ReaderSettings.fromStorage(emptyMap()).edgeForward)
        assertEquals("1", stored["honmaniSelectionLookup"])
        assertEquals("0", stored["honmaniHideKnownFurigana"])
        assertEquals("0", stored["honmaniTintOneUnknown"])
        // Immersive mode is off unless the reader turned it on; it hides the bars over a book only.
        assertEquals("0", stored["honmaniImmersive"])
        assertFalse(ReaderSettings.fromStorage(emptyMap()).immersive)
        assertEquals("0", stored["honmaniEInk"])
        assertFalse(ReaderSettings.fromStorage(emptyMap()).eInk)
        val immersive = ReaderSettings(immersive = true)
        assertTrue(immersive.hidesBars(atLibrary = false, settingsOpen = false))
        assertFalse(immersive.hidesBars(atLibrary = true, settingsOpen = false))
        assertFalse(immersive.hidesBars(atLibrary = false, settingsOpen = true))
        assertFalse(ReaderSettings().hidesBars(atLibrary = false, settingsOpen = false))
        // The due mark is on unless the reader turned it off.
        assertEquals("1", stored["honmaniMarkDue"])
        assertTrue(ReaderSettings.fromStorage(emptyMap()).markDue)
        assertFalse(ReaderSettings.fromStorage(mapOf("honmaniMarkDue" to "0")).markDue)
        assertEquals(settings, ReaderSettings.fromStorage(stored))
        assertEquals("1.65", ReaderSettings().toStorage()["lineHeight"])
    }

    @Test
    fun edgeTapOptionsRoundTrip() {
        val settings = ReaderSettings(edgeTaps = true, edgeZone = EdgeZone.NARROW, edgeForward = true)
        assertEquals(settings, ReaderSettings.fromStorage(settings.toStorage()))
    }

    @Test
    fun eInkModeRoundTrips() {
        val settings = ReaderSettings(eInk = true)
        assertEquals("1", settings.toStorage()["honmaniEInk"])
        assertEquals(settings, ReaderSettings.fromStorage(settings.toStorage()))
    }

    @Test
    fun customCssIsEmptyByDefault_andRoundTrips() {
        assertEquals("", ReaderSettings.fromStorage(emptyMap()).customCss)
        assertEquals("", ReaderSettings().toStorage()["honmaniCustomCss"])
        val settings = ReaderSettings(customCss = "p { line-height: 3 }\n.x { color: #333 }")
        assertEquals(settings, ReaderSettings.fromStorage(settings.toStorage()))
        // A stored value over the cap is not taken (the page would not use it either).
        val tooLong = "a".repeat(CustomCss.MAX_BYTES + 1)
        assertEquals("", ReaderSettings.fromStorage(mapOf("honmaniCustomCss" to tooLong)).customCss)
    }
}
