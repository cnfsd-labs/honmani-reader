package app.honmani.reader.ui.stats

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.honmani.reader.R
import app.honmani.reader.data.stats.DayTotal
import app.honmani.reader.data.stats.GoalProgress
import app.honmani.reader.data.stats.PeriodSummary
import app.honmani.reader.data.stats.StatsPeriod
import app.honmani.reader.data.stats.WeekSpeed
import java.text.NumberFormat
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Reading statistics over all books, for the period picked at the top: today (per book), the
 * last 7 days (per day) or all time (per week, with the reading speed's trend). In the Progress
 * tab.
 */
@Composable
fun StatisticsContent(
    modifier: Modifier = Modifier,
    viewModel: StatisticsViewModel = viewModel(factory = StatisticsViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val period by viewModel.period.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val csvFileName = stringResource(R.string.csv_stats_file_name)
    val exportCsv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) viewModel.exportCsv(uri.toString())
    }
    LaunchedEffect(viewModel) {
        viewModel.exported.collect { worked ->
            Toast.makeText(
                context,
                if (worked) R.string.csv_stats_exported else R.string.anki_export_failed,
                Toast.LENGTH_LONG,
            ).show()
        }
    }
    Column(modifier = modifier.fillMaxSize()) {
        PeriodPicker(period, viewModel::setPeriod)
        val current = state ?: return@Column
        val summary = current.summary
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            if (!summary.anyReading) {
                Text(
                    text = stringResource(R.string.stats_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            current.goal?.let { GoalCard(it) }
            Headline(summary)
            Tiles(current)
            when (summary.period) {
                StatsPeriod.TODAY -> BooksToday(summary.books, current.titles)
                StatsPeriod.WEEK -> BarChart(
                    title = stringResource(R.string.stats_day_chart),
                    bars = summary.chart,
                    label = { it.day.format(DateTimeFormatter.ofPattern("EEE, d MMM")) },
                )
                StatsPeriod.ALL -> {
                    SpeedTrend(summary.speedTrend)
                    val dateFormat = remember { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM) }
                    BarChart(
                        title = stringResource(R.string.stats_week_chart),
                        bars = summary.chart,
                        label = { stringResource(R.string.stats_week_of, it.day.format(dateFormat)) },
                    )
                }
            }
            Text(
                text = stringResource(R.string.stats_how),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = { exportCsv.launch(csvFileName) }) {
                Text(stringResource(R.string.csv_stats_export))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PeriodPicker(period: StatsPeriod, onPick: (StatsPeriod) -> Unit) {
    SingleChoiceSegmentedButtonRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        StatsPeriod.entries.forEachIndexed { index, entry ->
            SegmentedButton(
                selected = entry == period,
                onClick = { onPick(entry) },
                shape = SegmentedButtonDefaults.itemShape(index, StatsPeriod.entries.size),
                label = { Text(periodName(entry), maxLines = 1) },
            )
        }
    }
}

@Composable
private fun periodName(period: StatsPeriod) = stringResource(
    when (period) {
        StatsPeriod.TODAY -> R.string.stats_today
        StatsPeriod.WEEK -> R.string.stats_week
        StatsPeriod.ALL -> R.string.stats_total
    },
)

/** Today's reading against the daily goal, with the days in a row that reached it. */
@Composable
private fun GoalCard(goal: GoalProgress) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(R.string.stats_goal_title),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                if (goal.met) {
                    stringResource(R.string.stats_goal_met)
                } else {
                    stringResource(R.string.stats_goal_progress, (goal.secondsToday / 60).toInt(), goal.goalMinutes)
                },
                style = MaterialTheme.typography.titleLarge,
            )
            LinearProgressIndicator(progress = { goal.fraction }, modifier = Modifier.fillMaxWidth())
            if (goal.streak > 0) {
                Text(
                    pluralStringResource(R.plurals.stats_goal_streak, goal.streak, goal.streak),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** The period's reading time, large, with the characters read. */
@Composable
private fun Headline(summary: PeriodSummary) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                periodName(summary.period),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(duration(summary.seconds), style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary)
            Text(characters(summary.characters), style = MaterialTheme.typography.titleMedium)
        }
    }
}

/** Four numbers for the period: speed first, then what fits the period. */
@Composable
private fun Tiles(state: StatisticsUiState) {
    val summary = state.summary
    val speedLabel = stringResource(if (summary.period == StatsPeriod.ALL) R.string.stats_speed_recent else R.string.stats_speed)
    val speedTile: @Composable (Modifier) -> Unit = { modifier ->
        StatTile(
            label = speedLabel,
            value = summary.charactersPerHour?.let { speed(it) } ?: "–",
            detail = comparison(summary),
            modifier = modifier,
        )
    }
    val second: @Composable (Modifier) -> Unit = { modifier ->
        if (summary.period == StatsPeriod.TODAY) {
            StatTile(
                label = stringResource(R.string.stats_streak),
                value = pluralStringResource(R.plurals.stats_days, summary.streak, summary.streak),
                modifier = modifier,
            )
        } else {
            StatTile(
                label = stringResource(R.string.stats_days_read),
                value = if (summary.period == StatsPeriod.WEEK) {
                    stringResource(R.string.stats_days_of_week, summary.daysRead)
                } else {
                    number(summary.daysRead.toLong())
                },
                modifier = modifier,
            )
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.height(IntrinsicSize.Min)) {
        speedTile(Modifier.weight(1f).fillMaxHeight())
        second(Modifier.weight(1f).fillMaxHeight())
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.height(IntrinsicSize.Min)) {
        StatTile(
            label = stringResource(R.string.stats_lookups),
            value = number(state.lookups.toLong()),
            modifier = Modifier.weight(1f).fillMaxHeight(),
        )
        StatTile(
            label = stringResource(R.string.stats_books_finished),
            value = number(state.booksFinished.toLong()),
            modifier = Modifier.weight(1f).fillMaxHeight(),
        )
    }
}

/** How the speed compares with an earlier span: "▲ 12% vs. the 7 days before". */
@Composable
private fun comparison(summary: PeriodSummary): String? {
    val now = summary.charactersPerHour ?: return null
    val before = summary.earlierCharactersPerHour?.takeIf { it > 0 } ?: return null
    val span = stringResource(
        when (summary.period) {
            StatsPeriod.TODAY -> R.string.stats_vs_last_week
            StatsPeriod.WEEK -> R.string.stats_vs_week_before
            StatsPeriod.ALL -> R.string.stats_vs_start
        },
    )
    val percent = ((now - before) * 100.0 / before).roundToInt()
    return when {
        percent > 0 -> stringResource(R.string.stats_faster, percent, span)
        percent < 0 -> stringResource(R.string.stats_slower, -percent, span)
        else -> stringResource(R.string.stats_same_speed, span)
    }
}

/** A headline number with its label and an optional second line. */
@Composable
private fun StatTile(label: String, value: String, modifier: Modifier = Modifier, detail: String? = null) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (detail != null) {
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Today's reading per book. */
@Composable
private fun BooksToday(books: List<Pair<Long, DayTotal>>, titles: Map<Long, String>) {
    if (books.isEmpty()) return
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(modifier = Modifier.padding(vertical = 8.dp)) {
            Text(
                stringResource(R.string.stats_books_today),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            for ((bookId, total) in books) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(
                        titles[bookId] ?: "–",
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${duration(total.seconds)} · ${characters(total.characters)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * Minutes read per bar: thin bars in the accent colour from a quiet baseline; tapping a bar
 * shows its [label] and values above the chart (the latest bar's by default).
 */
@Composable
private fun BarChart(title: String, bars: List<DayTotal>, label: @Composable (DayTotal) -> String) {
    if (bars.isEmpty()) return
    var selected by rememberSaveable(bars.size) { mutableStateOf(bars.lastIndex) }
    val bar = bars[selected.coerceIn(bars.indices)]
    val color = MaterialTheme.colorScheme.primary
    val baseline = MaterialTheme.colorScheme.outlineVariant
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                text = "${label(bar)} · ${duration(bar.seconds)} · ${characters(bar.characters)}",
                style = MaterialTheme.typography.bodyMedium,
            )
            val maxMinutes = bars.maxOf { it.seconds / 60f }.coerceAtLeast(1f)
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .pointerInput(bars) {
                        detectTapGestures { offset ->
                            val slot = size.width.toFloat() / bars.size
                            selected = (offset.x / slot).toInt().coerceIn(bars.indices)
                        }
                    },
            ) {
                val slot = size.width / bars.size
                val gap = 2.dp.toPx()
                // Thin bars, at least 2 dp apart.
                val width = minOf(slot * 0.6f, slot - gap, 24.dp.toPx())
                bars.forEachIndexed { i, total ->
                    val height = size.height * (total.seconds / 60f) / maxMinutes
                    if (height <= 0f) return@forEachIndexed
                    val left = i * slot + (slot - width) / 2
                    val barColor = if (i == selected) color else color.copy(alpha = 0.55f)
                    roundedTopBar(left, width, height.coerceAtLeast(2.dp.toPx()), barColor)
                }
                drawLine(baseline, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1.dp.toPx())
            }
        }
    }
}

/**
 * Reading speed per week as a line, so improvement shows; weeks with too little reading are
 * gaps. Tapping a point shows its week and speed (the latest by default).
 */
@Composable
private fun SpeedTrend(weeks: List<WeekSpeed>) {
    val measured = weeks.withIndex().filter { it.value.charactersPerHour != null }
    var selected by rememberSaveable(weeks.size) { mutableStateOf(measured.lastOrNull()?.index ?: -1) }
    val color = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val dateFormat = remember { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM) }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.stats_speed_trend),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val week = weeks.getOrNull(selected)
            val weekSpeed = week?.charactersPerHour
            if (week == null || weekSpeed == null) {
                Text(
                    stringResource(R.string.stats_speed_trend_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }
            Text(
                "${stringResource(R.string.stats_week_of, week.start.format(dateFormat))} · ${speed(weekSpeed)}",
                style = MaterialTheme.typography.bodyMedium,
            )
            val values = measured.map { it.value.charactersPerHour!!.toFloat() }
            // Some room above and below, so a flat line sits in the middle.
            val low = values.min() * 0.9f
            val high = (values.max() * 1.1f).coerceAtLeast(low + 1f)
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .pointerInput(weeks) {
                        detectTapGestures { offset ->
                            val slot = size.width.toFloat() / weeks.size
                            val tapped = (offset.x / slot).toInt().coerceIn(weeks.indices)
                            // The nearest week with a speed.
                            measured.minByOrNull { abs(it.index - tapped) }?.let { selected = it.index }
                        }
                    },
            ) {
                val slot = size.width / weeks.size
                fun point(index: Int, value: Float) = Offset(
                    x = index * slot + slot / 2,
                    y = size.height * (1 - (value - low) / (high - low)),
                )
                drawLine(grid, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1.dp.toPx())
                var previous: Offset? = null
                var previousIndex = -2
                for ((index, value) in measured) {
                    val here = point(index, value.charactersPerHour!!.toFloat())
                    // Consecutive weeks are joined; a gap stays a gap.
                    if (previous != null && index == previousIndex + 1) {
                        drawLine(color, previous, here, strokeWidth = 2.dp.toPx())
                    }
                    previous = here
                    previousIndex = index
                }
                for ((index, value) in measured) {
                    val here = point(index, value.charactersPerHour!!.toFloat())
                    drawCircle(color, radius = if (index == selected) 5.dp.toPx() else 3.dp.toPx(), center = here)
                }
            }
        }
    }
}

/** A bar standing on the baseline, its top corners rounded by 4 dp. */
private fun DrawScope.roundedTopBar(left: Float, width: Float, height: Float, color: Color) {
    val radius = minOf(4.dp.toPx(), width / 2, height)
    val top = size.height - height
    val path = Path().apply {
        addRoundRect(
            RoundRect(
                left = left,
                top = top,
                right = left + width,
                bottom = size.height,
                topLeftCornerRadius = CornerRadius(radius),
                topRightCornerRadius = CornerRadius(radius),
                bottomLeftCornerRadius = CornerRadius.Zero,
                bottomRightCornerRadius = CornerRadius.Zero,
            ),
        )
    }
    drawPath(path, color)
}

/** "2 h 15 min", "45 min", "under a minute". */
@Composable
internal fun duration(seconds: Long): String {
    val minutes = seconds / 60
    return when {
        seconds == 0L -> stringResource(R.string.stats_no_time)
        minutes == 0L -> stringResource(R.string.stats_under_a_minute)
        minutes < 60 -> stringResource(R.string.stats_minutes, minutes)
        else -> stringResource(R.string.stats_hours_minutes, minutes / 60, minutes % 60)
    }
}

@Composable
internal fun characters(count: Long): String =
    pluralStringResource(R.plurals.stats_characters, count.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), number(count))

@Composable
internal fun speed(charactersPerHour: Long): String = stringResource(R.string.stats_speed_value, number(charactersPerHour))

internal fun number(value: Long): String = NumberFormat.getIntegerInstance().format(value)
