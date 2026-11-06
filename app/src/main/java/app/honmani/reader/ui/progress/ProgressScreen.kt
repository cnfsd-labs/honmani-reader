package app.honmani.reader.ui.progress

import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.honmani.reader.R
import app.honmani.reader.data.review.ReviewSummary
import app.honmani.reader.data.stats.Milestone
import app.honmani.reader.data.stats.MilestoneKind
import app.honmani.reader.data.stats.Progression
import app.honmani.reader.data.stats.ReadingLevel
import app.honmani.reader.ui.InfoButton
import app.honmani.reader.ui.InfoDialog
import app.honmani.reader.ui.ProgressBar
import app.honmani.reader.ui.nextPageInCycle
import app.honmani.reader.ui.stats.StatTile
import app.honmani.reader.ui.stats.StatisticsContent
import app.honmani.reader.ui.stats.duration
import app.honmani.reader.ui.stats.characters
import app.honmani.reader.ui.stats.number
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.launch

/** Sections of the Progress tab. */
private enum class ProgressSection(val label: Int) {
    OVERVIEW(R.string.progress_overview),
    MILESTONES(R.string.progress_milestones),
    STATISTICS(R.string.stats_title),
}

/** The Progress tab: level, goal and skill; the milestones; and the reading statistics. Swipe between them. */
@Composable
fun ProgressTab(bottomBar: @Composable () -> Unit, onOpenBook: (Long) -> Unit = {}, pageCycle: Int = 0) {
    val pager = rememberPagerState { ProgressSection.entries.size }
    val scope = rememberCoroutineScope()
    // A tap on the Progress tab while it is open: on to the next section, the first after the last.
    val firstPageCycle = remember { pageCycle }
    LaunchedEffect(pageCycle) {
        if (pageCycle != firstPageCycle) pager.animateScrollToPage(nextPageInCycle(pager.currentPage, ProgressSection.entries.size))
    }
    // Back from Milestones or Statistics goes to the overview first, then on to the Library.
    BackHandler(enabled = pager.currentPage != 0) { scope.launch { pager.animateScrollToPage(0) } }
    Scaffold(
        // No title: the bottom bar names the tab.
        topBar = {
            Surface(color = TabRowDefaults.primaryContainerColor) {
                PrimaryTabRow(
                    selectedTabIndex = pager.currentPage,
                    modifier = Modifier.windowInsetsPadding(WindowInsets.statusBars),
                ) {
                    ProgressSection.entries.forEachIndexed { index, entry ->
                        Tab(
                            selected = index == pager.currentPage,
                            onClick = { scope.launch { pager.animateScrollToPage(index) } },
                            text = { Text(stringResource(entry.label), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        )
                    }
                }
            }
        },
        bottomBar = bottomBar,
    ) { padding ->
        HorizontalPager(
            state = pager,
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) { page ->
            when (ProgressSection.entries[page]) {
                ProgressSection.OVERVIEW -> ProgressContent(onOpenBook = onOpenBook)
                ProgressSection.MILESTONES -> MilestonesContent()
                ProgressSection.STATISTICS -> StatisticsContent()
            }
        }
    }
}

/** The reading level, the daily goal, the skill and the reviews. */
@Composable
private fun ProgressContent(
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
        if (!current.anyReading) {
            Text(
                text = stringResource(R.string.progress_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LevelCard(current)
        GoalCard(current.goal)
        SkillSection(current, onOpenBook)
        if (current.reviews.any) ReviewTiles(current.reviews)
    }
}

/** The next milestones, the reading calendar and all milestones. */
@Composable
private fun MilestonesContent(
    modifier: Modifier = Modifier,
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
        if (current.next.isNotEmpty()) NextMilestones(current.next)
        ReadingCalendar(current.calendar, current.today)
        AllMilestones(current.milestones, recentSince = current.today.minusDays(RECENT_DAYS))
    }
}

/** "Level 14" beside a ring that fills towards the next level, with the streak below. */
@Composable
private fun LevelCard(state: ProgressUiState) {
    val level = state.level
    var explaining by rememberSaveable { mutableStateOf(false) }
    if (explaining) {
        InfoDialog(
            stringResource(R.string.progress_level_title),
            stringResource(R.string.progress_level_how),
            onDismiss = { explaining = false },
        )
    }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                LevelRing(level)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.progress_level, level.level),
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(characters(level.characters), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.progress_to_next, number(level.nextLevelAt - level.characters), level.level + 1),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                InfoButton(onClick = { explaining = true })
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    stringResource(
                        R.string.progress_streak,
                        pluralStringResource(R.plurals.stats_days, state.streak, state.streak),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    stringResource(R.string.progress_this_week, state.daysThisWeek),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Reviews today, the time they took, retention over the last 30 days and the words in review. */
@Composable
internal fun ReviewTiles(reviews: ReviewSummary) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.height(IntrinsicSize.Min)) {
        StatTile(
            label = stringResource(R.string.progress_reviews_today),
            value = number(reviews.today.toLong()),
            detail = pluralStringResource(R.plurals.progress_reviews_new, reviews.newToday, reviews.newToday),
            modifier = Modifier.weight(1f).fillMaxHeight(),
        )
        StatTile(
            label = stringResource(R.string.progress_reviews_time),
            value = duration(reviews.todayMillis / 1000),
            detail = stringResource(R.string.progress_reviews_time_note),
            modifier = Modifier.weight(1f).fillMaxHeight(),
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.height(IntrinsicSize.Min)) {
        StatTile(
            label = stringResource(R.string.progress_retention),
            value = reviews.retention?.let { stringResource(R.string.progress_retention_value, Math.round(it * 100).toInt()) } ?: "–",
            detail = if (reviews.retention != null) {
                pluralStringResource(R.plurals.progress_retention_note, reviews.retentionReviews, reviews.retentionReviews)
            } else {
                stringResource(R.string.progress_retention_none)
            },
            modifier = Modifier.weight(1f).fillMaxHeight(),
        )
        StatTile(
            label = stringResource(R.string.progress_words_in_review),
            value = number(reviews.inReview.toLong()),
            modifier = Modifier.weight(1f).fillMaxHeight(),
        )
    }
}

@Composable
private fun LevelRing(level: ReadingLevel) {
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val fill = MaterialTheme.colorScheme.primary
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(88.dp)) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = 8.dp.toPx()
            val inset = stroke / 2
            val arcSize = Size(size.width - stroke, size.height - stroke)
            val topLeft = Offset(inset, inset)
            drawArc(track, 0f, 360f, useCenter = false, topLeft = topLeft, size = arcSize, style = Stroke(stroke))
            if (level.fraction > 0f) {
                drawArc(
                    fill,
                    startAngle = -90f,
                    sweepAngle = 360f * level.fraction,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
            }
        }
        Text("${level.level}", style = MaterialTheme.typography.headlineSmall)
    }
}

/** The closest goals, each with how far along it is. */
@Composable
private fun NextMilestones(next: List<Milestone>) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
        ) {
            Text(
                stringResource(R.string.progress_next),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            for (milestone in next) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(milestoneTitle(milestone), style = MaterialTheme.typography.bodyLarge)
                    ProgressBar(progress = { milestone.fraction }, modifier = Modifier.fillMaxWidth())
                    Text(
                        stringResource(R.string.progress_of_goal, number(milestone.current), number(milestone.goal)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * Every milestone by kind: reached ones with their date, the others greyed with their goal. Each
 * kind fills its rows evenly (seven goals as four and three), so none sits alone in a row.
 */
@Composable
private fun AllMilestones(milestones: List<Milestone>, recentSince: LocalDate) {
    val dateFormat = remember { DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT) }
    var explaining by rememberSaveable { mutableStateOf(false) }
    if (explaining) {
        InfoDialog(stringResource(R.string.progress_milestones), stringResource(R.string.progress_milestones_how), onDismiss = { explaining = false })
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.progress_milestones),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            InfoButton(onClick = { explaining = true })
        }
        BoxWithConstraints {
            // As many tiles in a row as fit a goal and a short date, also with large text.
            val minTile = MIN_TILE_WIDTH * LocalDensity.current.fontScale.coerceAtLeast(1f)
            val maxColumns = ((maxWidth + TILE_SPACING) / (minTile + TILE_SPACING)).toInt().coerceAtLeast(2)
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                for ((kind, goals) in milestones.groupBy { it.kind }) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(stringResource(kindName(kind)), style = MaterialTheme.typography.titleSmall)
                        val columns = Progression.columns(goals.size, maxColumns)
                        for (row in goals.chunked(columns)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(TILE_SPACING)) {
                                for (milestone in row) {
                                    MilestoneTile(
                                        milestone,
                                        dateFormat,
                                        recent = milestone.reachedOn?.let { it >= recentSince } == true,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                                // The last row's tiles keep the width of the others.
                                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MilestoneTile(milestone: Milestone, dateFormat: DateTimeFormatter, recent: Boolean, modifier: Modifier = Modifier) {
    val date = milestone.reachedOn?.format(dateFormat)
    val title = milestoneTitle(milestone)
    val description = date?.let { "$title, ${stringResource(R.string.progress_reached_on, it)}" } ?: title
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (milestone.reached) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
        contentColor = if (milestone.reached) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.outline
        },
        modifier = modifier
            .then(if (recent) Modifier.recentGlow(MaterialTheme.colorScheme.primary) else Modifier)
            .clearAndSetSemantics { contentDescription = description },
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(
                goalLabel(milestone),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(date ?: "–", style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
    }
}

/**
 * A border that slowly brightens and fades, for milestones reached in the last days; a still
 * border when the phone's animations are off.
 */
@Composable
private fun Modifier.recentGlow(color: Color): Modifier {
    val context = LocalContext.current
    val animate = remember(context) {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
    }
    val alpha: State<Float> = if (animate) {
        rememberInfiniteTransition(label = "milestone glow").animateFloat(
            initialValue = 0.35f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(GLOW_PERIOD_MS, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "milestone glow alpha",
        )
    } else {
        remember { mutableFloatStateOf(1f) }
    }
    // Read only while drawing, so the glow redraws without recomposing.
    return drawWithContent {
        drawContent()
        drawRoundRect(
            color = color.copy(alpha = alpha.value),
            cornerRadius = CornerRadius(12.dp.toPx()),
            style = Stroke(width = 2.dp.toPx()),
        )
    }
}

/**
 * Reading days of the last year, a column per week (Monday on top), darker the longer the day's
 * reading. As many weeks as fit the width are shown (no sideways scrolling: that would block the
 * page swipe); the arrows step to older and newer weeks.
 */
@Composable
private fun ReadingCalendar(calendar: Map<LocalDate, Long>, today: LocalDate) {
    val thisMonday = today.with(DayOfWeek.MONDAY)
    val firstMonday = thisMonday.minusWeeks(CALENDAR_WEEKS - 1L)
    val empty = MaterialTheme.colorScheme.surfaceContainerHighest
    val fill = MaterialTheme.colorScheme.primary
    val days = calendar.count { (day, seconds) -> day >= firstMonday && seconds >= 60 }
    // How many weeks the newest shown week is from this one.
    var newerBy by rememberSaveable { mutableIntStateOf(0) }
    val monthFormat = remember { DateTimeFormatter.ofPattern("MMM yyyy") }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                pluralStringResource(R.plurals.progress_calendar, days, days),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val weeks = CalendarWindow.weeks(maxWidth.value, MIN_CELL.value, GAP.value, CALENDAR_WEEKS)
                val offset = newerBy.coerceIn(0, CALENDAR_WEEKS - weeks)
                val lastMonday = thisMonday.minusWeeks(offset.toLong())
                val shownFirst = lastMonday.minusWeeks(weeks - 1L)
                val cellDp = (maxWidth - GAP * (weeks - 1)) / weeks
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { newerBy = (offset + weeks).coerceAtMost(CALENDAR_WEEKS - weeks) }, enabled = offset < CALENDAR_WEEKS - weeks) {
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = stringResource(R.string.progress_calendar_older))
                        }
                        Text(
                            stringResource(
                                R.string.progress_calendar_range,
                                shownFirst.format(monthFormat),
                                minOf(lastMonday.plusDays(6), today).format(monthFormat),
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { newerBy = (offset - weeks).coerceAtLeast(0) }, enabled = offset > 0) {
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = stringResource(R.string.progress_calendar_newer))
                        }
                    }
                    Canvas(modifier = Modifier.fillMaxWidth().height((cellDp + GAP) * 7 - GAP)) {
                        val cell = cellDp.toPx()
                        val step = (cellDp + GAP).toPx()
                        for (week in 0 until weeks) {
                            for (weekday in 0 until 7) {
                                val day = shownFirst.plusDays(week * 7L + weekday)
                                if (day > today) continue
                                val minutes = (calendar[day] ?: 0L) / 60
                                val color = when {
                                    minutes < 1 -> empty
                                    minutes < 15 -> fill.copy(alpha = 0.35f)
                                    minutes < 30 -> fill.copy(alpha = 0.55f)
                                    minutes < 60 -> fill.copy(alpha = 0.75f)
                                    else -> fill
                                }
                                drawRoundRect(
                                    color = color,
                                    topLeft = Offset(week * step, weekday * step),
                                    size = Size(cell, cell),
                                    cornerRadius = CornerRadius(2.dp.toPx()),
                                )
                            }
                        }
                    }
                }
            }
            Text(
                stringResource(R.string.progress_calendar_legend),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** How much of the calendar fits a width. */
internal object CalendarWindow {
    /** Weeks (columns) of at least [minCell] plus [gap] that fit [width]; at least 4, at most [max]. All in dp. */
    fun weeks(width: Float, minCell: Float, gap: Float, max: Int): Int =
        ((width + gap) / (minCell + gap)).toInt().coerceIn(4, max)
}

private const val RECENT_DAYS = 6L
private const val GLOW_PERIOD_MS = 1_600
private const val CALENDAR_WEEKS = 53
private val MIN_CELL = 12.dp
private val GAP = 3.dp

private val MIN_TILE_WIDTH = 72.dp
private val TILE_SPACING = 8.dp

private fun kindName(kind: MilestoneKind): Int = when (kind) {
    MilestoneKind.CHARACTERS -> R.string.progress_kind_characters
    MilestoneKind.HOURS -> R.string.progress_kind_hours
    MilestoneKind.BOOKS -> R.string.progress_kind_books
    MilestoneKind.DAYS -> R.string.progress_kind_days
    MilestoneKind.WORDS_LEARNED -> R.string.progress_kind_words
    MilestoneKind.ANKI -> R.string.progress_kind_anki
    MilestoneKind.SPEED -> R.string.progress_kind_speed
}

/** The goal, short enough for a tile: "250k", "1M", "First card". */
@Composable
private fun goalLabel(milestone: Milestone): String = when {
    milestone.kind == MilestoneKind.ANKI -> stringResource(R.string.progress_first_card)
    milestone.goal >= 1_000_000 && milestone.goal % 1_000_000 == 0L -> "${milestone.goal / 1_000_000}M"
    (milestone.goal >= 10_000 || milestone.kind == MilestoneKind.SPEED) && milestone.goal % 1_000 == 0L ->
        "${milestone.goal / 1_000}k"
    else -> number(milestone.goal)
}

/** "100,000 characters read", "First Anki card". */
@Composable
internal fun milestoneTitle(milestone: Milestone): String {
    val goal = number(milestone.goal)
    val quantity = milestone.goal.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    return when (milestone.kind) {
        MilestoneKind.CHARACTERS -> pluralStringResource(R.plurals.milestone_characters, quantity, goal)
        MilestoneKind.HOURS -> pluralStringResource(R.plurals.milestone_hours, quantity, goal)
        MilestoneKind.BOOKS -> pluralStringResource(R.plurals.milestone_books, quantity, goal)
        MilestoneKind.DAYS -> pluralStringResource(R.plurals.milestone_days, quantity, goal)
        MilestoneKind.WORDS_LEARNED -> pluralStringResource(R.plurals.milestone_words, quantity, goal)
        MilestoneKind.ANKI -> stringResource(R.string.milestone_anki)
        MilestoneKind.SPEED -> stringResource(R.string.milestone_speed, goal)
    }
}
