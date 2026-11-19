package app.honmani.reader.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.honmani.reader.HonmaniApplication
import app.honmani.reader.R
import app.honmani.reader.data.sync.KoSyncException
import app.honmani.reader.ui.InfoAction
import kotlinx.coroutines.launch

/**
 * Settings → reading progress sync: a KOReader sync server account (KOReader's own server, a
 * self-hosted one, or Calibre-Web Automated). Off until the reader signs in.
 */
@Composable
fun ProgressSyncSection() {
    val context = LocalContext.current
    val container = remember { (context.applicationContext as HonmaniApplication).container }
    val settings = container.koSyncSettings
    val account by settings.account.collectAsStateWithLifecycle()
    // Signed in, but this phone can no longer read the login: the password is needed again.
    val locked = settings.signedInAs.collectAsStateWithLifecycle().value.takeIf { account == null }
    val status by container.koSyncStatus.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var editing by rememberSaveable { mutableStateOf(false) }

    ListItem(
        headlineContent = { Text(stringResource(R.string.sync_title)) },
        supportingContent = {
            Column {
                Text(
                    account?.let { stringResource(R.string.sync_signed_in, it.username, it.server) }
                        ?: locked?.let { (server, username) -> stringResource(R.string.sync_needs_password, username, server) }
                        ?: stringResource(R.string.sync_off),
                    color = if (locked != null) MaterialTheme.colorScheme.error else androidx.compose.ui.graphics.Color.Unspecified,
                )
                if (account != null) {
                    val failure = status.failure
                    val ok = status.lastSuccessAt
                    if (failure != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                stringResource(R.string.sync_status_failed, syncAgo(failure.at), syncFailureReason(failure)),
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = {
                                scope.launch { if (!container.progressSync.syncNow()) container.progressSync.checkServer() }
                            }) { Text(stringResource(R.string.sync_now)) }
                        }
                    }
                    if (ok != null) {
                        Text(stringResource(R.string.sync_status_ok, syncAgo(ok)))
                    } else if (failure == null) {
                        Text(stringResource(R.string.sync_status_never))
                    }
                }
                Text(stringResource(R.string.sync_summary))
            }
        },
        modifier = Modifier.clickable { editing = true },
    )
    if (!editing) return
    val signedIn = account
    if (signedIn != null) {
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text(stringResource(R.string.sync_title)) },
            text = { Text(stringResource(R.string.sync_signed_in_details, signedIn.username, signedIn.server)) },
            confirmButton = { TextButton(onClick = { editing = false }) { Text(stringResource(R.string.sync_close)) } },
            dismissButton = {
                TextButton(onClick = {
                    settings.signOut()
                    container.koSyncStatus.clear()
                    editing = false
                }) { Text(stringResource(R.string.sync_sign_out)) }
            },
        )
    } else {
        SignInDialog(onDismiss = { editing = false }, initialServer = locked?.first.orEmpty(), initialUsername = locked?.second.orEmpty())
    }
}

@Composable
private fun SignInDialog(onDismiss: () -> Unit, initialServer: String = "", initialUsername: String = "") {
    val context = LocalContext.current
    val container = remember { (context.applicationContext as HonmaniApplication).container }
    val scope = rememberCoroutineScope()
    var server by rememberSaveable { mutableStateOf(initialServer) }
    var username by rememberSaveable { mutableStateOf(initialUsername) }
    // Not saveable: that would write it, readable, into the saved state.
    var password by remember { mutableStateOf("") }
    var checking by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Int?>(null) }

    fun submit() {
        if (server.isBlank() || username.isBlank() || password.isEmpty() || checking) return
        checking = true
        error = null
        scope.launch {
            try {
                container.koSyncSettings.signIn(container.koSyncClient.signIn(server, username, password))
                onDismiss()
            } catch (e: KoSyncException) {
                error = when (e.reason) {
                    KoSyncException.Reason.UNAUTHORIZED -> R.string.sync_error_unauthorized
                    KoSyncException.Reason.NETWORK -> R.string.sync_error_network
                    KoSyncException.Reason.SERVER -> R.string.sync_error_server
                    KoSyncException.Reason.HOTSPOT -> R.string.catalog_error_hotspot
                    KoSyncException.Reason.PASSWORD_STORAGE -> R.string.error_password_storage
                    KoSyncException.Reason.USE_HTTPS -> R.string.sync_error_use_https
                }
            } finally {
                checking = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sync_title)) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.sync_message),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    InfoAction(title = stringResource(R.string.sync_servers_title), text = stringResource(R.string.sync_servers_info))
                }
                SuggestionChip(
                    onClick = { server = KOREADER_SYNC_SERVER },
                    label = { Text(stringResource(R.string.sync_use_koreader_server)) },
                    enabled = !checking,
                )
                OutlinedTextField(
                    value = server,
                    onValueChange = { server = it },
                    label = { Text(stringResource(R.string.sync_server)) },
                    placeholder = { Text(stringResource(R.string.sync_server_hint)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                    enabled = !checking,
                )
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text(stringResource(R.string.sync_username)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    enabled = !checking,
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.catalog_password)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    enabled = !checking,
                )
                if (server.trim().startsWith("http://", ignoreCase = true) && (username.isNotBlank() || password.isNotEmpty())) {
                    Text(
                        stringResource(R.string.catalog_insecure_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                error?.let {
                    Text(stringResource(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { submit() }, enabled = !checking) {
                Text(stringResource(R.string.sync_sign_in))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/** KOReader's public sync server, filled in by the dialog's chip. */
private const val KOREADER_SYNC_SERVER = "https://sync.koreader.rocks"
