package app.honmani.reader.ui.progress

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.honmani.reader.HonmaniApplication
import app.honmani.reader.R
import app.honmani.reader.data.stats.GoalProgress
import app.honmani.reader.ui.ProgressBar
import app.honmani.reader.ui.settings.GoalMinutesChips

/**
 * The daily reading goal on Overview. Off: one compact card that opens the choices (same chips as
 * Settings) and turns the goal on with the pick. On: today against the goal and the streaks; a tap
 * opens the chips again, with a button to turn it off. Both write the same [ReadingGoalSettings] as Settings.
 */
@Composable
internal fun GoalCard(goal: GoalProgress?) {
    val context = LocalContext.current
    val settings = remember { (context.applicationContext as HonmaniApplication).container.readingGoalSettings }
    var open by rememberSaveable { mutableStateOf(false) }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth().clickable { open = !open },
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (goal == null) {
                        Text(stringResource(R.string.progress_goal_set), style = MaterialTheme.typography.titleMedium)
                    } else {
                        Text(
                            stringResource(R.string.stats_goal_title),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            if (goal.met) {
                                stringResource(R.string.stats_goal_met)
                            } else {
                                stringResource(R.string.stats_goal_progress, (goal.secondsToday / 60).toInt(), goal.goalMinutes)
                            },
                            style = MaterialTheme.typography.titleLarge,
                        )
                    }
                }
                Icon(
                    if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (goal != null) {
                ProgressBar(progress = { goal.fraction }, modifier = Modifier.fillMaxWidth())
                val streaks = buildList {
                    if (goal.streak > 0) add(pluralStringResource(R.plurals.stats_goal_streak, goal.streak, goal.streak))
                    if (goal.bestStreak > 0) add(pluralStringResource(R.plurals.progress_goal_best, goal.bestStreak, goal.bestStreak))
                }
                if (streaks.isNotEmpty()) {
                    Text(
                        streaks.joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (open) {
                GoalMinutesChips(
                    selected = goal?.goalMinutes,
                    onChosen = { minutes ->
                        settings.setMinutes(minutes)
                        settings.setEnabled(true)
                        open = false
                    },
                )
                if (goal != null) {
                    TextButton(
                        onClick = {
                            settings.setEnabled(false)
                            open = false
                        },
                    ) { Text(stringResource(R.string.progress_goal_off)) }
                }
            }
        }
    }
}
