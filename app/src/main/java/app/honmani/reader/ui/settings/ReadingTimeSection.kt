package app.honmani.reader.ui.settings

import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.ListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.honmani.reader.HonmaniApplication
import app.honmani.reader.R

/** Settings → Reading: whether the reading timer runs while the lookup panel is open (on by default). */
@Composable
fun ReadingTimeSection() {
    val context = LocalContext.current
    val settings = remember { (context.applicationContext as HonmaniApplication).container.readingTimeSettings }
    val count by settings.countDuringLookup.collectAsStateWithLifecycle()
    ListItem(
        headlineContent = { Text(stringResource(R.string.settings_count_lookup_time)) },
        supportingContent = { Text(stringResource(R.string.settings_count_lookup_time_details)) },
        trailingContent = { Switch(checked = count, onCheckedChange = null) },
        modifier = Modifier.toggleable(count, role = Role.Switch, onValueChange = settings::setCountDuringLookup),
    )
}
