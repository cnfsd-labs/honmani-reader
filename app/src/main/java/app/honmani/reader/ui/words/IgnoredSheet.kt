package app.honmani.reader.ui.words

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.honmani.reader.R
import app.honmani.reader.data.words.WordEntity

/** The ignored words ([WordEntity.ignored]), each with "Stop ignoring", which brings it back as it was. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IgnoredSheet(words: List<WordEntity>, onUnignore: (id: Long) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.navigationBarsPadding()) {
            Text(
                text = stringResource(R.string.ignored_words_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            Text(
                text = stringResource(R.string.ignored_words_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 8.dp),
            )
            if (words.isEmpty()) {
                Text(
                    text = stringResource(R.string.ignored_words_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(32.dp),
                )
            } else {
                LazyColumn {
                    items(words, key = { it.id }) { word ->
                        val title = if (word.reading.isNotEmpty() && word.reading != word.expression) {
                            "${word.expression}  ${word.reading}"
                        } else {
                            word.expression
                        }
                        ListItem(
                            headlineContent = { Text(title) },
                            supportingContent = if (word.gloss.isNotBlank()) {
                                { Text(word.gloss, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                            } else {
                                null
                            },
                            trailingContent = {
                                TextButton(onClick = { onUnignore(word.id) }) { Text(stringResource(R.string.action_unignore_word)) }
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}
