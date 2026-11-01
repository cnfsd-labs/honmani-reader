package app.honmani.reader.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import app.honmani.reader.data.settings.Accent
import app.honmani.reader.data.settings.customAccentColor
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeContrastTest {
    private val presets = Accent.entries.mapNotNull { accent -> accent.color?.let { accent to Color(it) } }
    private val customHues = (0 until 360 step 5).map { Color(customAccentColor(it.toFloat())) }

    private val cases = presets.map { (accent, color) -> accent.name to color } +
        customHues.mapIndexed { i, color -> "hue ${i * 5}" to color }

    @Test
    fun lightTheme_labelOnPrimary_reaches4point5() {
        for ((name, color) in cases) {
            val scheme = accentColorScheme(color, darkTheme = false)
            val ratio = contrastRatio(scheme.onPrimary, scheme.primary)
            assertTrue("$name: $ratio", ratio >= 4.5f)
        }
    }

    @Test
    fun lightTheme_primaryAsTextOnBackground_reaches4point5() {
        for ((name, color) in cases) {
            val scheme = accentColorScheme(color, darkTheme = false)
            val ratio = contrastRatio(scheme.primary, lightColorScheme().background)
            assertTrue("$name: $ratio", ratio >= 4.5f)
        }
    }

    @Test
    fun darkTheme_labelOnPrimaryAndPrimaryAsText_reach4point5() {
        for ((name, color) in cases) {
            val scheme = accentColorScheme(color, darkTheme = true)
            val label = contrastRatio(scheme.onPrimary, scheme.primary)
            val text = contrastRatio(scheme.primary, darkColorScheme().background)
            assertTrue("$name label: $label", label >= 4.5f)
            assertTrue("$name text: $text", text >= 4.5f)
        }
    }
}
