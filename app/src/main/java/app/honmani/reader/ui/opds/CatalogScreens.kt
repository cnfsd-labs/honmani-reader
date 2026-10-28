package app.honmani.reader.ui.opds

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.honmani.reader.R
import app.honmani.reader.data.opds.OpdsEntry
import app.honmani.reader.data.opds.OpdsException
import app.honmani.reader.data.sync.KoSyncException
import app.honmani.reader.ui.HonmaniTopAppBar

/** Browsing a catalogue: its feeds, search, and books to download into the library. */
@Composable
fun CatalogBrowser(
    state: BrowseState,
    downloads: Map<String, DownloadState>,
    inLibrary: Set<String>,
    onBack: () -> Unit,
    onDone: () -> Unit,
    onOpenFeed: (url: String, title: String) -> Unit,
    onDownload: (OpdsEntry) -> Unit,
    onSearch: (String) -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    onChangeLogin: () -> Unit,
    thumbnail: suspend (String) -> ImageBitmap?,
) {
    var searching by rememberSaveable { mutableStateOf(false) }
    BackHandler {
        if (searching) searching = false else onBack()
    }
    val page = state.page
    Scaffold(
        topBar = {
            if (searching) {
                SearchBar(
                    onSearch = {
                        searching = false
                        onSearch(it)
                    },
                    onClose = { searching = false },
                )
            } else {
                HonmaniTopAppBar(
                    title = page?.title ?: state.catalog.title,
                    onBack = onBack,
                    actions = {
                        if (state.search != null) {
                            IconButton(onClick = { searching = true }) {
                                Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.action_search))
                            }
                        }
                    },
                )
            }
        },
        bottomBar = {
            // Back steps through the feeds; Done leaves the catalogue from any depth.
            Surface(tonalElevation = 3.dp) {
                Button(
                    onClick = onDone,
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) { Text(stringResource(R.string.action_done)) }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (state.loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            val error = state.error
            when {
                error != null -> CatalogError(error, onRetry, onChangeLogin)
                page == null -> Unit
                page.entries.isEmpty() -> Text(
                    text = stringResource(R.string.catalog_feed_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                )
                else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(page.entries, key = { it.id }) { entry ->
                        val feedUrl = entry.feedUrl
                        if (feedUrl != null) {
                            NavigationRow(entry, onClick = { onOpenFeed(feedUrl, entry.title) })
                        } else {
                            BookRow(
                                entry = entry,
                                download = downloads[entry.id],
                                inLibrary = entry.id in inLibrary,
                                onDownload = { onDownload(entry) },
                                thumbnail = thumbnail,
                            )
                        }
                        HorizontalDivider()
                    }
                    if (page.nextUrl != null) {
                        item(key = "more") {
                            // Reaching the end of the list loads the next page.
                            LaunchedEffect(page.entries.size) { onLoadMore() }
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                            ) { CircularProgressIndicator(modifier = Modifier.size(24.dp)) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchBar(onSearch: (String) -> Unit, onClose: () -> Unit) {
    var terms by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .statusBarsPadding()
            .padding(8.dp),
    ) {
        OutlinedTextField(
            value = terms,
            onValueChange = { terms = it },
            singleLine = true,
            placeholder = { Text(stringResource(R.string.catalog_search_hint)) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { if (terms.isNotBlank()) onSearch(terms) }),
            modifier = Modifier
                .weight(1f)
                .focusRequester(focus),
        )
        IconButton(onClick = onClose) {
            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_close_search))
        }
    }
}

@Composable
private fun CatalogError(reason: OpdsException.Reason, onRetry: () -> Unit, onChangeLogin: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(32.dp),
    ) {
        Text(
            text = stringResource(catalogErrorText(reason)),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (reason == OpdsException.Reason.UNAUTHORIZED) {
                OutlinedButton(onClick = onChangeLogin) { Text(stringResource(R.string.catalog_change_login)) }
            }
            Button(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
        }
    }
}

fun catalogErrorText(reason: OpdsException.Reason): Int = when (reason) {
    OpdsException.Reason.UNAUTHORIZED -> R.string.catalog_error_unauthorized
    OpdsException.Reason.NOT_A_CATALOGUE -> R.string.catalog_error_not_a_catalogue
    OpdsException.Reason.SERVER -> R.string.catalog_error_server
    OpdsException.Reason.NETWORK -> R.string.catalog_error_network
    OpdsException.Reason.CERTIFICATE -> R.string.catalog_error_certificate
    OpdsException.Reason.TOO_LARGE -> R.string.catalog_error_too_large
    OpdsException.Reason.HOTSPOT -> R.string.catalog_error_hotspot
}

@Composable
private fun NavigationRow(entry: OpdsEntry, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(entry.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = if (entry.summary != null) {
            { Text(entry.summary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        } else {
            null
        },
        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun BookRow(
    entry: OpdsEntry,
    download: DownloadState?,
    inLibrary: Boolean,
    onDownload: () -> Unit,
    thumbnail: suspend (String) -> ImageBitmap?,
) {
    val canDownload = entry.book != null && !inLibrary && download !is DownloadState.Running
    ListItem(
        leadingContent = { Thumbnail(entry.thumbnailUrl, thumbnail) },
        headlineContent = { Text(entry.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column {
                if (entry.authors.isNotEmpty()) {
                    Text(entry.authors.joinToString(", "), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                val status = when {
                    inLibrary -> stringResource(R.string.catalog_in_library)
                    download is DownloadState.Failed -> stringResource(downloadFailureText(download.reason))
                    entry.hasOnlyOtherFormats -> stringResource(R.string.catalog_no_epub)
                    else -> null
                }
                status?.let {
                    Text(
                        it,
                        color = if (download is DownloadState.Failed && !inLibrary) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        trailingContent = {
            when {
                inLibrary -> Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                download is DownloadState.Running -> Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    val progress = download.progress
                    if (progress == null) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    } else {
                        CircularProgressIndicator(progress = { progress }, modifier = Modifier.size(24.dp))
                    }
                }
                entry.book != null -> IconButton(onClick = onDownload) {
                    Icon(
                        painterResource(R.drawable.ic_download),
                        contentDescription = stringResource(R.string.action_download_book, entry.title),
                    )
                }
            }
        },
        modifier = Modifier.clickable(enabled = canDownload, onClick = onDownload),
    )
}

fun downloadFailureText(reason: DownloadFailure): Int = when (reason) {
    DownloadFailure.NETWORK -> R.string.catalog_download_failed_network
    DownloadFailure.UNAUTHORIZED -> R.string.catalog_error_unauthorized
    DownloadFailure.SERVER -> R.string.catalog_download_failed_server
    DownloadFailure.UNREADABLE -> R.string.catalog_download_failed_unreadable
    DownloadFailure.READER -> R.string.catalog_download_failed_reader
    DownloadFailure.HOTSPOT -> R.string.catalog_error_hotspot
}

@Composable
private fun Thumbnail(url: String?, load: suspend (String) -> ImageBitmap?) {
    val image by produceState<ImageBitmap?>(initialValue = null, url) {
        value = url?.let { load(it) }
    }
    val modifier = Modifier
        .size(width = 44.dp, height = 64.dp)
        .clip(MaterialTheme.shapes.extraSmall)
        .background(MaterialTheme.colorScheme.surfaceVariant)
    val bitmap = image
    if (bitmap != null) {
        Image(bitmap, contentDescription = null, contentScale = ContentScale.Crop, modifier = modifier)
    } else {
        Box(modifier)
    }
}

private fun catalogSyncErrorText(reason: KoSyncException.Reason) = when (reason) {
    KoSyncException.Reason.UNAUTHORIZED -> R.string.catalog_sync_error_unauthorized
    KoSyncException.Reason.SERVER -> R.string.sync_error_server
    KoSyncException.Reason.HOTSPOT -> R.string.sync_error_hotspot
    else -> R.string.catalog_sync_error_none
}

/** Adding a catalogue or changing its address and login. */
@Composable
fun CatalogDialog(
    editor: CatalogEditor,
    onSave: (address: String, username: String, password: String, autoImport: Boolean, syncProgress: Boolean) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    var address by rememberSaveable { mutableStateOf(editor.address) }
    var username by rememberSaveable { mutableStateOf(editor.username) }
    var password by rememberSaveable { mutableStateOf("") }
    var autoImport by rememberSaveable { mutableStateOf(editor.autoImport) }
    var syncProgress by rememberSaveable { mutableStateOf(editor.syncProgress) }
    var confirmDelete by remember { mutableStateOf(false) }
    // Sync needs a login: without a user name the switch is off and hidden.
    val withSync = syncProgress && username.isNotBlank()
    val save = { if (address.isNotBlank()) onSave(address, username, password, autoImport, withSync) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(if (editor.id == null) R.string.catalog_add_title else R.string.catalog_edit_title))
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                if (editor.id == null) {
                    Text(stringResource(R.string.catalog_add_message), style = MaterialTheme.typography.bodyMedium)
                }
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it },
                    label = { Text(stringResource(R.string.catalog_address)) },
                    placeholder = { Text(stringResource(R.string.catalog_address_hint)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                    enabled = !editor.checking,
                )
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text(stringResource(R.string.catalog_username)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    enabled = !editor.checking,
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.catalog_password)) },
                    placeholder = if (editor.hasPassword) {
                        { Text(stringResource(R.string.catalog_password_kept)) }
                    } else {
                        null
                    },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { save() }),
                    enabled = !editor.checking,
                )
                if (address.trim().startsWith("http://", ignoreCase = true) &&
                    (username.isNotBlank() || password.isNotEmpty() || editor.hasPassword)
                ) {
                    Text(
                        stringResource(R.string.catalog_insecure_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(autoImport, enabled = !editor.checking, role = Role.Switch) { autoImport = it },
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                        Text(stringResource(R.string.catalog_auto_import), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            stringResource(R.string.catalog_auto_import_details),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = autoImport, onCheckedChange = null, enabled = !editor.checking)
                }
                if (username.isNotBlank()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .toggleable(syncProgress, enabled = !editor.checking, role = Role.Switch) { syncProgress = it },
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                            Text(stringResource(R.string.catalog_sync), style = MaterialTheme.typography.bodyMedium)
                            val other = editor.otherSyncServer
                            Text(
                                if (syncProgress && other != null) {
                                    stringResource(R.string.catalog_sync_replaces, other)
                                } else {
                                    stringResource(R.string.catalog_sync_details)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = syncProgress, onCheckedChange = null, enabled = !editor.checking)
                    }
                }
                editor.error?.let {
                    Text(
                        stringResource(catalogErrorText(it)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                editor.syncError?.let {
                    Text(
                        stringResource(catalogSyncErrorText(it)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (editor.id != null) {
                    TextButton(onClick = { confirmDelete = true }, enabled = !editor.checking) {
                        Text(stringResource(R.string.catalog_delete), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            if (editor.checking) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
            } else {
                TextButton(onClick = save, enabled = address.isNotBlank()) {
                    Text(stringResource(R.string.action_save))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.catalog_delete_title)) },
            text = { Text(stringResource(R.string.catalog_delete_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        onDelete()
                    },
                ) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}
