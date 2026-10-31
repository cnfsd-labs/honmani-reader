package app.honmani.reader.ui.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
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
 * The reader's bottom menu, opened by a tap on the footer of the page (`reader-menu.js`): the
 * table of contents, Honmani's reader settings, the words of this session, leaving the book and
 * the progress counter.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderMenuSheet(
    showProgress: Boolean,
    onContents: () -> Unit,
    onSettings: () -> Unit,
    onSessionWords: () -> Unit,
    onCloseBook: () -> Unit,
    onSetShowProgress: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        val colors = ListItemDefaults.colors(containerColor = Color.Transparent)
        Column(modifier = Modifier.navigationBarsPadding().padding(bottom = 8.dp)) {
            ListItem(
                leadingContent = { Icon(Icons.Filled.Menu, contentDescription = null) },
                headlineContent = { Text(stringResource(R.string.reader_menu_contents)) },
                colors = colors,
                modifier = Modifier.clickable(onClick = onContents),
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
                modifier = Modifier.clickable { onSetShowProgress(!showProgress) },
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
