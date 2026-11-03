package app.honmani.reader.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.honmani.reader.HonmaniApplication
import app.honmani.reader.R
import app.honmani.reader.data.settings.DaySettings

/** Settings → Reading: the hour after midnight when a reading and review day starts (midnight by default). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DayStartSection() {
    val context = LocalContext.current
    val settings = remember { (context.applicationContext as HonmaniApplication).container.daySettings }
    val startHour by settings.startHour.collectAsStateWithLifecycle()
    ListItem(
        headlineContent = { Text(stringResource(R.string.settings_day_start)) },
        supportingContent = { Text(stringResource(R.string.settings_day_start_details)) },
    )
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (hour in DaySettings.CHOICES) {
                FilterChip(
                    selected = hour == startHour,
                    onClick = { settings.setStartHour(hour) },
                    label = {
                        Text(
                            if (hour == 0) stringResource(R.string.settings_day_start_midnight) else stringResource(R.string.settings_day_start_hour, hour),
                        )
                    },
                )
            }
        }
    }
}
