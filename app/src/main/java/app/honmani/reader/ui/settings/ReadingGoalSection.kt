package app.honmani.reader.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.honmani.reader.HonmaniApplication
import app.honmani.reader.R
import app.honmani.reader.data.settings.ReadingGoalSettings
import app.honmani.reader.data.stats.ReadingGoal

/** Settings → Reading: the daily reading goal, off until switched on; its progress shows in Progress. */
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
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            GoalMinutesChips(selected = minutes, onChosen = settings::setMinutes)
        }
    }
}

/**
 * The goal choices as chips (10, 20, 30, 45, 60 and Other, which asks for a number), shared by Settings and
 * the Progress goal card. [selected] marks the current goal, if any.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GoalMinutesChips(selected: Int?, onChosen: (Int) -> Unit, modifier: Modifier = Modifier) {
    var askingMinutes by rememberSaveable { mutableStateOf(false) }
    // A value typed under "Other" or restored from a backup is shown as one more chip.
    val choices = (ReadingGoal.PRESETS + listOfNotNull(selected)).distinct().sorted()
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = modifier) {
        for (choice in choices) {
            FilterChip(
                selected = choice == selected,
                onClick = { onChosen(choice) },
                label = { Text(stringResource(R.string.settings_daily_goal_minutes, choice)) },
            )
        }
        FilterChip(
            selected = false,
            onClick = { askingMinutes = true },
            label = { Text(stringResource(R.string.settings_daily_goal_other)) },
        )
    }
    if (askingMinutes) {
        GoalMinutesDialog(
            onChosen = {
                onChosen(it)
                askingMinutes = false
            },
            onDismiss = { askingMinutes = false },
        )
    }
}

/** Asks for a goal in whole minutes; OK and the keyboard's Done accept a number in range and otherwise say what is expected. */
@Composable
private fun GoalMinutesDialog(onChosen: (Int) -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    val chosen = ReadingGoalSettings.parseMinutes(text)
    var tried by rememberSaveable { mutableStateOf(false) }
    val invalid = chosen == null && (text.isNotEmpty() || tried)
    val submit = { if (chosen != null) onChosen(chosen) else tried = true }
    val focus = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_daily_goal_custom_title)) },
        text = {
            TextField(
                value = text,
                onValueChange = { typed -> text = typed.filter(Char::isDigit).take(MAX_TYPED_DIGITS) },
                singleLine = true,
                isError = invalid,
                supportingText = {
                    Text(
                        stringResource(
                            if (invalid) R.string.settings_daily_goal_custom_error else R.string.settings_daily_goal_custom_range,
                            ReadingGoalSettings.MIN_MINUTES,
                            ReadingGoalSettings.MAX_MINUTES,
                        ),
                    )
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                modifier = Modifier.focusRequester(focus),
            )
            LaunchedEffect(Unit) { focus.requestFocus() }
        },
        confirmButton = {
            TextButton(onClick = { submit() }) {
                Text(stringResource(android.R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

private const val MAX_TYPED_DIGITS = 4
