package app.honmani.reader.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.honmani.reader.HonmaniApplication
import app.honmani.reader.R
import app.honmani.reader.data.stats.ReadingGoal

/** Settings → Reading: the daily reading goal, off until switched on; its progress shows in the statistics. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReadingGoalSection() {
    val context = LocalContext.current
    val settings = remember { (context.applicationContext as HonmaniApplication).container.readingGoalSettings }
    val enabled by settings.enabled.collectAsStateWithLifecycle()
    val minutes by settings.minutes.collectAsStateWithLifecycle()
    ListItem(
        headlineContent = { Text(stringResource(R.string.settings_daily_goal)) },
        supportingContent = { Text(stringResource(R.string.settings_daily_goal_details)) },
        trailingContent = { Switch(checked = enabled, onCheckedChange = null) },
        modifier = Modifier.toggleable(enabled, role = Role.Switch, onValueChange = settings::setEnabled),
    )
    if (enabled) {
        // A value restored from a backup that isn't a preset is shown as one more chip.
        val choices = (ReadingGoal.PRESETS + minutes).distinct().sorted()
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (choice in choices) {
                    FilterChip(
                        selected = choice == minutes,
                        onClick = { settings.setMinutes(choice) },
                        label = { Text(stringResource(R.string.settings_daily_goal_minutes, choice)) },
                    )
                }
            }
        }
    }
}
