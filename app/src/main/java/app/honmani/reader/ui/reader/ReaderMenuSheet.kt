package app.honmani.reader.ui.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import java.text.NumberFormat
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.honmani.reader.R

/**
 * The reader's bottom menu, opened by a tap on the footer of the page or a long swipe up anywhere on it (`reader-menu.js`): the
 * table of contents, the search in the book, Honmani's reader settings, the words of this session, leaving the book and
 * the progress counter.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderMenuSheet(
    showProgress: Boolean,
    /** Why the last progress sync for this book failed ("server unreachable"); null when it did not. */
    syncFailure: String? = null,
    /** The reading position and the book's length in characters; null while the book is read for the slider. */
    place: MenuPlace? = null,
    /** The slider was let go at this many characters. */
    onJump: (Long) -> Unit = {},
    onAddBookmark: () -> Unit = {},
    onBookmarks: () -> Unit = {},
    onHighlights: () -> Unit = {},
    onContents: () -> Unit,
    onSearch: () -> Unit,
    onReadAloud: () -> Unit = {},
    /** How many Read aloud replacements this book has. */
    pronunciationCount: Int = 0,
    onPronunciations: () -> Unit = {},
    /** Whether this book has an audiobook (audio and subtitles) chosen. */
    audiobookPaired: Boolean = false,
    onAudiobook: () -> Unit = {},
    onSettings: () -> Unit,
    onSessionWords: () -> Unit,
    onCloseBook: () -> Unit,
    onSetShowProgress: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        val colors = ListItemDefaults.colors(containerColor = Color.Transparent)
        Column(modifier = Modifier.navigationBarsPadding().verticalScroll(rememberScrollState()).padding(bottom = 8.dp)) {
            syncFailure?.let {
                Text(
                    stringResource(R.string.reader_menu_sync_failed, it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            PlaceSlider(place, onJump)
            ListItem(
                leadingContent = { Icon(painterResource(R.drawable.ic_bookmark_add), contentDescription = null) },
                headlineContent = { Text(stringResource(R.string.reader_menu_add_bookmark)) },
                colors = colors,
                modifier = Modifier.clickable(enabled = place != null, onClick = onAddBookmark),
            )
            ListItem(
                leadingContent = { Icon(painterResource(R.drawable.ic_bookmark), contentDescription = null) },
                headlineContent = { Text(stringResource(R.string.reader_menu_bookmarks)) },
                colors = colors,
                modifier = Modifier.clickable(onClick = onBookmarks),
            )
            ListItem(
                leadingContent = { Icon(painterResource(R.drawable.ic_highlight), contentDescription = null) },
                headlineContent = { Text(stringResource(R.string.reader_menu_highlights)) },
                colors = colors,
                modifier = Modifier.clickable(onClick = onHighlights),
            )
            HorizontalDivider()
            ListItem(
                leadingContent = { Icon(Icons.Filled.Menu, contentDescription = null) },
                headlineContent = { Text(stringResource(R.string.reader_menu_contents)) },
                colors = colors,
                modifier = Modifier.clickable(onClick = onContents),
            )
            ListItem(
                leadingContent = { Icon(Icons.Filled.Search, contentDescription = null) },
                headlineContent = { Text(stringResource(R.string.reader_menu_search)) },
                colors = colors,
                modifier = Modifier.clickable(onClick = onSearch),
            )
            ListItem(
                leadingContent = { Icon(painterResource(R.drawable.ic_listen), contentDescription = null) },
                headlineContent = { Text(stringResource(R.string.reader_menu_read_aloud)) },
                colors = colors,
                modifier = Modifier.clickable(onClick = onReadAloud),
            )
            ListItem(
                leadingContent = { Icon(painterResource(R.drawable.ic_listen), contentDescription = null) },
                headlineContent = { Text(stringResource(R.string.reader_menu_pronunciations)) },
                supportingContent = {
                    Text(
                        if (pronunciationCount > 0) stringResource(R.string.reader_menu_pronunciations_count, pronunciationCount)
                        else stringResource(R.string.reader_menu_pronunciations_none),
                    )
                },
                colors = colors,
                modifier = Modifier.clickable(onClick = onPronunciations),
            )
            ListItem(
                leadingContent = { Icon(painterResource(R.drawable.ic_audiobook), contentDescription = null) },
                headlineContent = { Text(stringResource(R.string.reader_menu_audiobook)) },
                supportingContent = {
                    Text(stringResource(if (audiobookPaired) R.string.audiobook_menu_paired else R.string.audiobook_menu_new))
                },
                colors = colors,
                modifier = Modifier.clickable(onClick = onAudiobook),
            )
            ListItem(
                leadingContent = { Icon(painterResource(R.drawable.ic_tune), contentDescription = null) },
                headlineContent = { Text(stringResource(R.string.reader_menu_settings)) },
                colors = colors,
                modifier = Modifier.clickable(onClick = onSettings),
            )
            ListItem(
                leadingContent = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = null) },
                headlineContent = { Text(stringResource(R.string.session_open)) },
                colors = colors,
                modifier = Modifier.clickable(onClick = onSessionWords),
            )
            ListItem(
                leadingContent = { Icon(painterResource(R.drawable.ic_visible), contentDescription = null) },
                headlineContent = { Text(stringResource(R.string.reader_menu_progress)) },
                supportingContent = { Text(stringResource(R.string.reader_menu_progress_details)) },
                trailingContent = { Switch(checked = showProgress, onCheckedChange = null) },
                colors = colors,
                modifier = Modifier.toggleable(value = showProgress, role = Role.Switch, onValueChange = onSetShowProgress),
            )
            HorizontalDivider()
            ListItem(
                leadingContent = { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null) },
                headlineContent = { Text(stringResource(R.string.reader_menu_close_book)) },
                colors = colors,
                modifier = Modifier.clickable(onClick = onCloseBook),
            )
        }
    }
}

/** Where the reader is and how long the book is, in ttu's characters. */
data class MenuPlace(val characters: Long, val total: Int)

/** A slider over the whole book with its label; letting go jumps there. */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun PlaceSlider(place: MenuPlace?, onJump: (Long) -> Unit) {
    val total = place?.total ?: 0
    var fraction by remember(place) { mutableFloatStateOf(place?.let { BookPlace.fraction(it.characters, it.total) } ?: 0f) }
    val characters = BookPlace.charactersAt(fraction, total)
    val numbers = NumberFormat.getIntegerInstance()
    val label = stringResource(R.string.reader_menu_slider)
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(
            if (place == null) label else stringResource(
                R.string.reader_menu_slider_label,
                BookPlace.percent(characters, total.toLong()),
                numbers.format(characters),
                numbers.format(total),
            ),
            style = MaterialTheme.typography.bodyMedium,
        )
        Slider(
            value = fraction,
            onValueChange = { fraction = it },
            onValueChangeFinished = { onJump(BookPlace.charactersAt(fraction, total)) },
            enabled = place != null && total > 0,
            // No dot at the end of the track.
            track = { state -> SliderDefaults.Track(sliderState = state, enabled = place != null && total > 0, drawStopIndicator = null) },
            modifier = Modifier.semantics { contentDescription = label },
        )
    }
}
