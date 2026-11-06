package app.honmani.reader.ui.settings

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.honmani.reader.HonmaniApplication
import app.honmani.reader.R
import app.honmani.reader.data.library.WatchedFolder

/**
 * Settings → Books and sync: the optional folder the library looks in for new books when it opens.
 * It says plainly when the folder can no longer be read.
 */
@Composable
fun WatchedFolderSection() {
    val context = LocalContext.current
    val watched = remember { (context.applicationContext as HonmaniApplication).container.watchedFolder }
    var state by remember { mutableStateOf(watched.state()) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            if (!watched.choose(uri.toString())) {
                Toast.makeText(context, R.string.settings_watched_failed, Toast.LENGTH_LONG).show()
            }
            state = watched.state()
        }
    }
    val watching = state != WatchedFolder.State.None
    ListItem(
        headlineContent = { Text(stringResource(if (watching) R.string.settings_watched_change else R.string.settings_watched_choose)) },
        overlineContent = { Text(stringResource(R.string.settings_watched_title)) },
        supportingContent = {
            when (val current = state) {
                WatchedFolder.State.None -> Text(stringResource(R.string.settings_watched_none))
                is WatchedFolder.State.Watching -> Text(stringResource(R.string.settings_watched_watching, current.name))
                is WatchedFolder.State.Unavailable -> Text(
                    stringResource(R.string.settings_watched_unavailable, current.name),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        modifier = Modifier.clickable { picker.launch(null) },
    )
    if (watching) {
        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_watched_stop)) },
            supportingContent = { Text(stringResource(R.string.settings_watched_stop_details)) },
            modifier = Modifier.clickable {
                watched.stop()
                state = watched.state()
            },
        )
    }
}
