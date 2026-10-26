package app.honmani.reader.ui.library

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animate
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.honmani.reader.HonmaniApplication
import app.honmani.reader.R
import app.honmani.reader.data.library.BookFormat
import app.honmani.reader.data.opds.AutoImportStatus
import app.honmani.reader.ui.ProgressBar
import app.honmani.reader.ui.opds.CatalogBrowser
import app.honmani.reader.ui.opds.CatalogDialog
import app.honmani.reader.ui.opds.OpdsViewModel
import app.honmani.reader.ui.progress.ProgressViewModel
import app.honmani.reader.ui.progress.milestoneTitle
import app.honmani.reader.ui.theme.HonmaniTheme
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** File managers report EPUBs and Aozora text files under many types; the importer checks. */
private val BOOK_MIME_TYPES = arrayOf("*/*")

/** Where the first-start screen asked for a first book: the phone's files or a book server. */
enum class AddBookStart { FILES, SERVER }

/** The app's home: the books in the library. */
@Composable
fun LibraryScreen(
    bottomBar: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LibraryViewModel = viewModel(factory = LibraryViewModel.Factory),
    opds: OpdsViewModel = viewModel(factory = OpdsViewModel.Factory),
    progress: ProgressViewModel = viewModel(factory = ProgressViewModel.Factory),
    /** Adds a book right away, from the first-start screen; null: nothing to do. */
    addBookOnStart: AddBookStart? = null,
    onAddBookStarted: () -> Unit = {},
    /** Opens the Progress tab, from a milestone's notice. */
    onShowProgress: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val autoImport by viewModel.autoImport.collectAsStateWithLifecycle()
    val opdsState by opds.uiState.collectAsStateWithLifecycle()
    var choosingSource by rememberSaveable { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) {
        if (it.isNotEmpty()) viewModel.add(it)
    }
    LaunchedEffect(addBookOnStart) {
        when (addBookOnStart) {
            AddBookStart.FILES -> picker.launch(BOOK_MIME_TYPES)
            AddBookStart.SERVER -> opds.startAdding()
            null -> return@LaunchedEffect
        }
        onAddBookStarted()
    }
    // A copy of a book's file where the reader picks (an OPDS download, say), one launcher per type.
    var savingCopy by rememberSaveable { mutableStateOf<Long?>(null) }
    val onCopyPicked: (Uri?) -> Unit = { uri ->
        val id = savingCopy
        savingCopy = null
        if (uri != null && id != null) viewModel.saveCopy(id, uri)
    }
    val saveEpub = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/epub+zip"), onCopyPicked)
    val saveText = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain"), onCopyPicked)
    val saveCopy: (LibraryBook) -> Unit = { book ->
        savingCopy = book.id
        val name = LibraryViewModel.copyFileName(book.title, book.format)
        if (book.format == BookFormat.TXT) saveText.launch(name) else saveEpub.launch(name)
    }

    // Notices are shown one at a time; each waits until the previous snackbar is gone.
    var notice by remember { mutableStateOf<LibraryNotice?>(null) }
    LaunchedEffect(viewModel) {
        viewModel.notices.collect {
            notice = it
            snapshotFlow { notice }.first { current -> current == null }
        }
    }
    val noticeText = notice?.let { noticeText(it) }
    LaunchedEffect(noticeText) {
        if (noticeText != null) {
            snackbarHostState.showSnackbar(noticeText)
            notice = null
        }
    }

    val browsing = opdsState.browsing

    // Milestones reached since the library was last on screen, quietly, once the books show.
    val milestones by progress.pending.collectAsStateWithLifecycle()
    val milestoneText = milestones.takeIf { browsing == null && it.isNotEmpty() }?.let {
        if (it.size == 1) {
            stringResource(R.string.milestone_reached, milestoneTitle(it.single()))
        } else {
            pluralStringResource(R.plurals.milestones_reached, it.size, it.size)
        }
    }
    val viewProgress = stringResource(R.string.action_view_progress)
    LaunchedEffect(milestoneText) {
        if (milestoneText != null) {
            val showing = milestones
            val result = snackbarHostState.showSnackbar(milestoneText, actionLabel = viewProgress, duration = SnackbarDuration.Long)
            // Only once seen: leaving the library first keeps them for next time.
            progress.shown(showing)
            if (result == SnackbarResult.ActionPerformed) onShowProgress()
        }
    }

    if (browsing != null) {
        CatalogBrowser(
            state = browsing,
            downloads = opdsState.downloads,
            inLibrary = opdsState.inLibrary,
            onBack = { opds.back() },
            onDone = opds::close,
            onOpenFeed = opds::openFeed,
            onDownload = opds::download,
            onSearch = opds::search,
            onLoadMore = opds::loadMore,
            onRetry = opds::retry,
            onChangeLogin = { opds.startEditing(browsing.catalog) },
            thumbnail = opds::thumbnail,
        )
    } else {
        val container = (LocalContext.current.applicationContext as HonmaniApplication).container
        val grades by container.difficultyStore.grades.collectAsStateWithLifecycle()
        val showDifficulty by container.appearanceSettings.showDifficulty.collectAsStateWithLifecycle()
        LibraryContent(
            difficulty = { book ->
                if (showDifficulty) grades[book.contentHash]?.difficulty?.score?.takeIf { it > 0 } else null
            },
            uiState = uiState,
            autoImport = autoImport,
            snackbarHostState = snackbarHostState,
            onAdd = { choosingSource = true },
            onOpen = viewModel::open,
            onRemove = viewModel::remove,
            onResetProgress = viewModel::resetProgress,
            onMarkFinished = viewModel::markFinished,
            onSetHidden = viewModel::setHidden,
            onSaveCopy = saveCopy,
            onFilter = viewModel::setFilter,
            onShowHidden = viewModel::showHidden,
            onSearch = viewModel::search,
            onSort = viewModel::setSort,
            onDismissContinue = viewModel::dismissContinue,
            bottomBar = bottomBar,
            modifier = modifier,
        )
    }

    if (choosingSource) {
        AddBookSheet(
            catalogs = opdsState.catalogs,
            onPickFiles = {
                choosingSource = false
                picker.launch(BOOK_MIME_TYPES)
            },
            onOpenCatalog = {
                choosingSource = false
                opds.openCatalog(it)
            },
            onEditCatalog = {
                choosingSource = false
                opds.startEditing(it)
            },
            onAddCatalog = {
                choosingSource = false
                opds.startAdding()
            },
            onDismiss = { choosingSource = false },
        )
    }

    opdsState.editor?.let { editor ->
        key(editor.id) {
            CatalogDialog(
                editor = editor,
                onSave = opds::saveCatalog,
                onDelete = { editor.id?.let(opds::deleteCatalog) },
                onDismiss = opds::dismissEditor,
            )
        }
    }
}

