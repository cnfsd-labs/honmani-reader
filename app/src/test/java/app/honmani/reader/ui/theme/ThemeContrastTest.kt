package app.honmani.reader.ui.theme

import androidx.compose.ui.graphics.Color
import app.honmani.reader.data.settings.Accent
import app.honmani.reader.data.settings.customAccentColor
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeContrastTest {
    private val presets = Accent.entries.mapNotNull { accent -> accent.color?.let { accent to Color(it) } }
    private val customHues = (0 until 360 step 5).map { Color(customAccentColor(it.toFloat())) }

    @Test
    fun lightTheme_whiteOnPrimary_reaches4point5() {
        val cases = presets.map { (accent, color) -> accent.name to color } +
            customHues.mapIndexed { i, color -> "hue ${i * 5}" to color }
        for ((name, color) in cases) {
            val scheme = accentColorScheme(color, darkTheme = false)
            val ratio = contrastRatio(scheme.onPrimary, scheme.primary)
            assertTrue("$name: $ratio", ratio >= 4.5f)
        }
    }
}
