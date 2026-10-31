package app.honmani.reader.ui.words

import android.content.Context
import android.content.Intent
import android.text.format.DateUtils
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.honmani.reader.R
import app.honmani.reader.data.words.SessionWords
import app.honmani.reader.data.words.WordEntity
import app.honmani.reader.ui.lookup.LookupActivity

/**
 * The words looked up in the latest reading session (see `ReadingSession`), each with its
 * reading and a short meaning; a tap opens the word in the dictionary.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionSheet(session: SessionWords, onDismiss: () -> Unit) {
    val context = LocalContext.current
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.navigationBarsPadding()) {
            Text(
                text = stringResource(R.string.session_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            val start = session.start
            if (start == null || session.words.isEmpty()) {
                Text(
                    text = stringResource(R.string.session_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(32.dp),
                )
            } else {
                Text(
                    text = stringResource(
                        R.string.session_summary,
                        pluralStringResource(R.plurals.session_words, session.words.size, session.words.size),
                        DateUtils.formatDateTime(
                            context,
                            start,
                            DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_ALL,
                        ),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 8.dp),
                )
                LazyColumn {
                    items(session.words, key = { it.id }) { word ->
                        SessionRow(word, onClick = { openInDictionary(context, word.expression) })
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionRow(word: WordEntity, onClick: () -> Unit) {
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
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick),
    )
}

internal fun openInDictionary(context: Context, text: String) {
    context.startActivity(
        Intent(context, LookupActivity::class.java)
            .setAction(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, text),
    )
}
