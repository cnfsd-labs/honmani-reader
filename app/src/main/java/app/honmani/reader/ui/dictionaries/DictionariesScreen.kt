package app.honmani.reader.ui.dictionaries

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.honmani.reader.R
import app.honmani.reader.data.dictionary.CatalogDictionary
import app.honmani.reader.data.dictionary.DictionaryCatalog
import app.honmani.reader.data.dictionary.DictionaryEntity
import app.honmani.reader.data.dictionary.DictionaryUpdate
import app.honmani.reader.data.dictionary.DownloadProgress
import app.honmani.reader.data.dictionary.ImportError
import app.honmani.reader.data.dictionary.ImportProgress
import app.honmani.reader.data.dictionary.ImportStatus
import app.honmani.reader.ui.HonmaniTopAppBar
import app.honmani.reader.ui.ProgressBar
import app.honmani.reader.ui.rememberWithNotifications
import app.honmani.reader.ui.theme.HonmaniTheme
import java.text.NumberFormat

/** MIME types file managers use for Yomitan's .zip files. */
private val DICTIONARY_MIME_TYPES =
    arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")

@Composable
fun DictionariesScreen(
    onBack: () -> Unit,
    viewModel: DictionariesViewModel = viewModel(factory = DictionariesViewModel.Factory),
) {
    BackHandler(onBack = onBack)
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.import(it.toString()) }
    }
    val withNotifications = rememberWithNotifications()
    DictionariesContent(
        uiState = uiState,
        onBack = onBack,
        onImport = { withNotifications { picker.launch(DICTIONARY_MIME_TYPES) } },
        onDownload = { dictionary -> withNotifications { viewModel.download(dictionary) } },
        onDismissFailures = viewModel::dismissFailures,
        onSetEnabled = viewModel::setEnabled,
        onDelete = viewModel::delete,
        onMove = viewModel::move,
        onUpdate = { update -> withNotifications { viewModel.update(update) } },
        onCheckUpdates = viewModel::checkForUpdates,
    )
}

