package app.honmani.reader.ui.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.honmani.reader.R
import app.honmani.reader.lookup.Furigana
import app.honmani.reader.lookup.FuriganaOverride
import app.honmani.reader.lookup.RubySegment

/**
 * The editor for a word's furigana: one field per run of kanji (or other non-kana text) of
 * [expression], prefilled with what is shown now, kana between them as fixed text, and a preview.
 * [onSave] gets the stored form ([FuriganaOverride]), or "" for "Reset". [furigana] is the
 * correction in place now.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FuriganaDialog(
    expression: String,
    reading: String,
    furigana: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val runs = remember(expression) { Furigana.runs(expression) }
    val readings = remember(expression, reading, furigana) {
        mutableStateListOf<String>().apply {
            addAll(FuriganaOverride.prefill(expression, Furigana.shown(expression, reading, furigana)))
        }
    }
    val valid = readings.all { FuriganaOverride.isReading(it.trim()) }
    val result = if (valid) FuriganaOverride.build(expression, readings.toList()) else null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.furigana_title, expression)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // The preview shows the readings that are fine so far.
                val preview = run {
                    var index = 0
                    runs.map { run ->
                        if (Furigana.isKana(run.first())) {
                            RubySegment(run)
                        } else {
                            RubySegment(run, readings[index++].trim().takeIf { FuriganaOverride.isReading(it) })
                        }
                    }
                }
                Headword(preview)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    var index = 0
                    runs.forEach { run ->
                        if (Furigana.isKana(run.first())) {
                            Text(run, style = MaterialTheme.typography.titleLarge)
                        } else {
                            val field = index++
                            val label = stringResource(R.string.furigana_field, run)
                            OutlinedTextField(
                                value = readings[field],
                                onValueChange = { readings[field] = it },
                                label = { Text(run) },
                                singleLine = true,
                                isError = !FuriganaOverride.isReading(readings[field].trim()),
                                modifier = Modifier.widthIn(min = 112.dp, max = 192.dp).semantics { contentDescription = label },
                            )
                        }
                    }
                }
                if (!valid) {
                    Text(
                        stringResource(R.string.furigana_invalid),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (furigana.isNotEmpty()) {
                    TextButton(onClick = { onSave("") }) { Text(stringResource(R.string.furigana_reset)) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { result?.let(onSave) }, enabled = result != null) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
