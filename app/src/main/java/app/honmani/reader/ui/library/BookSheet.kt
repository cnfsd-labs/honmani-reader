package app.honmani.reader.ui.library

import androidx.compose.animation.core.animate
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.honmani.reader.HonmaniApplication
import app.honmani.reader.R
import app.honmani.reader.data.library.FolderResult
import app.honmani.reader.data.difficulty.BookDifficulty
import app.honmani.reader.data.difficulty.GradingState
import app.honmani.reader.ui.ProgressBar
import app.honmani.reader.ui.PullDownSheet
import app.honmani.reader.ui.difficultyBand
import app.honmani.reader.ui.progress.ProgressViewModel
import app.honmani.reader.ui.stats.BookStatistics
import app.honmani.reader.ui.stats.StatisticsViewModel
import app.honmani.reader.ui.stats.duration
import app.honmani.reader.ui.stats.number
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * Everything about one book, from a long press on its cover: the cover and progress, a button
 * into the book, its statistics, then what can be done with it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookSheet(
    book: LibraryBook,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
    onMarkFinished: () -> Unit,
    onResetProgress: () -> Unit,
    onSetHidden: (Boolean) -> Unit,
    onRemove: () -> Unit,
    /** Saves a copy of the book's file on the phone (an OPDS download, say). */
    onSaveCopy: () -> Unit = {},
    /** Whether the book has an audiobook to export; the button shows only then. */
    canExportAudiobook: Boolean = false,
    /** Saves the audiobook's audio and subtitles into a folder the reader picks. */
    onExportAudiobook: () -> Unit = {},
    folders: List<LibraryFolder> = emptyList(),
    onMoveToFolder: (Long?) -> Unit = {},
    /** Makes a folder with this name and moves the book in; the second argument gets what came of the name. */
    onCreateFolder: (String, (FolderResult) -> Unit) -> Unit = { _, _ -> },
    viewModel: StatisticsViewModel = viewModel(factory = StatisticsViewModel.Factory),
    progress: ProgressViewModel = viewModel(factory = ProgressViewModel.Factory),
) {
    val flow = remember(book.id) { viewModel.book(book.id) }
    val stats by flow.collectAsStateWithLifecycle(null)
    val progressState by progress.uiState.collectAsStateWithLifecycle()
    val skill = progressState?.skill?.current
    var showUnknown by rememberSaveable(book.id) { mutableStateOf(false) }
    var pickingFolder by rememberSaveable(book.id) { mutableStateOf(false) }
    var namingFolder by rememberSaveable(book.id) { mutableStateOf(false) }
    // Pulling the sheet down and its scroll without a bounce: see PullDownSheet.
    PullDownSheet(
        onDismiss = onDismiss,
        // The handle is drawn inside the header, so the cover's colour reaches the sheet's top.
        dragHandle = null,
    ) {
        Box {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState(), overscrollEffect = null)
                    .padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Header(book, stats?.let { timeToRead(it, book) })
                Button(
                    onClick = onOpen,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                        .heightIn(min = 52.dp),
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null)
                    Text(
                        text = stringResource(
                            when (book.status) {
                                ReadingStatus.NOT_STARTED -> R.string.book_start_reading
                                ReadingStatus.READING -> R.string.library_continue_reading
                                ReadingStatus.FINISHED -> R.string.book_open_again
                            },
                        ),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                // Before the reader's vocabulary is estimated, a graded book's list assumes a beginner's (to prepare it).
                val coverage = stats?.coverage
                val hasNewWords = if (coverage != null) coverage.newWords > 0 else (stats?.difficulty?.score ?: 0) > 0
                stats?.let {
                    DifficultyCard(
                        stats = it,
                        book = book,
                        skill = skill,
                        onLearn = if (hasNewWords && book.contentHash.isNotEmpty()) ({ showUnknown = true }) else null,
                    )
                }
                stats?.let { Statistics(it) }
                FolderAndCopy(
                    folder = folders.firstOrNull { it.id == book.folderId }?.name,
                    onFolder = { pickingFolder = true },
                    onSaveCopy = onSaveCopy,
                    onExportAudiobook = if (canExportAudiobook) onExportAudiobook else null,
                )
                Actions(
                    book = book,
                    onMarkFinished = onMarkFinished,
                    onResetProgress = onResetProgress,
                    onSetHidden = onSetHidden,
                    onRemove = onRemove,
                )
            }
        }
    }
    if (pickingFolder) {
        FolderPickerDialog(
            folders = folders,
            chosen = book.folderId,
            onPick = {
                pickingFolder = false
                onMoveToFolder(it)
            },
            onNew = {
                pickingFolder = false
                namingFolder = true
            },
            onDismiss = { pickingFolder = false },
        )
    }
    if (namingFolder) {
        FolderNameDialog(
            title = stringResource(R.string.folder_new_title),
            initial = "",
            confirm = stringResource(R.string.folder_create),
            onSave = { name, done -> onCreateFolder(name, done) },
            onDismiss = { namingFolder = false },
        )
    }
    // After the book's sheet, so it opens above it.
    if (showUnknown) UnknownWordsSheet(book.contentHash, viewModel, onDismiss = { showUnknown = false })
}

