package app.honmani.reader.ui.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import app.honmani.reader.R
import app.honmani.reader.data.examples.Example
import app.honmani.reader.data.examples.ExampleSentences

/** Where the lookup panel finds example sentences; null (previews, tests) shows none. */
val LocalExampleSentences = staticCompositionLocalOf<ExampleSentences?> { null }

/**
 * "Examples (n)" below a word's meanings: collapsed to one row, a tap shows the sentences
 * (Japanese, then English smaller and dimmer) and another tap collapses them. Nothing at all
 * while the sentences are off or the word has none.
 */
@Composable
internal fun ExamplesRow(expression: String, reading: String, onLookup: (String) -> Unit = {}) {
    val sentences = LocalExampleSentences.current ?: return
    val examples by produceState<List<Example>>(emptyList(), sentences, expression, reading) {
        value = sentences.find(expression, reading)
    }
    if (examples.isEmpty()) return
    var expanded by rememberSaveable(expression, reading) { mutableStateOf(false) }
    val title = stringResource(R.string.lookup_examples, examples.size)
    val state = stringResource(if (expanded) R.string.lookup_examples_expanded else R.string.lookup_examples_collapsed)
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clickable(role = Role.Button) { expanded = !expanded }
                .semantics { stateDescription = state }
                .heightIn(min = 48.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        if (expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                examples.forEach { example ->
                    Column {
                        ScanText(AnnotatedString(example.japanese), MaterialTheme.typography.bodyLarge, onScan = onLookup)
                        Text(
                            example.english,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
