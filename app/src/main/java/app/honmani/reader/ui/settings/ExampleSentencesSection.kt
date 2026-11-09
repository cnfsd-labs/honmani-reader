package app.honmani.reader.ui.settings

import android.text.format.Formatter
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.honmani.reader.HonmaniApplication
import app.honmani.reader.R
import app.honmani.reader.data.examples.ExampleState
import app.honmani.reader.ui.ProgressBar
import app.honmani.reader.ui.onboarding.messageRes

/**
 * Settings → Reading: example sentences in the lookup panel, off until switched on. Switching on
 * asks once, with the size, before the download; switching off offers to delete the file.
 */
@Composable
fun ExampleSentencesSection() {
    val context = LocalContext.current
    val container = remember { (context.applicationContext as HonmaniApplication).container }
    val settings = container.appearanceSettings
    val store = container.exampleStore
    val enabled by settings.showExamples.collectAsStateWithLifecycle()
    val state by store.state.collectAsStateWithLifecycle()
    var asking by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    // A setting restored from a backup without the file counts as off until it is downloaded.
    val on = enabled && (state is ExampleState.Installed || state is ExampleState.Downloading)

    ListItem(
        headlineContent = { Text(stringResource(R.string.settings_examples)) },
        supportingContent = {
            Column {
                when (val current = state) {
                    is ExampleState.Downloading -> {
                        val percent = (current.progress * 100).toInt()
                        Text(stringResource(R.string.examples_downloading, percent))
                        ProgressBar(
                            progress = { current.progress },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        )
                        TextButton(onClick = { cancel(container) }) { Text(stringResource(R.string.action_cancel)) }
                    }
                    is ExampleState.Failed -> Text(
                        stringResource(current.reason.messageRes()),
                        color = MaterialTheme.colorScheme.error,
                    )
                    else -> Text(stringResource(R.string.settings_examples_details))
                }
                Text(
                    stringResource(R.string.settings_examples_credit),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        trailingContent = { Switch(checked = on, onCheckedChange = null) },
        modifier = Modifier.toggleable(on, role = Role.Switch) { turnOn ->
            when {
                turnOn && state is ExampleState.Installed -> settings.setShowExamples(true)
                turnOn -> asking = true
                state is ExampleState.Downloading -> cancel(container)
                state is ExampleState.Installed -> deleting = true
                else -> settings.setShowExamples(false)
            }
        },
    )

    if (asking) {
        AlertDialog(
            onDismissRequest = { asking = false },
            title = { Text(stringResource(R.string.examples_download_title, Formatter.formatShortFileSize(context, store.catalog.sizeBytes))) },
            text = { Text(stringResource(R.string.examples_download_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        asking = false
                        settings.setShowExamples(true)
                        store.download()
                    },
                ) { Text(stringResource(R.string.action_download)) }
            },
            dismissButton = {
                TextButton(onClick = { asking = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text(stringResource(R.string.examples_delete_title)) },
            text = { Text(stringResource(R.string.examples_delete_message, Formatter.formatShortFileSize(context, store.file.length()))) },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleting = false
                        settings.setShowExamples(false)
                        store.delete()
                    },
                ) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        deleting = false
                        settings.setShowExamples(false)
                    },
                ) { Text(stringResource(R.string.examples_keep)) }
            },
        )
    }
}

private fun cancel(container: app.honmani.reader.AppContainer) {
    container.exampleStore.delete()
    container.appearanceSettings.setShowExamples(false)
}
