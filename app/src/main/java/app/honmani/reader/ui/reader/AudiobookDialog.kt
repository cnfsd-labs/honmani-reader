package app.honmani.reader.ui.reader

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.honmani.reader.R

/** Where the reader is in choosing or managing a book's audiobook. */
enum class AudiobookStep {
    /** Nothing chosen yet (or changing the files): explains and asks for the audio. */
    INTRO,

    /** The audio is chosen: asks for the subtitles. */
    SUBTITLES,

    /** The book has an audiobook: play, change or remove. */
    MANAGE,
}

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
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = menuReturnDialogProperties(onBack),
        title = { Text(stringResource(R.string.audiobook_title)) },
        text = {
            MenuReturnBack(onBack)
            when (step) {
                AudiobookStep.INTRO -> Text(stringResource(R.string.audiobook_intro))
                AudiobookStep.SUBTITLES -> Text(stringResource(R.string.audiobook_next_step))
                AudiobookStep.MANAGE -> Text(stringResource(R.string.audiobook_menu_paired))
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
                TextButton(onClick = onRemove) { Text(stringResource(R.string.audiobook_remove)) }
            } else {
                TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
            }
        },
    )
}
