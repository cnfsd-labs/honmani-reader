package app.honmani.reader.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.honmani.reader.R
import app.honmani.reader.lookup.RadicalIndex
import app.honmani.reader.lookup.RadicalMatch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Bottom sheet to find a kanji by its parts: radicals by stroke count above, the kanji that contain
 * all picked ones below the title. A tap on a kanji hands it to [onPick] (the caller closes the sheet).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RadicalPicker(onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val index by produceState<RadicalIndex?>(null) {
        value = withContext(Dispatchers.Default) { RadicalIndex.load(context) }
    }
    // The picked radicals as one string: every radical is a single character.
    var picked by rememberSaveable { mutableStateOf("") }
    val loaded = index
    val match = remember(loaded, picked) {
        loaded?.search(picked.map(Char::toString)) ?: RadicalMatch(emptyList(), emptySet())
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(modifier = Modifier.fillMaxHeight(0.92f)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 24.dp, end = 8.dp)) {
                Text(
                    stringResource(R.string.radical_search),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier
                        .weight(1f)
                        .semantics { heading() },
                )
                TextButton(onClick = { picked = "" }, enabled = picked.isNotEmpty()) {
                    Text(stringResource(R.string.radical_reset))
                }
            }
            if (picked.isEmpty()) {
                Text(
                    stringResource(R.string.radical_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
            } else {
                Text(
                    stringResource(R.string.radical_kanji_found, match.kanji.size),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(CELL),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = RESULT_HEIGHT)
                        .padding(horizontal = 16.dp),
                ) {
                    items(match.kanji, key = { it }) { kanji -> KanjiCell(kanji, onClick = { onPick(kanji) }) }
                }
            }
            HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
            val radicals = loaded?.radicals.orEmpty()
            LazyVerticalGrid(
                columns = GridCells.Adaptive(CELL),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 16.dp),
            ) {
                var strokes = -1
                for (radical in radicals) {
                    if (radical.strokes != strokes) {
                        strokes = radical.strokes
                        val count = strokes
                        item(key = "h$count", span = { GridItemSpan(maxLineSpan) }) {
                            Text(
                                pluralStringResource(R.plurals.radical_strokes, count, count),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .padding(start = 8.dp, top = 12.dp, bottom = 4.dp)
                                    .semantics { heading() },
                            )
                        }
                    }
                    item(key = radical.glyph) {
                        val on = radical.glyph[0] in picked
                        RadicalCell(
                            glyph = radical.glyph,
                            strokes = radical.strokes,
                            selected = on,
                            // A radical that would leave no kanji is dimmed; a picked one can always be undone.
                            enabled = on || radical.glyph in match.possible,
                            onToggle = { picked = if (on) picked.replace(radical.glyph, "") else picked + radical.glyph },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RadicalCell(glyph: String, strokes: Int, selected: Boolean, enabled: Boolean, onToggle: () -> Unit) {
    val description = stringResource(
        R.string.radical_description,
        glyph,
        pluralStringResource(R.plurals.radical_strokes, strokes, strokes),
    )
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .defaultMinSize(minWidth = CELL, minHeight = CELL)
            .padding(2.dp)
            .alpha(if (enabled) 1f else 0.3f)
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh)
            .toggleable(value = selected, enabled = enabled, role = Role.Checkbox, onValueChange = { onToggle() })
            .semantics { contentDescription = description },
    ) {
        Text(
            glyph,
            style = MaterialTheme.typography.titleLarge,
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun KanjiCell(kanji: String, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .defaultMinSize(minWidth = CELL, minHeight = CELL)
            .padding(2.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = kanji },
    ) {
        Text(kanji, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
    }
}

/** Smallest size of a radical or kanji cell; they grow with the font scale. */
private val CELL = 52.dp

/** Two and a half rows of kanji: the radicals below stay in reach. */
private val RESULT_HEIGHT = 150.dp