/** Reading time left (or for the whole book) at the reader's recent speed, as text; null without one. */
@Composable
private fun timeToRead(stats: BookStatistics, book: LibraryBook): String? {
    val speed = stats.readerSpeed ?: return null
    val characters = stats.difficulty?.characters?.takeIf { it > 0 } ?: return null
    val reading = book.status == ReadingStatus.READING
    if (!reading && book.status != ReadingStatus.NOT_STARTED) return null
    val share = if (reading) (1f - book.progress).coerceIn(0f, 1f) else 1f
    return stringResource(
        if (reading) R.string.book_time_left else R.string.book_time_whole,
        duration((characters * share * 3_600 / speed).toLong()),
    )
}

/**
 * The cover, large, on a soft wash of its own colour, beside the title, author, status and
 * progress.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Header(book: LibraryBook, timeToRead: String?) {
    val tint by produceState<Color?>(null, book.cover) { value = CoverCache.averageColor(book.cover) }
    // Without a cover, the placeholder's binding; with one, nothing until it is loaded.
    val wash = tint ?: if (book.cover == null) CoverTitle.binding(book.title) else Color.Transparent
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(wash.copy(alpha = 0.32f), Color.Transparent))),
    ) {
        BottomSheetDefaults.DragHandle()
        HeaderRow(book, timeToRead)
    }
}

@Composable
private fun HeaderRow(book: LibraryBook, timeToRead: String?) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 24.dp, bottom = 4.dp),
    ) {
        Box(
            modifier = Modifier
                .width(112.dp)
                .shadow(10.dp, RoundedCornerShape(10.dp)),
        ) {
            Cover(book)
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(top = 4.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = book.title,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
            book.author?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            StatusPill(book)
            if (book.status != ReadingStatus.NOT_STARTED) {
                val progress = if (book.status == ReadingStatus.FINISHED) 1f else book.progress
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ProgressBar(progress = { progress }, modifier = Modifier.weight(1f))
                    Text(
                        text = stringResource(R.string.library_progress, (progress * 100).roundToInt()),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
            timeToRead?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (book.hidden) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(
                        painter = painterResource(R.drawable.ic_hidden),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = stringResource(R.string.book_hidden),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusPill(book: LibraryBook) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(start = 8.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
        ) {
            Icon(painter = statusIcon(book.status), contentDescription = null, modifier = Modifier.size(16.dp))
            Text(stringResource(statusLabel(book.status)), style = MaterialTheme.typography.labelLarge)
        }
    }
}

/**
 * How hard the book is: the score, how it fits the reader's skill, its band with about which
 * JLPT level that is, and how much of its words the reader knows. A tap shows the measures behind the score. Without a grade: that it is being worked out, or what is missing.
 */
