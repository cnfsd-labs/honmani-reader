package app.honmani.reader.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ListItem
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.honmani.reader.HonmaniApplication
import app.honmani.reader.R
import app.honmani.reader.data.backup.BackupCodec
import app.honmani.reader.data.backup.RestoreSummary
import java.text.DateFormat
import java.util.Date

/**
 * Settings → Back up and restore: settings, progress, statistics and saved words in one file the
 * reader keeps where they like (no network), for a new phone or after installing Honmani again.
 */
@Composable
fun BackupSection(viewModel: BackupViewModel = viewModel(factory = BackupViewModel.Factory)) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var choosing by rememberSaveable { mutableStateOf(false) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(BackupCodec.MIME_TYPE)) { uri ->
        uri?.let(viewModel::export)
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::restore)
    }

    ListItem(
        headlineContent = { Text(stringResource(R.string.backup_title)) },
        supportingContent = { Text(stringResource(R.string.backup_details)) },
        modifier = Modifier.clickable { choosing = true },
    )
    if (choosing) {
        AlertDialog(
            onDismissRequest = { choosing = false },
            title = { Text(stringResource(R.string.backup_title)) },
            text = { Text(stringResource(R.string.backup_message)) },
            confirmButton = {
                TextButton(onClick = {
                    choosing = false
                    save.launch(viewModel.fileName())
                }) { Text(stringResource(R.string.backup_save)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    choosing = false
                    // Some file managers don't know zip's type, or call it something else.
                    open.launch(arrayOf(BackupCodec.MIME_TYPE, "application/x-zip-compressed", "application/octet-stream"))
                }) { Text(stringResource(R.string.backup_restore)) }
            },
        )
    }
    BackupStateDialog(state, onDismiss = viewModel::dismiss, onConfirm = { confirm, withCatalogs -> viewModel.confirmRestore(confirm.backup, withCatalogs) })
}

/** While a backup is made or restored, and how it went. */
@Composable
fun BackupStateDialog(
    state: BackupState,
    onDismiss: () -> Unit,
    onConfirm: (BackupState.Confirm, Boolean) -> Unit = { _, _ -> },
) {
    when (val current = state) {
        BackupState.Idle -> Unit
        BackupState.Working -> AlertDialog(
            onDismissRequest = {},
            text = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    Text(stringResource(R.string.backup_working))
                }
            },
            confirmButton = {},
        )
        is BackupState.Confirm -> RestoreConfirmDialog(current, onConfirm, onDismiss)
        else -> AlertDialog(
            onDismissRequest = onDismiss,
            title = {
                Text(
                    stringResource(
                        when (current) {
                            BackupState.Saved -> R.string.backup_saved_title
                            is BackupState.Restored -> R.string.backup_restored_title
                            else -> R.string.backup_failed_title
                        },
                    ),
                )
            },
            text = {
                when (current) {
                    BackupState.Saved -> Text(stringResource(R.string.backup_saved))
                    is BackupState.Restored -> RestoredText(current.summary)
                    BackupState.NotABackup -> Text(stringResource(R.string.backup_not_a_backup))
                    else -> Text(stringResource(R.string.backup_failed))
                }
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
        )
    }
}

/**
 * Offers the backup Android brought back from the reader's previous phone ([app.honmani.reader.data.backup.AutoBackup]),
 * once, on the first start of a new install; the progress and result show as for a restore from a file.
 */
@Composable
fun AutoBackupOffer(viewModel: BackupViewModel = viewModel(factory = BackupViewModel.Factory)) {
    val context = LocalContext.current
    val autoBackup = remember { (context.applicationContext as HonmaniApplication).container.autoBackup }
    val offer by autoBackup.offer.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Its own restore's progress and result only: Settings shows those of its own.
    var restoring by rememberSaveable { mutableStateOf(false) }
    offer?.let { madeAt ->
        val date = remember(madeAt) { DateFormat.getDateInstance(DateFormat.LONG).format(Date(madeAt)) }
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.auto_backup_offer_title)) },
            text = { Text(stringResource(R.string.auto_backup_offer, date)) },
            confirmButton = {
                TextButton(onClick = {
                    restoring = true
                    viewModel.restoreAutoBackup()
                }) { Text(stringResource(R.string.auto_backup_restore)) }
            },
            dismissButton = {
                TextButton(onClick = autoBackup::answered) { Text(stringResource(R.string.auto_backup_start_fresh)) }
            },
        )
    }
    if (restoring) {
        BackupStateDialog(state, onDismiss = {
            restoring = false
            viewModel.dismiss()
        })
    }
}

/** What the file holds, before anything is restored; its book servers can be left out. */
@Composable
private fun RestoreConfirmDialog(state: BackupState.Confirm, onConfirm: (BackupState.Confirm, Boolean) -> Unit, onDismiss: () -> Unit) {
    val contents = state.contents
    var withCatalogs by rememberSaveable { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.backup_confirm_title)) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                Text(stringResource(R.string.backup_confirm_intro))
                Text(pluralStringResource(R.plurals.backup_confirm_books, contents.books, contents.books))
                Text(pluralStringResource(R.plurals.backup_confirm_words, contents.words, contents.words))
                Text(pluralStringResource(R.plurals.backup_confirm_days, contents.readingDays, contents.readingDays))
                if (contents.hasReaderSettings) Text(stringResource(R.string.backup_confirm_reader_settings))
                if (contents.catalogs.isNotEmpty()) {
                    Text(stringResource(R.string.backup_confirm_catalogs))
                    for (catalog in contents.catalogs) {
                        Text(stringResource(R.string.backup_confirm_catalog, catalog.title, catalog.host))
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.toggleable(withCatalogs, role = Role.Checkbox) { withCatalogs = it },
                    ) {
                        Checkbox(checked = withCatalogs, onCheckedChange = null)
                        Text(
                            stringResource(R.string.backup_confirm_with_catalogs, contents.catalogs.size),
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(state, withCatalogs || contents.catalogs.isEmpty()) }) {
                Text(stringResource(R.string.backup_confirm_restore))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun RestoredText(summary: RestoreSummary) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.backup_restored))
        if (summary.booksInLibrary > 0) {
            Text(pluralStringResource(R.plurals.backup_restored_books, summary.booksInLibrary, summary.booksInLibrary))
        }
        if (summary.wordsAdded > 0) {
            Text(pluralStringResource(R.plurals.backup_restored_words, summary.wordsAdded, summary.wordsAdded))
        }
        if (summary.booksWaiting > 0) {
            Text(pluralStringResource(R.plurals.backup_restored_waiting, summary.booksWaiting, summary.booksWaiting))
        }
        if (summary.catalogsAdded > 0) {
            Text(pluralStringResource(R.plurals.backup_restored_catalogs, summary.catalogsAdded, summary.catalogsAdded))
        }
        Text(stringResource(R.string.backup_restored_not_included))
    }
}
