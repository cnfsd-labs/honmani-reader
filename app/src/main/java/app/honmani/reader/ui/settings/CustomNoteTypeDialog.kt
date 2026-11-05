package app.honmani.reader.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.honmani.reader.R
import app.honmani.reader.anki.AnkiNoteTypeInfo
import app.honmani.reader.anki.AnkiSettings
import app.honmani.reader.anki.CardSource
import app.honmani.reader.anki.CardStyle
import app.honmani.reader.anki.FieldMapping
import app.honmani.reader.anki.MappingPresets

/**
 * Settings, Anki, Card type, "My own": the reader picks one of AnkiDroid's note types
 * ([noteTypes]) and says which of Honmani's texts goes into each of its fields. The fields are
 * pre-filled from what was saved for that note type, else from [MappingPresets]. Save needs a
 * field for the word. Nothing here touches the note type in AnkiDroid.
 */
@Composable
fun CustomNoteTypeDialog(noteTypes: List<AnkiNoteTypeInfo>, settings: AnkiSettings, onDismiss: () -> Unit) {
    val savedId = remember { settings.noteTypeId(CardStyle.CUSTOM) }
    val savedName = remember { settings.customNoteTypeName.value }
    var selectedId by rememberSaveable {
        mutableStateOf(
            (noteTypes.firstOrNull { it.id == savedId } ?: noteTypes.firstOrNull { it.name == savedName })?.id,
        )
    }
    val selected = noteTypes.firstOrNull { it.id == selectedId }
    // The edits belong to the chosen note type: choosing another starts from its saved mapping or the guess.
    val edits = remember(selected?.id) {
        mutableStateMapOf<String, CardSource>().apply {
            if (selected != null) {
                val saved = settings.fieldMapping(selected.name).restrictedTo(selected.fields)
                putAll(if (saved.sources.isNotEmpty()) saved.sources else MappingPresets.guess(selected.fields).sources)
            }
        }
    }
    val guess = selected?.let { MappingPresets.detect(it.fields) }
    val hasWord = selected != null && edits.values.any { it == CardSource.WORD }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.custom_note_type)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.custom_note_type_intro), style = MaterialTheme.typography.bodySmall)
                if (noteTypes.isEmpty()) {
                    Text(
                        text = stringResource(R.string.custom_note_type_none_found),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                } else {
                    Choice(
                        label = stringResource(R.string.custom_note_type_pick),
                        current = selected?.name ?: stringResource(R.string.custom_note_type_none_chosen),
                        description = stringResource(
                            R.string.custom_field_row,
                            stringResource(R.string.custom_note_type_pick),
                            selected?.name ?: stringResource(R.string.custom_note_type_none_chosen),
                        ),
                        options = noteTypes.map { it.name },
                        onSelect = { index -> selectedId = noteTypes[index].id },
                    )
                }
                if (selected != null) {
                    guess?.preset?.let {
                        Text(
                            text = stringResource(R.string.custom_note_type_looks_like, it.label),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    Text(
                        text = stringResource(R.string.custom_note_type_fields),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                    val emptyLabel = stringResource(R.string.custom_source_empty)
                    val sources = CardSource.entries
                    for (field in selected.fields) {
                        val current = edits[field]?.let { stringResource(it.label()) } ?: emptyLabel
                        Choice(
                            label = field,
                            current = current,
                            description = stringResource(R.string.custom_field_row, field, current),
                            options = listOf(emptyLabel) + sources.map { stringResource(it.label()) },
                            onSelect = { index ->
                                if (index == 0) edits.remove(field) else edits[field] = sources[index - 1]
                            },
                        )
                    }
                    if (!hasWord) {
                        Text(
                            text = stringResource(R.string.custom_note_type_needs_word),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = hasWord,
                onClick = {
                    if (selected != null) {
                        settings.setCustomNoteType(selected.name, selected.id, FieldMapping(edits.toMap()))
                    }
                    onDismiss()
                },
            ) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}

/** A name above a full-width button that opens a menu of [options]; [onSelect] gets the option's index. */
@Composable
private fun Choice(label: String, current: String, description: String, options: List<String>, onSelect: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column(modifier = Modifier.padding(top = 8.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Box {
            OutlinedButton(
                onClick = { open = true },
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = description },
            ) { Text(current) }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEachIndexed { index, option ->
                    DropdownMenuItem(
                        text = { Text(option) },
                        onClick = {
                            onSelect(index)
                            open = false
                        },
                    )
                }
            }
        }
    }
}

private fun CardSource.label(): Int = when (this) {
    CardSource.WORD -> R.string.custom_source_word
    CardSource.READING -> R.string.custom_source_reading
    CardSource.FURIGANA -> R.string.custom_source_furigana
    CardSource.FURIGANA_PLAIN -> R.string.custom_source_furigana_plain
    CardSource.MEANING -> R.string.custom_source_meaning
    CardSource.SENTENCE_MARKED -> R.string.custom_source_sentence_marked
    CardSource.SENTENCE_PLAIN -> R.string.custom_source_sentence_plain
    CardSource.BOOK -> R.string.custom_source_book
    CardSource.WORD_AUDIO -> R.string.custom_source_word_audio
    CardSource.SENTENCE_AUDIO -> R.string.custom_source_sentence_audio
    CardSource.PITCH -> R.string.custom_source_pitch
    CardSource.FREQUENCY -> R.string.custom_source_frequency
}
