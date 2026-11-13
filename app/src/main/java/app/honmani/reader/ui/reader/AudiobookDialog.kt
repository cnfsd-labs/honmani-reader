package app.honmani.reader.ui.reader

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import app.honmani.reader.R
import app.honmani.reader.readaloud.generate.AudiobookJobState
import app.honmani.reader.readaloud.generate.MakerStatus
import java.util.Locale

/** Where the reader is in choosing or managing a book's audiobook. */
enum class AudiobookStep {
    /** Nothing chosen yet (or changing the files): explains and asks for the audio. */
    INTRO,

    /** The audio is chosen: asks for the subtitles. */
    SUBTITLES,

    /** The book has an audiobook: play, change or remove. */
    MANAGE,
}

/** What the manage step can ask for an audiobook made with a Google voice. */
class GeneratedActions(
    val onCancel: () -> Unit,
    /** Continues after a stop or cancel; the text is a new cap when the cap stopped it. */
    val onContinue: (newCapText: String?) -> Unit,
    /** Deletes the partial or finished audio, and with it the pairing. */
    val onDelete: () -> Unit,
)

@Composable
fun AudiobookDialog(
    step: AudiobookStep,
    onChooseAudio: () -> Unit,
    onChooseSubtitles: () -> Unit,
    onPlay: () -> Unit,
    onChange: () -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
    /** Back (not a tap outside): returns to the reader menu; null closes like a tap outside. */
    onBack: (() -> Unit)? = null,
    /** Why the book can't get a Google voice audiobook (null: it can); the button opens [onMake]. */
    makeBlock: MakeBlock? = null,
    onMake: () -> Unit = {},
    /** The audiobook made with a Google voice, if the book has one (or one in the making). */
    generated: GeneratedManage? = null,
    generatedActions: GeneratedActions? = null,
    /** Saves the audio and subtitles into a folder the reader picks; null: nothing to export (no button). */
    onExport: (() -> Unit)? = null,
) {
    if (step == AudiobookStep.MANAGE && generated != null && generatedActions != null) {
        GeneratedManageDialog(generated, generatedActions, onPlay, onDismiss, onBack, onExport.takeIf { generated.partial == null })
        return
    }
    var keyHelp by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text(stringResource(R.string.audiobook_remove_title)) },
            text = { Text(stringResource(R.string.audiobook_remove_text)) },
            confirmButton = {
                TextButton(onClick = { confirmRemove = false; onRemove() }) { Text(stringResource(R.string.audiobook_remove)) }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = menuReturnDialogProperties(onBack),
        title = { Text(stringResource(R.string.audiobook_title)) },
        text = {
            MenuReturnBack(onBack)
            when (step) {
                AudiobookStep.INTRO -> Column {
                    Text(stringResource(R.string.audiobook_intro))
                    Spacer(Modifier.height(16.dp))
                    OutlinedButton(
                        onClick = { if (makeBlock == MakeBlock.NO_KEY) keyHelp = true else onMake() },
                        enabled = makeBlock != MakeBlock.EPUB_ONLY,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.audiobook_make)) }
                    if (makeBlock == MakeBlock.EPUB_ONLY) {
                        Text(stringResource(R.string.audiobook_make_epub_only), style = MaterialTheme.typography.bodySmall)
                    }
                    if (keyHelp && makeBlock == MakeBlock.NO_KEY) {
                        Spacer(Modifier.height(8.dp))
                        Text(stringResource(R.string.audiobook_make_no_key), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                AudiobookStep.SUBTITLES -> Text(stringResource(R.string.audiobook_next_step))
                AudiobookStep.MANAGE -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.audiobook_menu_paired))
                    if (onExport != null) ExportButton(onExport)
                }
            }
        },
        confirmButton = {
            when (step) {
                AudiobookStep.INTRO -> TextButton(onClick = onChooseAudio) { Text(stringResource(R.string.audiobook_choose_audio)) }
                AudiobookStep.SUBTITLES -> TextButton(onClick = onChooseSubtitles) { Text(stringResource(R.string.audiobook_choose_subtitles)) }
                AudiobookStep.MANAGE -> TextButton(onClick = onPlay) { Text(stringResource(R.string.audiobook_play)) }
            }
        },
        dismissButton = {
            if (step == AudiobookStep.MANAGE) {
                TextButton(onClick = onChange) { Text(stringResource(R.string.audiobook_change)) }
                TextButton(onClick = { confirmRemove = true }) { Text(stringResource(R.string.audiobook_remove)) }
            } else {
                TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
            }
        },
    )
}

