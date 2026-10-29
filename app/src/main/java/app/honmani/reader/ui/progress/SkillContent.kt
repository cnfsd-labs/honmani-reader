package app.honmani.reader.ui.progress

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.honmani.reader.R
import app.honmani.reader.data.stats.Skill
import app.honmani.reader.data.stats.SkillEstimate
import app.honmani.reader.data.stats.SkillPoint
import app.honmani.reader.ui.ProgressBar
import app.honmani.reader.ui.difficultyBand
import app.honmani.reader.ui.stats.duration
import app.honmani.reader.ui.stats.number

/** The reader's skill on the books' difficulty scale, its course over the last weeks, and books that fit it. */
@Composable
internal fun SkillContent(
    modifier: Modifier = Modifier,
    onOpenBook: (Long) -> Unit = {},
    viewModel: ProgressViewModel = viewModel(factory = ProgressViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val current = state ?: return
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        SkillCard(current.skill)
        if (current.skill.trend.any { it.skill != null }) SkillTrend(current.skill.trend)
        if (current.fitting.isNotEmpty()) FittingBooks(current.fitting, onOpenBook)
        Text(
            text = stringResource(R.string.skill_how),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SkillCard(skill: SkillEstimate) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
        ) {
            val value = skill.current
            if (value != null) {
                Text(
                    stringResource(R.string.skill_value, value),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(difficultyBand(value), style = MaterialTheme.typography.titleMedium)
                val vocabulary = skill.vocabulary
                if (vocabulary != null && skill.fluency != null) {
                    Text(
                        stringResource(R.string.skill_parts, vocabulary.score, number(vocabulary.estimate.words.toLong()), skill.fluency),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                } else if (skill.gradedCharacters < Skill.VOCABULARY_MIN_CHARACTERS) {
                    Text(
                        stringResource(
                            R.string.skill_vocabulary_waiting,
                            number(Skill.VOCABULARY_MIN_CHARACTERS),
                            number(skill.gradedCharacters),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    stringResource(R.string.skill_based_on, duration(skill.gradedSeconds)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(stringResource(R.string.progress_skill), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(
                        R.string.skill_measuring,
                        skill.gradedSeconds / 60,
                        Skill.MIN_SECONDS / 60,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ProgressBar(
                    progress = { (skill.gradedSeconds.toFloat() / Skill.MIN_SECONDS).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** The skill at the end of each of the last weeks, as a line; weeks before the first measurement are left out. */
@Composable
private fun SkillTrend(trend: List<SkillPoint>) {
    val values = trend.mapNotNull { it.skill }
    val low = (values.min() - 5).coerceAtLeast(0)
    val high = (values.max() + 5).coerceAtMost(100)
    val color = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                pluralStringResource(R.plurals.skill_trend, trend.size, trend.size),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Canvas(
                    modifier = Modifier
                        .weight(1f)
                        .height(120.dp),
                ) {
                    fun point(index: Int, value: Int) = Offset(
                        x = if (trend.size > 1) size.width * index / (trend.size - 1) else size.width / 2,
                        y = size.height * (1f - (value - low).toFloat() / (high - low).coerceAtLeast(1)),
                    )
                    drawLine(grid, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1.dp.toPx())
                    var previous: Offset? = null
                    trend.forEachIndexed { index, week ->
                        val value = week.skill ?: return@forEachIndexed
                        val here = point(index, value)
                        previous?.let { drawLine(color, it, here, strokeWidth = 2.dp.toPx()) }
                        drawCircle(color, radius = 3.dp.toPx(), center = here)
                        previous = here
                    }
                }
                Column(
                    verticalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier
                        .height(120.dp)
                        .padding(start = 8.dp),
                ) {
                    Text("$high", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("$low", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun FittingBooks(books: List<FittingBook>, onOpen: (Long) -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(modifier = Modifier.padding(vertical = 12.dp)) {
            Text(
                stringResource(R.string.skill_fitting),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            // Each row opens its book.
            for (book in books) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpen(book.id) }
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                ) {
                    Text(
                        book.title,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        stringResource(R.string.book_difficulty, book.difficulty),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 12.dp),
                    )
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
            }
        }
    }
}
