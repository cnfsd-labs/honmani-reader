package app.honmani.reader.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import app.honmani.reader.data.settings.Accent
import app.honmani.reader.data.settings.customAccentColor

@Composable
fun HonmaniTheme(
    accent: Accent = Accent.DEFAULT,
    customHue: Float = 0f,
    darkTheme: Boolean = isSystemInDarkTheme(),
    eInk: Boolean = false,
    content: @Composable () -> Unit,
) {
    val color = if (accent == Accent.CUSTOM) customAccentColor(customHue) else accent.color
    val colorScheme = when {
        color != null -> accentColorScheme(Color(color), darkTheme)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        else -> accentColorScheme(Color(Accent.DEFAULT.color!!), darkTheme)
    }
    val animationsOn = motionEnabled(eInk, rememberAnimatorScale())
    CompositionLocalProvider(LocalMotionEnabled provides animationsOn) {
        MaterialTheme(colorScheme = colorScheme, content = content)
    }
}

/**
 * A colour scheme around one accent colour: buttons, the selected tab, chips and progress bars use
 * it; the containers are the accent mixed with the background, so they stay calm.
 */
fun accentColorScheme(accent: Color, darkTheme: Boolean): ColorScheme {
    return if (darkTheme) {
        // Lighter on dark backgrounds until the accent as text reads at 4.5:1; the label on it is
        // black or white, whichever contrasts more (always at least 4.5:1).
        val accent = lightenForDarkBackground(accent)
        val onAccent = readableOn(accent)
        val container = lerp(accent, Color.Black, 0.55f)
        val onContainer = lerp(accent, Color.White, 0.8f)
        darkColorScheme(
            primary = accent,
            onPrimary = onAccent,
            primaryContainer = container,
            onPrimaryContainer = onContainer,
            secondary = accent,
            onSecondary = onAccent,
            secondaryContainer = container,
            onSecondaryContainer = onContainer,
            tertiary = accent,
            onTertiary = onAccent,
        )
    } else {
        // Darker on light backgrounds until white text on it (and it as text on the page) reads at 4.5:1.
        val primary = darkenForWhiteText(accent)
        val onPrimary = readableOn(primary)
        val container = lerp(accent, Color.White, 0.8f)
        val onContainer = lerp(accent, Color.Black, 0.6f)
        lightColorScheme(
            primary = primary,
            onPrimary = onPrimary,
            primaryContainer = container,
            onPrimaryContainer = onContainer,
            secondary = primary,
            onSecondary = onPrimary,
            secondaryContainer = container,
            onSecondaryContainer = onContainer,
            tertiary = primary,
            onTertiary = onPrimary,
        )
    }
}

/** WCAG contrast ratio of two opaque colours, 1 to 21. */
fun contrastRatio(a: Color, b: Color): Float {
    val l1 = a.luminance()
    val l2 = b.luminance()
    return (maxOf(l1, l2) + 0.05f) / (minOf(l1, l2) + 0.05f)
}

/** Black or white, whichever has the higher contrast against [background]. */
fun readableOn(background: Color): Color =
    if (contrastRatio(Color.White, background) >= contrastRatio(Color.Black, background)) Color.White else Color.Black

/** [accent] mixed toward white in 5 % steps until it has a contrast of 4.5:1 against the dark background. */
fun lightenForDarkBackground(accent: Color): Color {
    val background = darkColorScheme().background
    var amount = 0f
    var color = accent
    while (contrastRatio(color, background) < MIN_TEXT_CONTRAST && amount < 1f) {
        amount += 0.05f
        color = lerp(accent, Color.White, amount)
    }
    return color
}

/**
 * [accent] mixed toward black in 5 % steps, starting at 15 %, until it has a contrast of 4.5:1 against white
 * (button labels) and the light background (as text).
 */
fun darkenForWhiteText(accent: Color): Color {
    val background = lightColorScheme().background
    var amount = 0.15f
    var color = lerp(accent, Color.Black, amount)
    while (
        (contrastRatio(Color.White, color) < MIN_TEXT_CONTRAST || contrastRatio(color, background) < MIN_TEXT_CONTRAST) &&
        amount < 1f
    ) {
        amount += 0.05f
        color = lerp(accent, Color.Black, amount)
    }
    return color
}

private const val MIN_TEXT_CONTRAST = 4.5f