@Composable
private fun ExportButton(onExport: () -> Unit) {
    OutlinedButton(onClick = onExport, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.audiobook_export)) }
}

/** The manage step of a generated audiobook: size, where the work is, and what can be done about it. */
@Composable
private fun GeneratedManageDialog(
    generated: GeneratedManage,
    actions: GeneratedActions,
    onPlay: () -> Unit,
    onDismiss: () -> Unit,
    onBack: (() -> Unit)?,
    onExport: (() -> Unit)?,
) {
    val context = LocalContext.current
    val size = Formatter.formatShortFileSize(context, generated.sizeBytes)
    val partial = generated.partial
    val job = generated.job
    val stoppedForCap = job is AudiobookJobState.Stopped && job.capReached
    var capText by remember(partial?.capUsd, stoppedForCap) { mutableStateOf(partial?.takeIf { stoppedForCap }?.let { GeneratedManage.raisedCapText(it) }.orEmpty()) }
    var confirmDelete by remember { mutableStateOf(false) }
    val working = job is AudiobookJobState.Running || job is AudiobookJobState.Waiting || job is AudiobookJobState.Paused
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.audiobook_generated_delete_title)) },
            text = { Text(stringResource(R.string.audiobook_generated_delete_text)) },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; actions.onDelete() }) {
                    Text(stringResource(if (partial != null) R.string.audiobook_generated_delete_partial else R.string.audiobook_generated_delete_short))
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = menuReturnDialogProperties(onBack),
        title = { Text(stringResource(R.string.audiobook_title)) },
        text = {
            MenuReturnBack(onBack)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (partial == null) {
                    Text(stringResource(R.string.audiobook_generated_finished))
                } else {
                    Text(progressText(generated, partial))
                    when {
                        job is AudiobookJobState.Paused || job is AudiobookJobState.Stopped -> Text(
                            if (job is AudiobookJobState.Paused) job.message else (job as AudiobookJobState.Stopped).message,
                            color = MaterialTheme.colorScheme.error,
                        )
                        else -> Unit
                    }
                    if (stoppedForCap) {
                        OutlinedTextField(
                            value = capText,
                            onValueChange = { capText = it },
                            label = { Text(stringResource(R.string.audiobook_generated_new_cap)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                Text(stringResource(R.string.audiobook_generated_size, size))
                if (onExport != null) ExportButton(onExport)
            }
        },
        confirmButton = {
            when {
                partial == null -> TextButton(onClick = onPlay) { Text(stringResource(R.string.audiobook_play)) }
                working -> TextButton(onClick = actions.onCancel) { Text(stringResource(R.string.audiobook_generated_cancel)) }
                stoppedForCap -> TextButton(
                    onClick = { actions.onContinue(capText) },
                    enabled = (MakeState.parseDecimal(capText) ?: 0.0) > partial.capUsd,
                ) { Text(stringResource(R.string.audiobook_generated_raise)) }
                else -> TextButton(onClick = { actions.onContinue(null) }) { Text(stringResource(R.string.audiobook_generated_continue)) }
            }
        },
        dismissButton = {
            TextButton(onClick = { confirmDelete = true }) {
                Text(
                    if (partial == null) stringResource(R.string.audiobook_generated_delete, size)
                    else stringResource(R.string.audiobook_generated_delete_partial),
                )
            }
        },
    )
}

@Composable
private fun progressText(generated: GeneratedManage, partial: MakerStatus.Partial): String {
    val job = generated.job
    val running = job as? AudiobookJobState.Running
    val done = running?.progress?.done ?: partial.done
    val total = running?.progress?.total ?: partial.total
    val spent = usd(running?.progress?.spentUsd ?: partial.spentUsd)
    return when (job) {
        is AudiobookJobState.Running -> stringResource(R.string.audiobook_generated_progress, done, total, spent, usd(partial.capUsd))
        is AudiobookJobState.Waiting, is AudiobookJobState.Paused -> stringResource(R.string.audiobook_generated_waiting, done, total, spent)
        else -> stringResource(R.string.audiobook_generated_cancelled, done, total, spent)
    }
}

/** A dollar amount as the maker's notification shows it. */
internal fun usd(value: Double) = "$" + String.format(Locale.ROOT, "%.2f", value)