@Composable
private fun noticeText(notice: LibraryNotice): String = when (notice) {
    is LibraryNotice.AlreadyInLibrary -> stringResource(R.string.library_already_added, notice.title)
    is LibraryNotice.Unsupported -> stringResource(R.string.library_unsupported, notice.fileName)
    is LibraryNotice.Unreadable -> stringResource(R.string.library_unreadable, notice.fileName)
    is LibraryNotice.ReaderFailed -> stringResource(R.string.library_reader_failed, notice.title)
    is LibraryNotice.ResetFailed -> stringResource(R.string.library_reset_failed, notice.title)
    is LibraryNotice.CopySaved -> stringResource(R.string.library_copy_saved, notice.title)
    is LibraryNotice.CopyFailed -> stringResource(R.string.library_copy_failed, notice.title)
}

@Composable
private fun LibraryContent(
    uiState: LibraryUiState,
    snackbarHostState: SnackbarHostState,
    autoImport: AutoImportStatus? = null,
    onAdd: () -> Unit,
    onOpen: (id: Long) -> Unit,
    onRemove: (id: Long) -> Unit,
    onResetProgress: (id: Long) -> Unit,
    onMarkFinished: (id: Long) -> Unit,
    onFilter: (ReadingStatus?) -> Unit,
    onSearch: (String) -> Unit,
    bottomBar: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    onSetHidden: (id: Long, hidden: Boolean) -> Unit = { _, _ -> },
    onSaveCopy: (LibraryBook) -> Unit = {},
    onShowHidden: (Boolean) -> Unit = {},
    /** The difficulty to show on a cover; null for none. */
    difficulty: (LibraryBook) -> Int? = { null },
    onSort: (LibrarySort) -> Unit = {},
    onDismissContinue: () -> Unit = {},
) {
    var pendingRemove by remember { mutableStateOf<LibraryBook?>(null) }
    var pendingReset by remember { mutableStateOf<LibraryBook?>(null) }
    var detailsFor by rememberSaveable { mutableStateOf<Long?>(null) }
    var searching by rememberSaveable { mutableStateOf(false) }
    val books = uiState.books
    val gridState = rememberLazyGridState()
    // A new order or filter starts at the top; coming back from a book keeps the place.
    var shownOrder by rememberSaveable { mutableStateOf(uiState.sort to uiState.filter) }
    LaunchedEffect(uiState.sort, uiState.filter) {
        val order = uiState.sort to uiState.filter
        if (order != shownOrder) {
            shownOrder = order
            gridState.scrollToItem(0)
        }
    }
    BackHandler(enabled = searching) {
        searching = false
        onSearch("")
    }
    // The hidden shelf is opened like a page (from the end of the library), so back leaves it.
    BackHandler(enabled = uiState.showHidden && !searching) { onShowHidden(false) }

    Scaffold(
        modifier = modifier,
        // No top bar: everything is within thumb reach at the bottom.
        bottomBar = {
            Column(modifier = Modifier.imePadding()) {
                // No background: the buttons float over the covers, which fade out towards the
                // tabs so the buttons stay readable.
                val fade = MaterialTheme.colorScheme.background
                Column(
                    modifier = Modifier.background(
                        Brush.verticalGradient(
                            0f to fade.copy(alpha = 0f),
                            0.55f to fade.copy(alpha = 0.85f),
                            1f to fade,
                        ),
                    ),
                ) {
                    // Kept for the exit animation when it goes (a book finished, or searching).
                    val lastContinue = remember { arrayOfNulls<LibraryBook>(1) }
                    uiState.continueBook?.let { lastContinue[0] = it }
                    AnimatedVisibility(
                        visible = uiState.continueBook != null && !searching,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically(),
                    ) {
                        lastContinue[0]?.let { book -> ContinueBubble(book, onOpen = { onOpen(book.id) }, onDismiss = onDismissContinue) }
                    }
                    if (uiState.totalBooks > 0) {
                        LibraryToolbar(
                            uiState = uiState,
                            searching = searching,
                            onStartSearch = {
                                searching = true
                                onFilter(null)
                            },
                            onEndSearch = {
                                searching = false
                                onSearch("")
                            },
                            onSearch = onSearch,
                            onFilter = onFilter,
                            onShowHidden = onShowHidden,
                            onAdd = onAdd,
                            onSort = onSort,
                        )
                    }
                }
                // The keyboard covers the tabs while searching.
                if (!searching) bottomBar()
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        // Only the top inset: the covers scroll on under the floating toolbar, and the grid's
        // bottom padding lets the last row come up above it.
        val bottomInset = padding.calculateBottomPadding()
        val showHiddenLink = uiState.hiddenCount > 0 && !uiState.showHidden && !searching
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding()),
        ) {
            uiState.adding?.let { AddingRow(stringResource(R.string.library_adding, it)) }
            autoImport?.let {
                AddingRow(
                    text = stringResource(R.string.library_auto_importing, it.catalogTitle, it.bookTitle),
                    progress = it.progress,
                    detail = if (it.added > 0) {
                        pluralStringResource(R.plurals.library_auto_imported, it.added, it.added)
                    } else {
                        null
                    },
                )
            }
            when {
                books == null -> Box(Modifier.fillMaxSize().padding(bottom = bottomInset), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                uiState.totalBooks == 0 -> if (uiState.adding == null) {
                    Box(Modifier.padding(bottom = bottomInset)) { EmptyLibrary(onAdd) }
                }
                books.isEmpty() -> Column {
                    Text(
                        text = stringResource(
                            if (uiState.query.isBlank()) R.string.library_filter_empty else R.string.library_search_empty,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                    )
                    // E.g. every book hidden: the way to them must not disappear with the covers.
                    if (showHiddenLink) HiddenBooksLink(uiState.hiddenCount) { onShowHidden(true) }
                }
                else -> LazyVerticalGrid(
                    columns = CoverColumns,
                    state = gridState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = bottomInset + 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    items(books, key = { it.id }) { book ->
                        BookCard(
                            book = book,
                            opening = book.id == uiState.opening,
                            onOpen = { onOpen(book.id) },
                            onShowDetails = { detailsFor = book.id },
                            difficulty = difficulty(book),
                        )
                    }
                    if (showHiddenLink) {
                        item(key = "hidden-books", span = { GridItemSpan(maxLineSpan) }) {
                            HiddenBooksLink(uiState.hiddenCount) { onShowHidden(true) }
                        }
                    }
                }
            }
        }
    }

    // The book as the library has it now, so the sheet follows its changes (finished, hidden).
    detailsFor?.let { id -> books?.firstOrNull { it.id == id } }?.let { book ->
        BookSheet(
            book = book,
            onDismiss = { detailsFor = null },
            onOpen = {
                detailsFor = null
                onOpen(book.id)
            },
            onMarkFinished = { onMarkFinished(book.id) },
            onResetProgress = { pendingReset = book },
            onSetHidden = {
                // Hiding takes the book off this shelf: the sheet goes with it.
                detailsFor = null
                onSetHidden(book.id, it)
            },
            onRemove = { pendingRemove = book },
            onSaveCopy = { onSaveCopy(book) },
        )
    }

    pendingReset?.let { book ->
        AlertDialog(
            onDismissRequest = { pendingReset = null },
            title = { Text(stringResource(R.string.reset_progress_title, book.title)) },
            text = { Text(stringResource(R.string.reset_progress_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onResetProgress(book.id)
                        pendingReset = null
                    },
                ) { Text(stringResource(R.string.action_reset)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingReset = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    pendingRemove?.let { book ->
        AlertDialog(
            onDismissRequest = { pendingRemove = null },
            title = { Text(stringResource(R.string.remove_book_title, book.title)) },
            text = { Text(stringResource(R.string.remove_book_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onRemove(book.id)
                        pendingRemove = null
                        detailsFor = null
                    },
                ) { Text(stringResource(R.string.action_remove)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingRemove = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

/** At least three covers side by side, even with a large display size; more where they fit. */
private object CoverColumns : GridCells {
    override fun Density.calculateCrossAxisCellSizes(availableSize: Int, spacing: Int): List<Int> {
        val count = maxOf(3, (availableSize + spacing) / (COVER_MIN_WIDTH.roundToPx() + spacing))
        return with(GridCells.Fixed(count)) { calculateCrossAxisCellSizes(availableSize, spacing) }
    }
}

private val COVER_MIN_WIDTH = 96.dp

/**
 * One compact row above the tabs: the reading-status filters with their counts (or, on the
 * hidden shelf, the way back), then the order, search and adding a book at the right. While
 * searching, a search field takes its place.
 */
@Composable
private fun LibraryToolbar(
    uiState: LibraryUiState,
    searching: Boolean,
    onStartSearch: () -> Unit,
    onEndSearch: () -> Unit,
    onSearch: (String) -> Unit,
    onFilter: (ReadingStatus?) -> Unit,
    onShowHidden: (Boolean) -> Unit,
    onAdd: () -> Unit,
    onSort: (LibrarySort) -> Unit = {},
) {
    Box {
        if (searching) {
            val focus = remember { FocusRequester() }
            LaunchedEffect(Unit) { focus.requestFocus() }
            OutlinedTextField(
                value = uiState.query,
                onValueChange = onSearch,
                singleLine = true,
                placeholder = { Text(stringResource(R.string.library_search_hint)) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    IconButton(onClick = onEndSearch) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_close_search))
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                shape = CircleShape,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .focusRequester(focus),
            )
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                // Scrolls only as a last resort (three-digit counts on the narrowest screens).
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(rememberScrollState()),
                ) {
                    if (uiState.showHidden) {
                        // The hidden shelf replaces the filters; it is opened from the end of the library.
                        FilterChip(
                            colors = floatingChipColors(),
                            selected = true,
                            onClick = { onShowHidden(false) },
                            label = { Text(stringResource(R.string.library_hidden_shelf, uiState.hiddenCount)) },
                            leadingIcon = {
                                Icon(
                                    painter = painterResource(R.drawable.ic_hidden),
                                    contentDescription = null,
                                    modifier = Modifier.size(FilterChipDefaults.IconSize),
                                )
                            },
                            trailingIcon = {
                                Icon(
                                    Icons.Filled.Close,
                                    contentDescription = stringResource(R.string.action_close_hidden),
                                    modifier = Modifier.size(FilterChipDefaults.IconSize),
                                )
                            },
                        )
                    } else {
                        StatusFilters(uiState, onFilter)
                    }
                }
                // 48 dp touch targets already keep 8 dp between the 40 dp buttons.
                Row {
                    SortButton(uiState.sort, uiState.skill, onSort)
                    FilledTonalIconButton(onClick = onStartSearch) {
                        Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.action_search))
                    }
                    FilledTonalIconButton(onClick = onAdd) {
                        Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.action_add_book))
                    }
                }
            }
        }
    }
}

/** The library's order, from a small rounded menu; the chosen order is marked and tinted. */
@Composable
private fun SortButton(sort: LibrarySort, skill: Int?, onSort: (LibrarySort) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        FilledTonalIconButton(onClick = { open = true }) {
            Icon(painter = painterResource(R.drawable.ic_sort), contentDescription = stringResource(R.string.action_sort))
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            shape = RoundedCornerShape(24.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Text(
                text = stringResource(R.string.action_sort),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 8.dp),
            )
            // Best fit needs a skill; until there is one it is left out.
            for (entry in LibrarySort.entries.filter { it != LibrarySort.BEST_FIT || skill != null }) {
                val selected = entry == sort
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(
                                text = stringResource(
                                    when (entry) {
                                        LibrarySort.RECENT -> R.string.sort_recent
                                        LibrarySort.BEST_FIT -> R.string.sort_best_fit
                                        LibrarySort.EASIEST -> R.string.sort_easiest
                                        LibrarySort.HARDEST -> R.string.sort_hardest
                                        LibrarySort.TITLE -> R.string.sort_title
                                    },
                                ),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            if (entry == LibrarySort.BEST_FIT && skill != null) {
                                Text(
                                    text = stringResource(R.string.sort_best_fit_detail, skill),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                    leadingIcon = {
                        Box(modifier = Modifier.size(24.dp)) {
                            if (selected) Icon(Icons.Filled.Check, contentDescription = null)
                        }
                    },
                    colors = MenuDefaults.itemColors(
                        textColor = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
                        leadingIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    ),
                    onClick = {
                        open = false
                        onSort(entry)
                    },
                    modifier = Modifier
                        .padding(horizontal = 8.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .then(if (selected) Modifier.background(MaterialTheme.colorScheme.secondaryContainer) else Modifier),
                )
            }
        }
    }
}

/**
 * The reading-status filters as one compact pill of segments (icon and count; tap a selected
 * one again for all books). Separate chips did not fit beside the buttons on 360 dp wide
 * screens.
 */
@Composable
private fun StatusFilters(uiState: LibraryUiState, onFilter: (ReadingStatus?) -> Unit) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(modifier = Modifier.height(IntrinsicSize.Min)) {
            // Reversed: the filters used most (not started, reading) sit nearer the right thumb.
            ReadingStatus.entries.asReversed().forEachIndexed { index, status ->
                if (index > 0) {
                    Box(
                        Modifier
                            .fillMaxHeight()
                            .width(1.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant),
                    )
                }
                val selected = uiState.filter == status
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier
                        .selectable(
                            selected = selected,
                            role = Role.Tab,
                            onClick = { onFilter(if (selected) null else status) },
                        )
                        .background(
                            if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                        )
                        .padding(horizontal = 10.dp, vertical = 10.dp),
                ) {
                    Icon(
                        painter = statusIcon(status),
                        contentDescription = stringResource(statusLabel(status)),
                        tint = if (selected) {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = "${uiState.counts[status] ?: 0}",
                        style = MaterialTheme.typography.labelLarge,
                        color = if (selected) {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }
    }
}

/** A line after the last cover that opens the hidden books, so they need no filter of their own. */
@Composable
private fun HiddenBooksLink(count: Int, onShow: () -> Unit) {
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        TextButton(onClick = onShow) {
            Icon(
                painter = painterResource(R.drawable.ic_hidden),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = pluralStringResource(R.plurals.library_hidden_books, count, count),
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

/** Unselected chips are filled, so they stay readable over the covers behind them. */
@Composable
private fun floatingChipColors() = FilterChipDefaults.filterChipColors(
    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
)

/**
 * One tap back into the book read last, while it is being read: a bubble at the right, under
 * the thumb, as wide as its title needs up to a limit.
 */
@Composable
private fun ContinueBubble(book: LibraryBook, onOpen: () -> Unit, onDismiss: () -> Unit) {
    // Swiped to the right far enough (or flung), it slides off and stays away; otherwise it springs back.
    var offset by remember { mutableFloatStateOf(0f) }
    var width by remember { mutableIntStateOf(0) }
    val dismissAt = width * DISMISS_FRACTION
    val drag = rememberDraggableState { delta -> offset = (offset + delta).coerceAtLeast(0f) }
    Row(
        horizontalArrangement = Arrangement.End,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 64.dp, end = 12.dp, top = 8.dp, bottom = 2.dp),
    ) {
        Surface(
            onClick = onOpen,
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            shape = CircleShape,
            shadowElevation = 4.dp,
            modifier = Modifier
                .widthIn(max = 320.dp)
                .onSizeChanged { width = it.width }
                .offset { IntOffset(offset.roundToInt(), 0) }
                .graphicsLayer { alpha = 1f - (offset / (width.coerceAtLeast(1) * 1.2f)).coerceIn(0f, 1f) }
                .draggable(
                    state = drag,
                    orientation = Orientation.Horizontal,
                    onDragStopped = { velocity ->
                        if (offset > dismissAt || velocity > FLING_VELOCITY) {
                            animate(offset, width * 1.5f) { value, _ -> offset = value }
                            onDismiss()
                        } else {
                            animate(offset, 0f) { value, _ -> offset = value }
                        }
                    },
                ),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(start = 14.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
            ) {
                Box(modifier = Modifier.width(24.dp)) { Cover(book) }
                Column(modifier = Modifier.weight(1f, fill = false)) {
                    Text(
                        text = stringResource(R.string.library_continue_reading),
                        style = MaterialTheme.typography.labelSmall,
                    )
                    BookTitle(book.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                }
                Text(
                    text = stringResource(R.string.library_progress, (book.progress * 100).roundToInt()),
                    style = MaterialTheme.typography.labelMedium,
                )
                Icon(Icons.Filled.PlayArrow, contentDescription = null)
            }
        }
    }
}

private const val DISMISS_FRACTION = 0.4f

/** Pixels per second; a quick flick to the right dismisses from any distance. */
private const val FLING_VELOCITY = 1_500f

@Composable
internal fun statusIcon(status: ReadingStatus): Painter = when (status) {
    ReadingStatus.NOT_STARTED -> painterResource(R.drawable.ic_not_started)
    ReadingStatus.READING -> painterResource(R.drawable.ic_reading)
    ReadingStatus.FINISHED -> rememberVectorPainter(Icons.Filled.CheckCircle)
}

internal fun statusLabel(status: ReadingStatus): Int = when (status) {
    ReadingStatus.NOT_STARTED -> R.string.library_filter_not_started
    ReadingStatus.READING -> R.string.library_filter_reading
    ReadingStatus.FINISHED -> R.string.library_filter_finished
}

@Composable
private fun AddingRow(text: String, progress: Float? = null, detail: String? = null) {
    Column(modifier = Modifier.fillMaxWidth()) {
        if (progress != null) {
            ProgressBar(progress = { progress }, modifier = Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = if (detail == null) 8.dp else 0.dp),
        )
        detail?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
            )
        }
    }
}

@Composable
private fun EmptyLibrary(onAdd: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.library_empty_title),
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(R.string.library_empty_message),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
        )
        Button(onClick = onAdd) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Text(stringResource(R.string.action_add_book), modifier = Modifier.padding(start = 8.dp))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookCard(
    book: LibraryBook,
    opening: Boolean,
    onOpen: () -> Unit,
    onShowDetails: () -> Unit,
    difficulty: Int? = null,
) {
    Column(
        modifier = Modifier.combinedClickable(
            role = Role.Button,
            onClick = onOpen,
            onLongClick = onShowDetails,
        ),
    ) {
        Box {
            Cover(book)
            if (difficulty != null) {
                // The book's difficulty, small, in the corner the finished check doesn't use.
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(4.dp),
                ) {
                    val description = stringResource(R.string.book_difficulty, difficulty)
                    Text(
                        text = "$difficulty",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                            .clearAndSetSemantics { contentDescription = description },
                    )
                }
            }
            if (book.status == ReadingStatus.FINISHED) {
                // A finished book carries a check on its cover, like the filter.
                Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = stringResource(R.string.library_filter_finished),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .background(MaterialTheme.colorScheme.surface, CircleShape),
                )
            }
            if (opening) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }
        }
        if (book.progress > 0f || book.status == ReadingStatus.FINISHED) {
            val finished = book.status == ReadingStatus.FINISHED
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                ProgressBar(
                    progress = { if (finished) 1f else book.progress },
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = if (finished) {
                        stringResource(R.string.library_progress, 100)
                    } else {
                        stringResource(R.string.library_progress, (book.progress * 100).roundToInt())
                    },
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
        }
        BookTitle(
            book.title,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            modifier = Modifier.padding(top = 6.dp),
        )
        book.author?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
internal fun Cover(book: LibraryBook) {
    val shape = RoundedCornerShape(6.dp)
    val modifier = Modifier
        .fillMaxWidth()
        .aspectRatio(COVER_ASPECT_RATIO)
        .clip(shape)
    val image by produceState<ImageBitmap?>(CoverCache.get(book.cover), book.cover) {
        if (value == null) value = CoverCache.load(book.cover)
    }
    val bitmap = image
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier,
        )
    } else {
        // Books without a cover get a stitched binding with their title on a slip.
        PlaceholderCover(book.title, modifier)
    }
}

private const val COVER_ASPECT_RATIO = 2f / 3f

/** Decoded covers, downsampled to grid size, kept while the process lives. */
internal object CoverCache {
    private const val TARGET_WIDTH_PX = 360
    private val cache = LruCache<String, ImageBitmap>(64)

    fun get(file: File?): ImageBitmap? = file?.let { cache.get(it.path) }

    suspend fun load(file: File?): ImageBitmap? {
        if (file == null) return null
        return withContext(Dispatchers.IO) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            if (bounds.outWidth <= 0) return@withContext null
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= TARGET_WIDTH_PX) sample *= 2
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            BitmapFactory.decodeFile(file.path, options)?.asImageBitmap()
        }?.also { cache.put(file.path, it) }
    }

    /** The cover's average colour (null without a cover), for a wash behind it. */
    suspend fun averageColor(file: File?): Color? {
        val bitmap = get(file) ?: load(file) ?: return null
        return withContext(Dispatchers.Default) {
            Color(Bitmap.createScaledBitmap(bitmap.asAndroidBitmap(), 1, 1, true).getPixel(0, 0))
        }
    }
}

@Preview
@Composable
private fun LibraryPreview() {
    HonmaniTheme {
        LibraryContent(
            uiState = LibraryUiState(
                books = listOf(
                    LibraryBook(1, "吾輩は猫である", "夏目漱石", null, 0.42f),
                    LibraryBook(2, "銀河鉄道の夜", "宮沢賢治", null, 0f),
                ),
                totalBooks = 2,
                counts = mapOf(ReadingStatus.READING to 1, ReadingStatus.NOT_STARTED to 1),
                adding = "kokoro.epub",
            ),
            snackbarHostState = remember { SnackbarHostState() },
            onAdd = {},
            onOpen = {},
            onRemove = {},
            onResetProgress = {},
            onMarkFinished = {},
            onFilter = {},
            onSearch = {},
            bottomBar = {},
        )
    }
}

@Preview
@Composable
private fun EmptyLibraryPreview() {
    HonmaniTheme {
        LibraryContent(
            uiState = LibraryUiState(books = emptyList()),
            snackbarHostState = remember { SnackbarHostState() },
            onAdd = {},
            onOpen = {},
            onRemove = {},
            onResetProgress = {},
            onMarkFinished = {},
            onFilter = {},
            onSearch = {},
            bottomBar = {},
        )
    }
}
