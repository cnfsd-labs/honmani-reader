package app.honmani.reader.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import android.text.format.DateUtils
import android.text.format.Formatter
import app.honmani.reader.R
import app.honmani.reader.diagnostics.NetworkLog
import app.honmani.reader.diagnostics.NetworkPurpose
import app.honmani.reader.ui.HonmaniTopAppBar

private fun NetworkPurpose.title(): Int = when (this) {
    NetworkPurpose.DICTIONARY -> R.string.network_purpose_dictionary
    NetworkPurpose.BOOK_SERVER -> R.string.network_purpose_book_server
    NetworkPurpose.PROGRESS_SYNC -> R.string.network_purpose_sync
    NetworkPurpose.GOOGLE_VOICE -> R.string.network_purpose_voice
}

/**
 * The latest requests Honmani made to the network: when, what for, which server (only its
 * host), and how it went. The list lives in memory and is never sent anywhere.
 */
@Composable
fun NetworkActivityScreen(onBack: () -> Unit, log: NetworkLog = NetworkLog.shared) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    var entries by remember { mutableStateOf(log.entries()) }
    Scaffold(
        topBar = {
            HonmaniTopAppBar(
                title = stringResource(R.string.network_activity_title),
                onBack = onBack,
                actions = {
                    TextButton(
                        onClick = {
                            log.clear()
                            entries = log.entries()
                        },
                        enabled = entries.isNotEmpty(),
                    ) { Text(stringResource(R.string.network_activity_clear)) }
                },
            )
        },
    ) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
            item {
                Text(
                    text = stringResource(R.string.network_activity_details),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            if (entries.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.network_activity_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            itemsIndexed(entries) { _, entry ->
                ListItem(
                    overlineContent = {
                        Text(
                            DateUtils.formatDateTime(
                                context,
                                entry.time,
                                DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_ALL,
                            ),
                        )
                    },
                    headlineContent = { Text(stringResource(entry.purpose.title())) },
                    supportingContent = {
                        val size = entry.bytes?.let { "  ·  " + Formatter.formatShortFileSize(context, it) }.orEmpty()
                        Text(entry.host + "  ·  " + entry.result + size)
                    },
                )
                HorizontalDivider()
            }
        }
    }
}
