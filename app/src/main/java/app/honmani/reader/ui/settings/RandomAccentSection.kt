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
import app.honmani.reader.data.settings.AccentRandom

/** Settings → Appearance: Off / Every day / Every launch for a random accent colour. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RandomAccentSection() {
    val context = LocalContext.current
    val container = remember { (context.applicationContext as HonmaniApplication).container }
    val mode by container.appearanceSettings.randomAccent.collectAsStateWithLifecycle()
    ListItem(
        headlineContent = { Text(stringResource(R.string.settings_accent_random)) },
        supportingContent = { Text(stringResource(R.string.settings_accent_random_details)) },
    )
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (choice in AccentRandom.entries) {
                FilterChip(
                    selected = choice == mode,
                    onClick = { container.appearanceSettings.setRandomAccent(choice, container.dayClock.today().toEpochDay()) },
                    label = {
                        Text(
                            stringResource(
                                when (choice) {
                                    AccentRandom.OFF -> R.string.settings_accent_random_off
                                    AccentRandom.DAILY -> R.string.settings_accent_random_daily
                                    AccentRandom.LAUNCH -> R.string.settings_accent_random_launch
                                },
                            ),
                        )
                    },
                )
            }
        }
    }
}
