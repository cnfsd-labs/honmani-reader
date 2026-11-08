package app.honmani.reader.ui.reader

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.honmani.reader.R
import app.honmani.reader.ui.anki.label
import app.honmani.reader.ui.rememberWithNotifications
import java.util.Locale

/**
 * The confirmation before a book's text is sent to Google: what it costs, what is sent, the cap.
 * Start asks for the notification permission first (the work shows its progress there).
 */
@Composable
fun AudiobookMakeDialog(
    title: String,
    state: MakeState,
    model: GeneratedAudiobookModel,
    onStarted: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val withNotifications = rememberWithNotifications()
    Dialog(onDismissRequest = onDismiss, properties = DropdownDialogProperties) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(Modifier.safeDrawingPadding().imePadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(stringResource(R.string.audiobook_make_title), style = MaterialTheme.typography.headlineSmall)
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    when {
                        state.loading -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            CircularProgressIndicator()
                            Text(stringResource(R.string.audiobook_make_checking))
                        }
                        state.problem != null ->
                            Text(stringResource(R.string.audiobook_make_key_problem, stringResource(state.problem.label())), color = MaterialTheme.colorScheme.error)
                        state.unreadable -> Text(stringResource(R.string.audiobook_make_unreadable), color = MaterialTheme.colorScheme.error)
                        else -> Details(state, model, Formatter.formatShortFileSize(context, state.bytes))
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
                    Button(
                        onClick = { withNotifications { if (model.start()) onStarted() } },
                        enabled = state.canStart,
                    ) { Text(stringResource(R.string.audiobook_make_start)) }
                }
            }
        }
    }
}

private val DropdownDialogProperties = DialogProperties(usePlatformDefaultWidth = false)

@Composable
private fun Details(state: MakeState, model: GeneratedAudiobookModel, sizeText: String) {
    val context = LocalContext.current
    val minutes = state.seconds / 60
    val length = if (minutes >= 60) stringResource(R.string.audiobook_make_hours, minutes / 60, minutes % 60) else stringResource(R.string.audiobook_make_minutes, minutes)
    Text(stringResource(R.string.audiobook_make_chars, java.text.NumberFormat.getIntegerInstance(Locale.forLanguageTag(androidx.compose.ui.text.intl.Locale.current.toLanguageTag())).format(state.chars), length))
    VoicePicker(state, model)
    if (state.needsPrice) {
        Text(stringResource(R.string.audiobook_make_price_help), style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(
            value = state.typedPrice,
            onValueChange = model::typePrice,
            label = { Text(stringResource(R.string.audiobook_make_price_field)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    state.estimateUsd?.let {
        Text(stringResource(R.string.audiobook_make_estimate, length, sizeText, usd(it)))
    }
    Text(stringResource(R.string.audiobook_make_costs), fontWeight = FontWeight.Bold)
    Text(stringResource(R.string.audiobook_make_free_tier), style = MaterialTheme.typography.bodyMedium)
    Text(stringResource(R.string.audiobook_make_sent), style = MaterialTheme.typography.bodyMedium)
    OutlinedTextField(
        value = state.capText,
        onValueChange = model::typeCap,
        label = { Text(stringResource(R.string.audiobook_make_cap)) },
        supportingText = { Text(stringResource(R.string.audiobook_make_cap_help)) },
        isError = state.overCap,
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    if (state.overCap) Text(stringResource(R.string.audiobook_make_over_cap), color = MaterialTheme.colorScheme.error)
    if (state.spaceShort) {
        Text(stringResource(R.string.audiobook_make_no_space, Formatter.formatShortFileSize(context, state.spaceNeededBytes)), color = MaterialTheme.colorScheme.error)
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().toggleable(state.mobileData, role = Role.Checkbox, onValueChange = model::setMobileData),
    ) {
        Checkbox(checked = state.mobileData, onCheckedChange = null)
        Text(stringResource(R.string.audiobook_make_mobile), modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun VoicePicker(state: MakeState, model: GeneratedAudiobookModel) {
    var open by remember { mutableStateOf(false) }
    Text(stringResource(R.string.audiobook_make_voice), style = MaterialTheme.typography.labelLarge)
    Box {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            Text(state.voiceChoice?.let { voiceLine(it) }.orEmpty())
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (voice in state.voices) {
                DropdownMenuItem(text = { Text(voiceLine(voice)) }, onClick = { open = false; model.chooseVoice(voice.name) })
            }
        }
    }
}

@Composable
private fun voiceLine(voice: VoiceChoice): String {
    val price = voice.pricePerMillion?.let { stringResource(R.string.audiobook_make_price, trimmed(it)) }
        ?: stringResource(R.string.audiobook_make_price_unknown)
    return stringResource(R.string.audiobook_make_voice_line, voice.label, price)
}

private fun trimmed(value: Double): String = if (value == Math.floor(value)) value.toLong().toString() else value.toString()
