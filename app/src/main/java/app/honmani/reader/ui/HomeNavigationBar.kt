package app.honmani.reader.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.honmani.reader.R

/**
 * The home screen's sections, switched with the bar at the bottom (within thumb reach), or at
 * the side in landscape ([HomeNavigationRail]), in bar order: most used first.
 */
enum class HomeTab(
    @StringRes val label: Int,
    val vector: ImageVector? = null,
    /**
     * A kanji drawn as the icon: 本 (book) for the library, 語 (from 単語, word) for words, 進
     * (from 進歩, progress) for progress.
     */
    val glyph: String? = null,
) {
    LIBRARY(R.string.nav_library, glyph = "本"),
    WORDS(R.string.nav_words, glyph = "語"),
    PROGRESS(R.string.nav_progress, glyph = "進"),
    SETTINGS(R.string.nav_settings, vector = Icons.Filled.Settings),
}

@Composable
private fun HomeTab.TabIcon() {
    if (glyph != null) {
        // Icon-sized whatever the font size, like the drawn icons beside it.
        val size = with(LocalDensity.current) { GLYPH_SIZE.toSp() }
        Box(modifier = Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            Text(glyph, fontSize = size, lineHeight = size, fontWeight = FontWeight.Medium)
        }
    } else {
        Icon(rememberVectorPainter(vector!!), contentDescription = null)
    }
}

/** [badges]: tabs with something new, such as dictionary updates, marked with a dot. */
@Composable
fun HomeNavigationBar(selected: HomeTab, onSelect: (HomeTab) -> Unit, badges: Set<HomeTab> = emptySet()) {
    NavigationBar {
        // A little inset at both ends, so the outer labels don't sit at the screen edge.
        Spacer(modifier = Modifier.width(EDGE_INSET))
        for (tab in HomeTab.entries) {
            NavigationBarItem(
                selected = tab == selected,
                onClick = { onSelect(tab) },
                icon = {
                    BadgedBox(badge = { if (tab in badges) Badge() }) {
                        tab.TabIcon()
                    }
                },
                label = { Text(stringResource(tab.label)) },
            )
        }
        Spacer(modifier = Modifier.width(EDGE_INSET))
    }
}

/**
 * The tabs at the side, for landscape: a bar at the bottom took a quarter of a phone's height
 * there. Centred top to bottom, so the thumb reaches every tab.
 */
@Composable
fun HomeNavigationRail(selected: HomeTab, onSelect: (HomeTab) -> Unit, badges: Set<HomeTab> = emptySet()) {
    NavigationRail {
        Spacer(modifier = Modifier.weight(1f))
        for (tab in HomeTab.entries) {
            NavigationRailItem(
                selected = tab == selected,
                onClick = { onSelect(tab) },
                icon = {
                    BadgedBox(badge = { if (tab in badges) Badge() }) {
                        tab.TabIcon()
                    }
                },
                label = { Text(stringResource(tab.label)) },
            )
        }
        Spacer(modifier = Modifier.weight(1f))
    }
}

private val EDGE_INSET = 16.dp

private val GLYPH_SIZE = 21.dp