@Composable
private fun DictionariesContent(
    uiState: DictionariesUiState,
    onBack: () -> Unit,
    onImport: () -> Unit,
    onDownload: (CatalogDictionary) -> Unit,
    onDismissFailures: () -> Unit,
    onSetEnabled: (id: Long, enabled: Boolean) -> Unit,
    onDelete: (id: Long) -> Unit,
    onMove: (id: Long, up: Boolean) -> Unit,
    onUpdate: (DictionaryUpdate) -> Unit,
    onCheckUpdates: () -> Unit,
) {
    var pendingDelete by remember { mutableStateOf<DictionaryEntity?>(null) }

    Scaffold(
        topBar = {
            HonmaniTopAppBar(title = stringResource(R.string.dictionaries_title), onBack = onBack)
        },
        floatingActionButton = {
            // The label didn't reach TalkBack from the text slot (the 7 Pro's accessibility tree
            // showed a nameless button): the icon names the button and the text stays visual.
            val importLabel = stringResource(R.string.action_import_dictionary)
            ExtendedFloatingActionButton(
                onClick = onImport,
                icon = { Icon(Icons.Filled.Add, contentDescription = importLabel) },
                text = { Text(importLabel, modifier = Modifier.clearAndSetSemantics {}) },
            )
        },
    ) { padding ->
        val modifier = Modifier
            .fillMaxSize()
            .padding(padding)
        when (uiState) {
            DictionariesUiState.Loading -> Box(modifier, contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            is DictionariesUiState.Loaded -> LazyColumn(
                modifier = modifier,
                // Room for the floating button below the last row.
                contentPadding = PaddingValues(bottom = 88.dp),
            ) {
                items(uiState.imports, key = { "import-${it.id}" }) { status ->
                    ImportStatusCard(status, onDismissFailures)
                }
                if (uiState.imports.any { it !is ImportStatus.Failed }) {
                    // Some phones (OnePlus, OPPO) freeze apps in the background despite the
                    // import's foreground service; the import then waits until the app is back.
                    item(key = "import-hint") {
                        Text(
                            stringResource(R.string.import_keep_open),
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (uiState.catalog.any { it.state != CatalogItem.State.INSTALLED }) {
                    item(key = "catalog-title") { SectionTitle(stringResource(R.string.catalog_title)) }
                    items(
                        uiState.catalog.filter { it.state != CatalogItem.State.INSTALLED },
                        key = { "catalog-${it.dictionary.id}" },
                    ) { item ->
                        CatalogRow(item, onDownload = { onDownload(item.dictionary) })
                    }
                }
                if (uiState.dictionaries.isNotEmpty()) {
                    item(key = "installed-title") {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(modifier = Modifier.weight(1f)) { SectionTitle(stringResource(R.string.installed_title)) }
                                UpdateCheckButton(uiState, onCheckUpdates)
                            }
                            if (uiState.dictionaries.size > 1) {
                                Text(
                                    text = stringResource(R.string.dictionaries_order_hint),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                )
                            }
                        }
                    }
                }
                itemsIndexed(uiState.dictionaries, key = { _, it -> it.id }) { i, dictionary ->
                    DictionaryRow(
                        dictionary = dictionary,
                        canMoveUp = i > 0,
                        canMoveDown = i < uiState.dictionaries.lastIndex,
                        onMove = { up -> onMove(dictionary.id, up) },
                        deleting = dictionary.id in uiState.deleting,
                        update = uiState.updates[dictionary.id],
                        updating = dictionary.id in uiState.updating,
                        onSetEnabled = { onSetEnabled(dictionary.id, it) },
                        onDelete = { pendingDelete = dictionary },
                        onUpdate = onUpdate,
                    )
                }
                if (uiState.dictionaries.isEmpty() && uiState.imports.isEmpty()) {
                    item {
                        Text(
                            text = stringResource(R.string.dictionaries_empty),
                            modifier = Modifier.padding(24.dp),
                        )
                    }
                }
            }
        }
    }

    pendingDelete?.let { dictionary ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.delete_dictionary_title, dictionary.title)) },
            text = { Text(stringResource(R.string.delete_dictionary_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete(dictionary.id)
                        pendingDelete = null
                    },
                ) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

/** "Check for updates", "Checking…", or "Up to date" after a check that found nothing. */
@Composable
private fun UpdateCheckButton(uiState: DictionariesUiState.Loaded, onCheckUpdates: () -> Unit) {
    val label = when {
        uiState.updateCheck == UpdateCheck.CHECKING -> R.string.updates_checking
        uiState.updateCheck == UpdateCheck.CHECKED && uiState.updates.isEmpty() -> R.string.updates_none
        else -> R.string.action_check_updates
    }
    TextButton(
        onClick = onCheckUpdates,
        enabled = uiState.updateCheck != UpdateCheck.CHECKING,
        modifier = Modifier.padding(end = 8.dp, top = 8.dp),
    ) {
        Text(stringResource(label))
    }
}

@Composable
private fun CatalogRow(item: CatalogItem, onDownload: () -> Unit) {
    val dictionary = item.dictionary
    val overline = listOfNotNull(
        when (dictionary.kind) {
            CatalogDictionary.Kind.TERMS -> null
            CatalogDictionary.Kind.JAPANESE -> stringResource(R.string.catalog_kind_japanese)
            CatalogDictionary.Kind.KANJI -> stringResource(R.string.catalog_kind_kanji)
            CatalogDictionary.Kind.FREQUENCY -> stringResource(R.string.catalog_kind_frequency)
            CatalogDictionary.Kind.PITCH -> stringResource(R.string.catalog_kind_pitch)
            CatalogDictionary.Kind.NAMES -> stringResource(R.string.catalog_kind_names)
        },
        stringResource(R.string.catalog_recommended).takeIf { dictionary.recommended },
    ).joinToString(" · ")
    ListItem(
        overlineContent = if (overline.isNotEmpty()) {
            { Text(overline) }
        } else {
            null
        },
        headlineContent = { Text(dictionary.name) },
        supportingContent = {
            Column {
                catalogDescription(dictionary)?.let { Text(stringResource(it)) }
                Text(
                    text = stringResource(R.string.catalog_details, dictionary.sizeMb, dictionary.license),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        trailingContent = {
            when (item.state) {
                CatalogItem.State.AVAILABLE -> TextButton(onClick = onDownload) {
                    Text(stringResource(R.string.action_download))
                }
                CatalogItem.State.IN_PROGRESS -> Text(stringResource(R.string.catalog_in_progress))
                CatalogItem.State.INSTALLED -> Text(stringResource(R.string.catalog_installed))
            }
        },
    )
}

private fun catalogDescription(dictionary: CatalogDictionary): Int? = when (dictionary.id) {
    "jitendex" -> R.string.catalog_jitendex
    "jmdict-english" -> R.string.catalog_jmdict_english
    "jmnedict" -> R.string.catalog_jmnedict
    "kanjidic-english" -> R.string.catalog_kanjidic
    "wiktionary-japanese" -> R.string.catalog_wiktionary_japanese
    "jiten-frequency" -> R.string.catalog_jiten_frequency
    "kanjium-pitch" -> R.string.catalog_kanjium_pitch
    else -> null
}

@Composable
private fun ImportStatusCard(status: ImportStatus, onDismissFailures: () -> Unit) {
    val failed = status is ImportStatus.Failed
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = if (failed) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
        } else {
            CardDefaults.cardColors()
        },
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (status) {
                is ImportStatus.Queued -> {
                    Text(stringResource(R.string.import_queued))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                is ImportStatus.Running -> {
                    val progress = status.progress
                    val download = status.download
                    if (download != null) {
                        DownloadStatus(download)
                    } else if (progress == null) {
                        Text(stringResource(R.string.import_preparing))
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    } else {
                        Text(stringResource(R.string.import_running, progress.title))
                        ProgressBar(
                            progress = { progress.percent / 100f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                is ImportStatus.Failed -> {
                    Text(importErrorMessage(status))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = onDismissFailures) {
                            Text(stringResource(R.string.action_dismiss))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadStatus(download: DownloadProgress) {
    if (download.bytesTotal > 0) {
        Text(
            stringResource(
                R.string.download_progress,
                download.name,
                (download.bytesDone / MEGABYTE).toInt(),
                (download.bytesTotal / MEGABYTE).toInt(),
            ),
        )
        ProgressBar(
            progress = { download.bytesDone.toFloat() / download.bytesTotal },
            modifier = Modifier.fillMaxWidth(),
        )
    } else {
        Text(stringResource(R.string.download_running, download.name))
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }
}

private const val MEGABYTE = 1_000_000L

@Composable
private fun importErrorMessage(failure: ImportStatus.Failed): String {
    val detail = failure.detail.orEmpty()
    return when (failure.error) {
        ImportError.INVALID -> stringResource(R.string.import_failed_invalid, detail)
        ImportError.ALREADY_INSTALLED -> stringResource(R.string.import_failed_already_installed, detail)
        ImportError.NO_TERMS -> stringResource(R.string.import_failed_no_terms, detail)
        ImportError.DOWNLOAD_FAILED -> stringResource(R.string.import_failed_download, detail)
        ImportError.FAILED -> stringResource(R.string.import_failed)
    }
}

@Composable
private fun DictionaryRow(
    dictionary: DictionaryEntity,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMove: (up: Boolean) -> Unit,
    deleting: Boolean,
    update: DictionaryUpdate?,
    updating: Boolean,
    onSetEnabled: (Boolean) -> Unit,
    onDelete: () -> Unit,
    onUpdate: (DictionaryUpdate) -> Unit,
) {
    // Frequency and pitch dictionaries have no terms of their own, only data about words;
    // kanji dictionaries only characters.
    val (details, number) = when {
        dictionary.termCount > 0 -> R.string.dictionaries_details to dictionary.termCount
        dictionary.kanjiCount > 0 -> R.string.dictionaries_details_kanji to dictionary.kanjiCount
        else -> R.string.dictionaries_details_meta to dictionary.metaCount
    }
    val count = remember(number) { NumberFormat.getIntegerInstance().format(number) }
    ListItem(
        headlineContent = { Text(dictionary.title) },
        supportingContent = {
            Column {
                Text(stringResource(details, dictionary.revision, count))
                if (update != null) {
                    Text(
                        text = stringResource(R.string.dictionary_update_available, update.revision),
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        },
        // The update button sits under the switch and the menu, at the right edge, so the
        // version lines on the left keep their full width.
        trailingContent = {
            Column(horizontalAlignment = Alignment.End) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (deleting) {
                        Text(stringResource(R.string.dictionary_deleting))
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    } else {
                        Switch(
                            checked = dictionary.enabled,
                            onCheckedChange = onSetEnabled,
                            // Read out with the dictionary's name, not just "switch".
                            modifier = Modifier.semantics { contentDescription = dictionary.title },
                        )
                        DictionaryMenu(canMoveUp, canMoveDown, onMove, onDelete)
                    }
                }
                if (update != null && !deleting) {
                    if (updating) {
                        Text(
                            text = stringResource(R.string.dictionary_updating),
                            modifier = Modifier.padding(vertical = 10.dp, horizontal = 12.dp),
                        )
                    } else {
                        TextButton(onClick = { onUpdate(update) }) {
                            Text(stringResource(R.string.action_update))
                        }
                    }
                }
            }
        },
    )
}

/** Order (the first dictionary's definitions show first in lookups) and delete. */
@Composable
private fun DictionaryMenu(
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMove: (up: Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.dictionary_menu))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (canMoveUp) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_move_up)) },
                    onClick = {
                        open = false
                        onMove(true)
                    },
                )
            }
            if (canMoveDown) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_move_down)) },
                    onClick = {
                        open = false
                        onMove(false)
                    },
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_delete)) },
                onClick = {
                    open = false
                    onDelete()
                },
            )
        }
    }
}

@Preview
@Composable
private fun DictionariesContentPreview() {
    HonmaniTheme {
        DictionariesContent(
            uiState = DictionariesUiState.Loaded(
                dictionaries = listOf(
                    DictionaryEntity(
                        id = 1,
                        title = "JMdict",
                        revision = "2026-10-01",
                        formatVersion = 3,
                        importedAt = 0,
                        termCount = 214_812,
                    ),
                ),
                catalog = DictionaryCatalog.dictionaries.map { CatalogItem(it, CatalogItem.State.AVAILABLE) },
                imports = listOf(
                    ImportStatus.Running("a", ImportProgress("Jitendex", percent = 40)),
                    ImportStatus.Failed("b", ImportError.INVALID, "index.json is missing"),
                ),
            ),
            onBack = {},
            onImport = {},
            onDownload = {},
            onDismissFailures = {},
            onSetEnabled = { _, _ -> },
            onDelete = {},
            onMove = { _, _ -> },
            onUpdate = {},
            onCheckUpdates = {},
        )
    }
}
