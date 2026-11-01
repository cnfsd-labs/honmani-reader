package app.honmani.reader.ui.review

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.honmani.reader.R

/**
 * The review's entrance at the top of Words: how many cards are due and how many new words the
 * session would start, and the chips for what to work on (only "Due" for now).
 */
@Composable
fun ReviewEntry(due: Int, newWords: Int, onStart: () -> Unit, modifier: Modifier = Modifier) {
    val nothing = due + newWords == 0
    val startDescription = stringResource(R.string.review_start_description, due, newWords)
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                if (due > 0) stringResource(R.string.review_card_title, due) else stringResource(R.string.review_card_title_none),
                style = MaterialTheme.typography.titleMedium,
            )
            val subtitle = when {
                nothing -> stringResource(R.string.review_card_nothing)
                newWords > 0 -> pluralStringResource(R.plurals.review_card_new, newWords, newWords)
                else -> null
            }
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            ) {
                FilterChip(selected = true, onClick = {}, label = { Text(stringResource(R.string.review_chip_due)) })
                Button(
                    onClick = onStart,
                    enabled = !nothing,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .semantics { contentDescription = startDescription },
                ) { Text(stringResource(R.string.review_start)) }
            }
        }
    }
}
