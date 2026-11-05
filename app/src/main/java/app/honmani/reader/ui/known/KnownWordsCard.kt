package app.honmani.reader.ui.known

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.honmani.reader.HonmaniApplication
import app.honmani.reader.R

/** Below this many Known words, the Words screen offers [KnownWordsSheet]. */
private const val CARD_BELOW = 50

private const val PREFS = "known_words"
private const val KEY_CARD_DISMISSED = "card_dismissed"

/** The offer in the Words list for readers who have told Honmani little: gone for good once dismissed. */
@Composable
fun KnownWordsCard() {
    val context = LocalContext.current
    val preferences = remember { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    var dismissed by remember { mutableStateOf(preferences.getBoolean(KEY_CARD_DISMISSED, false)) }
    val known by (context.applicationContext as HonmaniApplication).container.knownWords.batches.knownCount
        .collectAsState(initial = Int.MAX_VALUE)
    var open by rememberSaveable { mutableStateOf(false) }
    if (open) KnownWordsSheet(onDismiss = { open = false })
    if (dismissed || known >= CARD_BELOW) return
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(stringResource(R.string.known_card_text), style = MaterialTheme.typography.titleSmall)
        Text(stringResource(R.string.known_card_details), style = MaterialTheme.typography.bodyMedium)
        Row {
            TextButton(onClick = { open = true }) { Text(stringResource(R.string.known_card_open)) }
            TextButton(
                onClick = {
                    preferences.edit().putBoolean(KEY_CARD_DISMISSED, true).apply()
                    dismissed = true
                },
            ) { Text(stringResource(R.string.known_card_dismiss)) }
        }
    }
}

/** The settings row that opens [KnownWordsSheet]. */
@Composable
fun KnownWordsSettingsRow() {
    var open by rememberSaveable { mutableStateOf(false) }
    if (open) KnownWordsSheet(onDismiss = { open = false })
    ListItem(
        headlineContent = { Text(stringResource(R.string.known_title)) },
        supportingContent = { Text(stringResource(R.string.known_card_details)) },
        modifier = Modifier.clickable { open = true },
    )
}