@Composable
private fun DifficultyCard(stats: BookStatistics, book: LibraryBook, skill: Int?, onLearn: (() -> Unit)?) {
    val difficulty = stats.difficulty
    if (difficulty == null) {
        val hint = when (stats.grading) {
            GradingState.GRADING -> R.string.book_difficulty_grading
            GradingState.NO_DICTIONARIES -> R.string.book_difficulty_no_dictionaries
            GradingState.IDLE -> return
        }
        Text(
            text = stringResource(hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
        return
    }
    var expanded by rememberSaveable(book.id) { mutableStateOf(false) }
    Surface(
        onClick = { expanded = !expanded },
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.book_difficulty, difficulty.score),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                skill?.let { FitLabel(BookFit.of(difficulty.score, it)) }
                Icon(
                    imageVector = if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = stringResource(if (expanded) R.string.action_hide_details else R.string.action_show_details),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            Text(
                text = difficultyBand(difficulty.score),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ProgressBar(progress = { difficulty.score / 100f }, modifier = Modifier.fillMaxWidth())
            stats.coverage?.let { coverage ->
                // Rounded down: 99.6% isn't all of them.
                val percent = (coverage.known * 100).toInt()
                Text(
                    text = stringResource(R.string.book_known_words, percent),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ProgressBar(progress = { coverage.known.toFloat() }, modifier = Modifier.fillMaxWidth())
            }
            onLearn?.let { NewWordsRow(stats.coverage?.newWords, it) }
            if (expanded) {
                Text(
                    text = stringResource(
                        R.string.book_difficulty_detail,
                        (difficulty.rareShare * 100).roundToInt(),
                        (difficulty.kanjiShare * 100).roundToInt(),
                        difficulty.sentenceLength.roundToInt(),
                        number(difficulty.distinctWords.toLong()),
                        number(difficulty.words.toLong()),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** How a book of some difficulty fits a reader of some skill. */
internal enum class BookFit {
    EASY,
    FITS,
    CHALLENGE,
    HARD,
    ;

    companion object {
        /** "Fits" is the range of "Books that fit you" in Progress: a little easier to a little harder. */
        fun of(score: Int, skill: Int): BookFit = when (score - skill) {
            in Int.MIN_VALUE..-6 -> EASY
            in -5..8 -> FITS
            in 9..15 -> CHALLENGE
            else -> HARD
        }
    }
}

@Composable
private fun FitLabel(fit: BookFit) {
    Surface(
        shape = RoundedCornerShape(50),
        color = if (fit == BookFit.FITS) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer,
        contentColor = if (fit == BookFit.FITS) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Text(
            text = stringResource(
                when (fit) {
                    BookFit.EASY -> R.string.book_fit_easy
                    BookFit.FITS -> R.string.book_fit_fits
                    BookFit.CHALLENGE -> R.string.book_fit_challenge
                    BookFit.HARD -> R.string.book_fit_hard
                },
            ),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

/** Four numbers in two rows, then when the book was read. */
@Composable
private fun Statistics(stats: BookStatistics) {
    val reading = stats.reading
    val context = LocalContext.current
    Column(
        modifier = Modifier.padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (reading.seconds == 0L && stats.lookups == 0) {
            Text(
                text = stringResource(R.string.book_nothing_counted),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.height(IntrinsicSize.Min)) {
            Fact(
                label = stringResource(R.string.stats_book_time),
                value = duration(reading.seconds),
                detail = pluralStringResource(R.plurals.book_on_days, reading.daysRead, reading.daysRead),
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
            Fact(
                label = stringResource(R.string.book_read_so_far),
                value = number(stats.position),
                detail = stats.bookCharacters?.let { stringResource(R.string.book_of_characters, number(it)) },
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.height(IntrinsicSize.Min)) {
            Fact(
                label = stringResource(R.string.stats_speed),
                value = reading.charactersPerHour?.let { number(it) } ?: "–",
                detail = reading.charactersPerHour?.let { stringResource(R.string.book_per_hour) },
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
            Fact(
                label = stringResource(R.string.stats_lookups),
                value = number(stats.lookups.toLong()),
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
        }
        val dateFormat = remember { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM) }
        val dayClock = remember { (context.applicationContext as HonmaniApplication).container.dayClock }
        val dates = listOfNotNull(
            reading.firstDay?.let { stringResource(R.string.book_first_read, it.format(dateFormat)) },
            stats.finishedAt?.let {
                val day = dayClock.date(it)
                stringResource(R.string.book_finished_on, day.format(dateFormat))
            },
        )
        if (dates.isNotEmpty()) {
            Text(
                text = dates.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Fact(label: String, value: String, modifier: Modifier = Modifier, detail: String? = null) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                value,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (detail != null) {
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** What to learn first: the count of the book's new words, opening the list to choose from. */
@Composable
private fun NewWordsRow(count: Int?, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (count != null) {
                        pluralStringResource(R.plurals.book_new_words, count, number(count.toLong()))
                    } else {
                        stringResource(R.string.book_new_words_unknown)
                    },
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(stringResource(R.string.book_new_words_choose), style = MaterialTheme.typography.bodySmall)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
        }
    }
}

/** Two equal pills: the book's folder (opening the picker) and "Save a copy"; below them "Export audiobook" if there is one. Long names are cut. */
@Composable
private fun FolderAndCopy(folder: String?, onFolder: () -> Unit, onSaveCopy: () -> Unit, onExportAudiobook: (() -> Unit)?) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            PillButton(R.drawable.ic_folder, folder ?: stringResource(R.string.book_folder_add), onFolder, Modifier.weight(1f))
            PillButton(R.drawable.ic_download, stringResource(R.string.action_save_copy), onSaveCopy, Modifier.weight(1f))
        }
        if (onExportAudiobook != null) {
            PillButton(R.drawable.ic_audiobook, stringResource(R.string.audiobook_export), onExportAudiobook, Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun PillButton(icon: Int, label: String, onClick: () -> Unit, modifier: Modifier) {
    FilledTonalButton(onClick = onClick, modifier = modifier, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
        Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(18.dp))
        Text(
            text = label,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 8.dp).weight(1f, fill = false),
        )
    }
}

/** The book's actions as a row of tiles (two rows with large text), removing last and in the error colour. */
@Composable
private fun Actions(
    book: LibraryBook,
    onMarkFinished: () -> Unit,
    onResetProgress: () -> Unit,
    onSetHidden: (Boolean) -> Unit,
    onRemove: () -> Unit,
) {
    val tiles = buildList<@Composable (Modifier) -> Unit> {
        if (book.status != ReadingStatus.FINISHED) {
            add { ActionTile(rememberVectorPainter(Icons.Filled.CheckCircle), stringResource(R.string.action_mark_finished), onMarkFinished, it) }
        }
        if (book.status != ReadingStatus.NOT_STARTED) {
            add { ActionTile(rememberVectorPainter(Icons.Filled.Refresh), stringResource(R.string.action_reset_progress), onResetProgress, it) }
        }
        add {
            ActionTile(
                icon = painterResource(if (book.hidden) R.drawable.ic_visible else R.drawable.ic_hidden),
                label = stringResource(if (book.hidden) R.string.action_unhide_book else R.string.action_hide_book),
                onClick = { onSetHidden(!book.hidden) },
                modifier = it,
            )
        }
        add {
            ActionTile(
                icon = rememberVectorPainter(Icons.Filled.Delete),
                label = stringResource(R.string.action_remove),
                onClick = onRemove,
                modifier = it,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
    // With large text four tiles in a row cut their labels short: two per row instead.
    val perRow = if (LocalDensity.current.fontScale >= 1.3f) 2 else tiles.size
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 24.dp)) {
        tiles.chunked(perRow).forEach { row ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.height(IntrinsicSize.Min),
            ) {
                row.forEach { it(Modifier.weight(1f).fillMaxHeight()) }
                // A short last row keeps its tiles the width of the others.
                repeat(perRow - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun ActionTile(
    icon: Painter,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 14.dp),
        ) {
            Icon(painter = icon, contentDescription = null, tint = color)
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
