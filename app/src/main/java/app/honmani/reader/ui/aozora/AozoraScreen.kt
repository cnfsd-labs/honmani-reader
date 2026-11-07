package app.honmani.reader.ui.aozora

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.honmani.reader.R
import app.honmani.reader.data.aozora.AozoraException
import app.honmani.reader.data.aozora.AozoraWork
import app.honmani.reader.ui.HonmaniTopAppBar

/** The free books catalogue: an explanation until the user loads the list, then search and download. */
@Composable
fun AozoraScreen(
    onBack: () -> Unit,
    onBook: (AozoraBook) -> Unit,
    viewModel: AozoraViewModel = viewModel(factory = AozoraViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val currentOnBook by rememberUpdatedState(onBook)
    LaunchedEffect(viewModel) { viewModel.downloaded.collect { currentOnBook(it) } }
    AozoraContent(
        state = state,
        onBack = onBack,
        onLoad = viewModel::load,
        onQuery = viewModel::setQuery,
        onIncludeOld = viewModel::setIncludeOld,
        onSelect = viewModel::select,
        onDeselect = viewModel::deselect,
        onAdd = viewModel::add,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AozoraContent(
    state: AozoraUiState,
    onBack: () -> Unit,
    onLoad: () -> Unit,
    onQuery: (String) -> Unit,
    onIncludeOld: (Boolean) -> Unit,
    onSelect: (AozoraWork) -> Unit,
    onDeselect: () -> Unit,
    onAdd: () -> Unit,
) {
    BackHandler(onBack = onBack)
    Scaffold(
        topBar = {
            HonmaniTopAppBar(
                title = stringResource(R.string.aozora_title),
                onBack = onBack,
                actions = {
                    if (state.hasList) {
                        IconButton(onClick = onLoad, enabled = !state.loading) {
                            Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.aozora_refresh))
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.starting -> Column(
                    Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) { CircularProgressIndicator() }
                !state.hasList -> Intro(state, onLoad)
                else -> Browser(state, onQuery, onIncludeOld, onSelect)
            }
        }
    }
    state.selected?.let { work ->
        ModalBottomSheet(onDismissRequest = onDeselect) {
            WorkDetails(work, state.download, onAdd, onCancel = onDeselect)
        }
    }
}

@Composable
private fun Intro(state: AozoraUiState, onLoad: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            stringResource(R.string.aozora_intro_title),
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
        )
        Text(
            stringResource(R.string.aozora_intro_message),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 12.dp),
        )
        state.error?.let {
            Text(
                stringResource(listErrorText(it)),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
        if (state.loading) {
            CircularProgressIndicator(Modifier.padding(top = 24.dp))
        } else {
            Button(onClick = onLoad, modifier = Modifier.padding(top = 24.dp)) {
                Text(stringResource(R.string.aozora_load))
            }
        }
    }
}

@Composable
private fun Browser(
    state: AozoraUiState,
    onQuery: (String) -> Unit,
    onIncludeOld: (Boolean) -> Unit,
    onSelect: (AozoraWork) -> Unit,
) {
    OutlinedTextField(
        value = state.query,
        onValueChange = onQuery,
        singleLine = true,
        label = { Text(stringResource(R.string.aozora_search)) },
        trailingIcon = {
            if (state.query.isNotEmpty()) {
                IconButton(onClick = { onQuery("") }) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_clear))
                }
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onIncludeOld(!state.includeOld) }
            .padding(horizontal = 16.dp),
    ) {
        Text(
            stringResource(R.string.aozora_include_old),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = state.includeOld, onCheckedChange = onIncludeOld)
    }
    if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
    state.error?.let {
        Text(
            stringResource(listErrorText(it)),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
    }
    Text(
        stringResource(R.string.aozora_count, state.results.size, state.generated),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
    HorizontalDivider()
    if (state.results.isEmpty()) {
        Text(
            stringResource(R.string.aozora_no_results),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(32.dp),
        )
    } else {
        LazyColumn(Modifier.fillMaxSize()) {
            items(state.results, key = { it.id }) { work ->
                ListItem(
                    headlineContent = { Text(work.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text(subtitle(work), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clickable { onSelect(work) },
                )
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun subtitle(work: AozoraWork): String {
    val parts = listOfNotNull(
        work.author.takeIf { it.isNotEmpty() },
        stringResource(R.string.aozora_old_kana).takeIf { work.oldKana },
    )
    return parts.joinToString(" · ")
}

@Composable
private fun WorkDetails(work: AozoraWork, download: AozoraDownload?, onAdd: () -> Unit, onCancel: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 24.dp, vertical = 8.dp),
    ) {
        Text(work.title, style = MaterialTheme.typography.titleLarge)
        if (work.author.isNotEmpty()) {
            Text(work.author, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 4.dp))
        }
        val details = listOfNotNull(
            stringResource(R.string.aozora_published, work.published).takeIf { work.published.isNotEmpty() },
            stringResource(R.string.aozora_old_kana_details).takeIf { work.oldKana },
            stringResource(R.string.aozora_old_kanji_details).takeIf { work.oldKanji },
        )
        for (line in details) {
            Text(
                line,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        when (download) {
            is AozoraDownload.Running -> {
                val progress = download.progress
                if (progress == null) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 16.dp))
                } else {
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().padding(top = 16.dp))
                }
                OutlinedButton(onClick = onCancel, modifier = Modifier.padding(top = 12.dp, bottom = 16.dp)) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
            else -> {
                if (download is AozoraDownload.Failed) {
                    Text(
                        stringResource(bookErrorText(download.reason)),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
                Button(onClick = onAdd, modifier = Modifier.padding(top = 16.dp, bottom = 16.dp)) {
                    Text(stringResource(R.string.aozora_add))
                }
            }
        }
    }
}

private fun listErrorText(reason: AozoraException.Reason): Int = when (reason) {
    AozoraException.Reason.NETWORK -> R.string.aozora_error_list_network
    AozoraException.Reason.TOO_NEW -> R.string.aozora_error_list_new
    else -> R.string.aozora_error_list
}

private fun bookErrorText(reason: AozoraException.Reason): Int = when (reason) {
    AozoraException.Reason.NETWORK -> R.string.aozora_error_book_network
    AozoraException.Reason.TOO_LARGE -> R.string.aozora_error_book_large
    else -> R.string.aozora_error_book
}
