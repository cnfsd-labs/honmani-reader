package app.honmani.reader.ui.words

import android.content.Intent
import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import app.honmani.reader.ui.reader.LookupPanel
import app.honmani.reader.ui.reader.ReaderViewModel
import app.honmani.reader.ui.review.ReviewScopeChoice
import app.honmani.reader.ui.search.SearchContent
import app.honmani.reader.ui.search.SearchViewModel
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.focus.focusRequester
import app.honmani.reader.ui.search.SearchResults
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.honmani.reader.HonmaniApplication
import app.honmani.reader.R
import app.honmani.reader.data.words.WordEntity
import app.honmani.reader.data.words.WordLookupEntity
import app.honmani.reader.data.words.WordProgress
import app.honmani.reader.data.words.WordStatus
import app.honmani.reader.lookup.Frequency
import app.honmani.reader.lookup.LookupGroups
import app.honmani.reader.ui.ProgressBar
import app.honmani.reader.ui.anki.label
import app.honmani.reader.ui.anki.rememberAnkiAccess
import app.honmani.reader.ui.anki.rememberAnkiEnabled
import app.honmani.reader.ui.anki.KnownFromAnkiDialogs
import app.honmani.reader.ui.anki.rememberKnownFromAnki
import app.honmani.reader.ui.known.KnownWordsCard
import app.honmani.reader.ui.known.KnownWordsSheet
import app.honmani.reader.ui.anki.showAnkiCheck
import app.honmani.reader.ui.anki.showAnkiResult
import app.honmani.reader.ui.anki.showVoiceProblem
import app.honmani.reader.ui.reader.FrequencyBadge
import app.honmani.reader.ui.reader.GlossLines
import app.honmani.reader.ui.theme.HonmaniTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** "Add all" asks first when it would make more cards than this. */
private const val ADD_ALL_WITHOUT_ASKING = 10

/** The words looked up while reading, most looked up (recently) first. */
@Composable
fun WordsScreen(
    bottomBar: @Composable () -> Unit,
    /** Counts taps on the Words tab while it is open: each one scrolls the list to the top. */
    scrollToTop: Int = 0,
    /** The launcher's "Search" shortcut asks for the search field; [onSearchFocused] once it has it. */
    focusSearch: Boolean = false,
    onSearchFocused: () -> Unit = {},
    /** The review's entrance under the search field: cards due, new words the session would start. */
    reviewDue: Int = 0,
    reviewNew: Int = 0,
    /** Ids of the words due today, marked with a dot in the list. */
    dueIds: Set<Long> = emptySet(),
    onStartReview: () -> Unit = {},
    reviewChoice: ReviewScopeChoice = ReviewScopeChoice(),
    viewModel: WordsViewModel = viewModel(factory = WordsViewModel.Factory),
    search: SearchViewModel = viewModel(factory = SearchViewModel.Factory),
    // Its own lookup panel, apart from the reader's: a search saves no word by itself.
    searchPanel: ReaderViewModel = viewModel(key = "search", factory = ReaderViewModel.SearchFactory),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // Each change gets a snackbar with an undo; the next waits until it is gone.
    var change by remember { mutableStateOf<WordChange?>(null) }
    LaunchedEffect(viewModel) {
        viewModel.changes.collect {
            change = it
            snapshotFlow { change }.first { current -> current == null }
        }
    }
    val current = change
    val message = current?.let {
        when (it) {
            is WordChange.Learned -> stringResource(R.string.word_moved_to_learned, it.expression)
            is WordChange.Deleted -> stringResource(R.string.word_deleted, it.expression)
            is WordChange.Ignored -> stringResource(R.string.word_ignored, it.expression)
        }
    }
    val undoLabel = stringResource(R.string.action_undo)
    val context = LocalContext.current
    val resources = LocalResources.current
    val anki = rememberAnkiAccess()
    val ankiEnabled = rememberAnkiEnabled()
    val exportFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) viewModel.exportToFile(uri.toString())
    }
    val exportPackage = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        if (uri != null) viewModel.exportPackage(uri.toString())
    }
    val exportCsv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) viewModel.exportCsv(uri.toString())
    }
    LaunchedEffect(viewModel) {
        viewModel.ankiOutcomes.collect { outcome ->
            when (outcome) {
                is AnkiOutcome.One -> showAnkiResult(context, outcome.expression, outcome.result)
                is AnkiOutcome.Many -> {
                    val stopped = outcome.stopped
                    if (stopped != null && outcome.added == 0) {
                        showAnkiResult(context, "", stopped)
                    } else if (outcome.added == 0 && outcome.leftOut > 0) {
                        val text = resources.getQuantityString(R.plurals.anki_only_grammar_left, outcome.leftOut, outcome.leftOut)
                        Toast.makeText(context, text, Toast.LENGTH_LONG).show()
                    } else {
                        val added = resources.getQuantityString(
                            R.plurals.anki_added_many,
                            outcome.added,
                            outcome.added,
                        )
                        val leftOut = if (outcome.leftOut > 0) {
                            " – " + resources.getQuantityString(R.plurals.anki_left_out, outcome.leftOut, outcome.leftOut)
                        } else {
                            ""
                        }
                        val audio = outcome.audioProblem?.let { " – " + resources.getString(it.label()) }.orEmpty()
                        Toast.makeText(context, added + leftOut + audio, Toast.LENGTH_LONG).show()
                        if (stopped != null) showAnkiResult(context, "", stopped)
                    }
                }
                is AnkiOutcome.Exported -> {
                    val saved = resources.getQuantityString(
                        if (outcome.packaged) R.plurals.anki_exported_package else R.plurals.anki_exported,
                        outcome.count,
                        outcome.count,
                    )
                    val audio = outcome.audioProblem?.let { " – " + resources.getString(it.label()) }.orEmpty()
                    Toast.makeText(context, saved + audio, Toast.LENGTH_LONG).show()
                }
                is AnkiOutcome.CsvExported -> Toast.makeText(
                    context,
                    resources.getQuantityString(R.plurals.csv_words_exported, outcome.count, outcome.count),
                    Toast.LENGTH_LONG,
                ).show()
                is AnkiOutcome.Checked -> showAnkiCheck(context, outcome.result)
                AnkiOutcome.ExportFailed ->
                    Toast.makeText(context, R.string.anki_export_failed, Toast.LENGTH_LONG).show()
            }
        }
    }
    val knownFromAnki = rememberKnownFromAnki()
    KnownFromAnkiDialogs(knownFromAnki)
    val ankiSettings = remember { (context.applicationContext as HonmaniApplication).container.ankiSettings }
    val offered by ankiSettings.knownFromAnkiOffered.collectAsStateWithLifecycle()
    // Only for readers who have AnkiDroid at all.
    val offerKnown = !offered && remember { (context.applicationContext as HonmaniApplication).container.ankiDroid.packageName() != null }
    var confirmAddAll by remember { mutableStateOf(false) }
    if (confirmAddAll) {
        AlertDialog(
            onDismissRequest = { confirmAddAll = false },
            title = { Text(stringResource(R.string.anki_add_all_title, uiState.notInAnki)) },
            text = { Text(stringResource(R.string.anki_add_all_text)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmAddAll = false
                    anki.whenReady(viewModel::addAllToAnki)
                }) { Text(stringResource(R.string.action_add)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmAddAll = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
    LaunchedEffect(current) {
        if (current != null && message != null) {
            val result = snackbarHostState.showSnackbar(message, undoLabel, duration = SnackbarDuration.Short)
            if (result == SnackbarResult.ActionPerformed) viewModel.undo(current)
            change = null
        }
    }

    var showSession by remember { mutableStateOf(false) }
    if (showSession) SessionSheet(uiState.session, onDismiss = { showSession = false })
    var showIgnored by remember { mutableStateOf(false) }
    if (showIgnored) {
        val ignored by viewModel.ignoredWords.collectAsStateWithLifecycle()
        IgnoredSheet(ignored, onUnignore = viewModel::unignore, onDismiss = { showIgnored = false })
    }

    // The dictionary search: results while typing, the last searches when the field is empty.
    val searchQuery by search.query.collectAsStateWithLifecycle()
    val searchResults by search.results.collectAsStateWithLifecycle()
    val history by search.history.collectAsStateWithLifecycle()
    val panel by searchPanel.panel.collectAsStateWithLifecycle()
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    // Review is the first tab, but the screen opens on the Words list.
    val pager = rememberPagerState(initialPage = WordsPage.WORDS.ordinal) { WordsPage.entries.size }
    val scope = rememberCoroutineScope()
    // The field takes focus once the Dictionary page is there: a tap on its tab or the launcher's shortcut.
    var focusWanted by remember { mutableStateOf(false) }
    LaunchedEffect(focusSearch) {
        if (focusSearch) {
            pager.scrollToPage(WordsPage.DICTIONARY.ordinal)
            focusWanted = true
            onSearchFocused()
        }
    }
    LaunchedEffect(focusWanted) {
        if (focusWanted) {
            snapshotFlow { pager.currentPage == WordsPage.DICTIONARY.ordinal && !pager.isScrollInProgress }.first { it }
            focus.requestFocus()
            keyboard?.show()
            focusWanted = false
        }
    }
    // Leaving the Dictionary page closes the keyboard.
    LaunchedEffect(pager) {
        snapshotFlow { pager.currentPage }.collect {
            if (it != WordsPage.DICTIONARY.ordinal) {
                focusManager.clearFocus()
                keyboard?.hide()
            }
        }
    }
    // A tap on the Words tab while it is open: back to the Words page.
    val firstScrollToTop = remember { scrollToTop }
    LaunchedEffect(scrollToTop) {
        if (scrollToTop != firstScrollToTop && pager.currentPage != WordsPage.WORDS.ordinal) pager.animateScrollToPage(WordsPage.WORDS.ordinal)
    }
    LaunchedEffect(searchPanel) {
        searchPanel.ankiResults.collect { (expression, result) -> showAnkiResult(context, expression, result) }
    }
    LaunchedEffect(searchPanel) {
        searchPanel.voiceProblems.collect { showVoiceProblem(context, it) }
    }
    // Back from Review or Dictionary goes to Words, as Progress's sections go to the first.
    BackHandler(enabled = panel == null && pager.currentPage != WordsPage.WORDS.ordinal) {
        scope.launch { pager.animateScrollToPage(WordsPage.WORDS.ordinal) }
    }
    val searchWords = remember(searchQuery, uiState.learning, uiState.known) {
        searchWords(uiState.learning.orEmpty() + uiState.known.orEmpty(), searchQuery).take(MAX_WORDS_IN_SEARCH)
    }
    fun open(show: () -> Unit) {
        search.remember()
        keyboard?.hide()
        focusManager.clearFocus()
        show()
    }
    var showHelp by rememberSaveable { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
    Scaffold(
        // No title (the bottom bar names the tab): Review, Words and Dictionary, as tabs to tap or
        // swipe between like Progress's sections.
        topBar = {
            Surface(color = TabRowDefaults.primaryContainerColor) {
                PrimaryTabRow(
                    selectedTabIndex = pager.currentPage,
                    modifier = Modifier.windowInsetsPadding(WindowInsets.statusBars),
                ) {
                    WordsPage.entries.forEachIndexed { index, page ->
                        Tab(
                            selected = index == pager.currentPage,
                            onClick = {
                                if (page == WordsPage.DICTIONARY) focusWanted = true
                                scope.launch { pager.animateScrollToPage(index) }
                            },
                            text = {
                                Text(
                                    if (page == WordsPage.REVIEW && reviewDue > 0) {
                                        stringResource(R.string.words_tab_review_due, reviewDue)
                                    } else {
                                        stringResource(page.label)
                                    },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                        )
                    }
                }
            }
        },
        bottomBar = bottomBar,
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        HorizontalPager(state = pager, modifier = Modifier.padding(padding).fillMaxSize()) { index ->
            when (WordsPage.entries[index]) {
                WordsPage.WORDS -> WordsContent(
                    uiState = uiState,
                    now = remember(uiState.learning, uiState.known) { System.currentTimeMillis() },
                    padding = padding,
                    onFilter = viewModel::setFilter,
                    onQuery = viewModel::setQuery,
                    scrollToTop = scrollToTop,
                    onToggleExpanded = viewModel::toggleExpanded,
                    onToggleReadWord = viewModel::toggleReadWord,
                    onSetStarred = viewModel::setStarred,
                    onMarkLearned = viewModel::markLearned,
                    onMarkLearning = viewModel::markLearning,
                    onDelete = viewModel::delete,
                    onIgnore = viewModel::ignore,
                    onShowIgnored = { showIgnored = true },
                    onAddToAnki = { word, lookup -> anki.whenReady { viewModel.addToAnki(word, lookup) } },
                    onAddAllToAnki = {
                        if (uiState.notInAnki > ADD_ALL_WITHOUT_ASKING) confirmAddAll = true else anki.whenReady(viewModel::addAllToAnki)
                    },
                    onExport = { exportFile.launch(resources.getString(R.string.anki_export_file_name)) },
                    onExportPackage = { exportPackage.launch(resources.getString(R.string.anki_package_file_name)) },
                    onExportCsv = { exportCsv.launch(resources.getString(R.string.csv_words_file_name)) },
                    onShowSession = { showSession = true },
                    onShowHelp = { showHelp = true },
                    onCheckAnki = { anki.whenReady(viewModel::checkAgainstAnki) },
                    offerKnown = offerKnown && ankiEnabled,
                    ankiEnabled = ankiEnabled,
                    onOfferKnown = knownFromAnki::start,
                    onDismissOffer = { ankiSettings.setKnownFromAnkiOffered(true) },
                    dueIds = dueIds,
                )
                WordsPage.REVIEW -> ReviewPage(
                    due = reviewDue,
                    newWords = reviewNew,
                    starredLearning = uiState.learning.orEmpty().count { it.starred },
                    onStart = onStartReview,
                    choice = reviewChoice,
                )
                WordsPage.DICTIONARY -> DictionaryPage(
                    padding = padding,
                    query = searchQuery,
                    results = searchResults,
                    words = searchWords,
                    history = history,
                    focusRequester = focus,
                    onQuery = search::setQuery,
                    onSearch = {
                        search.remember()
                        keyboard?.hide()
                    },
                    onOpenGroup = { open { searchPanel.showSearched(it) } },
                    onOpenWord = { open { searchPanel.showSearched(it.expression, it.reading) } },
                    onHistory = search::setQuery,
                    onForget = search::forget,
                    onForgetAll = search::forgetAll,
                )
            }
        }
    }
    if (showHelp) HowItWorksScreen(onBack = { showHelp = false })
    val state = panel
    if (state != null) {
        BackHandler { if (!searchPanel.back()) searchPanel.dismiss() }
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = SCRIM_ALPHA))
                // A tap beside the panel closes it, as one beside a dialog does.
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = searchPanel::dismiss),
        ) {
            LookupPanel(
                state = state,
                availableHeight = maxHeight,
                onClose = searchPanel::dismiss,
                onOpenDictionaries = searchPanel::dismiss,
                onAddToAnki = { group -> anki.whenReady { searchPanel.addToAnki(group) } },
                showAnki = ankiEnabled,
                onSetStarred = searchPanel::setStarred,
                onSetIgnored = searchPanel::setIgnored,
                onSetFurigana = searchPanel::setFurigana,
                onListen = searchPanel::listen,
                onLookupWord = searchPanel::lookupWord,
                onShowKanji = searchPanel::showKanji,
                onBack = { searchPanel.back() },
                onLearn = searchPanel::learn,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
            )
        }
    }
    }
}

/** The pages of Words, in tab order. */
private enum class WordsPage(val label: Int) {
    REVIEW(R.string.review_title),
    WORDS(R.string.nav_words),
    DICTIONARY(R.string.words_tab_dictionary),
}

/** Saved words a search lists under the dictionaries' at most. */
private const val MAX_WORDS_IN_SEARCH = 50
private const val SCRIM_ALPHA = 0.32f

@Composable
private fun WordsContent(
    uiState: WordsUiState,
    now: Long,
    padding: PaddingValues,
    onFilter: (WordFilter) -> Unit,
    onToggleExpanded: (id: Long) -> Unit,
    onToggleReadWord: (expression: String) -> Unit,
    onSetStarred: (id: Long, starred: Boolean) -> Unit,
    onMarkLearned: (WordEntity) -> Unit,
    onMarkLearning: (id: Long) -> Unit,
    onDelete: (id: Long) -> Unit,
    onIgnore: (WordEntity) -> Unit = {},
    onAddToAnki: (WordEntity, WordLookupEntity?) -> Unit,
    onAddAllToAnki: () -> Unit,
    onExport: () -> Unit,
    onExportPackage: () -> Unit,
    onShowIgnored: () -> Unit = {},
    onQuery: (String) -> Unit = {},
    scrollToTop: Int = 0,
    onExportCsv: () -> Unit = {},
    onShowSession: () -> Unit = {},
    onShowHelp: () -> Unit = {},
    onCheckAnki: () -> Unit = {},
    offerKnown: Boolean = false,
    ankiEnabled: Boolean = true,
    onOfferKnown: () -> Unit = {},
    onDismissOffer: () -> Unit = {},
    dueIds: Set<Long> = emptySet(),
) {
    // Above the keyboard when the filter field is typed in.
    Column(modifier = Modifier.fillMaxSize().consumeWindowInsets(padding).imePadding()) {
        var searchOpen by rememberSaveable { mutableStateOf(uiState.query.isNotEmpty()) }
        val closeSearch = {
            searchOpen = false
            onQuery("")
        }
        BackHandler(enabled = searchOpen, onBack = closeSearch)
        // One quiet row: Learning and Known as chips, a magnifier for the filter, the menu.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
        ) {
            if (searchOpen) {
                val focus = remember { FocusRequester() }
                LaunchedEffect(Unit) { focus.requestFocus() }
                OutlinedTextField(
                    value = uiState.query,
                    onValueChange = onQuery,
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.words_search_hint), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    trailingIcon = {
                        IconButton(onClick = closeSearch) {
                            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.words_search_close))
                        }
                    },
                    modifier = Modifier.weight(1f).padding(vertical = 4.dp).focusRequester(focus),
                )
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                    WordFilter.entries.forEach { filter ->
                        val selected = uiState.filter == filter
                        FilterChip(
                            selected = selected,
                            onClick = { onFilter(filter) },
                            label = {
                                Text(
                                    stringResource(
                                        filterLabel(filter),
                                        if (filter == WordFilter.LEARNING) uiState.learning?.size ?: 0 else uiState.knownCount,
                                    ),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            leadingIcon = if (selected) {
                                { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
                            } else {
                                null
                            },
                        )
                    }
                }
                IconButton(onClick = { searchOpen = true }) {
                    Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.words_search_open))
                }
            }
            val shown = if (uiState.filter == WordFilter.LEARNING) uiState.learning else uiState.known
            WordsMenu(
                onShowHelp = onShowHelp,
                onShowSession = onShowSession,
                onShowIgnored = onShowIgnored,
                onExport = onExport,
                onExportPackage = onExportPackage,
                onExportCsv = onExportCsv,
                ankiEnabled = ankiEnabled,
                exportEnabled = !shown.isNullOrEmpty() && uiState.ankiProgress == null,
            )
        }
        val progress = uiState.ankiProgress
        if (progress != null) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(
                    text = stringResource(
                        if (uiState.savingPackage) R.string.anki_package_progress else R.string.anki_adding_progress,
                        progress.first + 1,
                        progress.second,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                ProgressBar(
                    progress = { progress.first.toFloat() / progress.second.coerceAtLeast(1) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                )
            }
        }
        val listStates = WordFilter.entries.map { rememberLazyListState() }
        val firstScrollToTop = remember { scrollToTop }
        LaunchedEffect(scrollToTop) {
            if (scrollToTop != firstScrollToTop) listStates[uiState.filter.ordinal].animateScrollToItem(0)
        }
        val filter = uiState.filter
        WordList(
            filter = filter,
            listState = listStates[filter.ordinal],
            words = (if (filter == WordFilter.LEARNING) uiState.learning else uiState.known)
                ?.let { filterWords(it, uiState.query) },
            knownByReading = if (filter == WordFilter.KNOWN) filterReadWords(uiState.knownByReading, uiState.query) else emptyList(),
            uiState = uiState,
            now = now,
            dueIds = dueIds,
            onToggleExpanded = onToggleExpanded,
            onToggleReadWord = onToggleReadWord,
            onSetStarred = onSetStarred,
            onMarkLearned = onMarkLearned,
            onMarkLearning = onMarkLearning,
            onDelete = onDelete,
            onIgnore = onIgnore,
            onAddToAnki = onAddToAnki,
            onAddAllToAnki = onAddAllToAnki,
            onCheckAnki = onCheckAnki,
            offerKnown = offerKnown,
            ankiEnabled = ankiEnabled,
            onOfferKnown = onOfferKnown,
            onDismissOffer = onDismissOffer,
        )
    }
}

/** One of the two lists: [words] saved, then (known list) [knownByReading]. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WordList(
    filter: WordFilter,
    listState: LazyListState,
    words: List<WordEntity>?,
    knownByReading: List<Pair<String, Int>>,
    uiState: WordsUiState,
    now: Long,
    dueIds: Set<Long>,
    onToggleExpanded: (id: Long) -> Unit,
    onToggleReadWord: (expression: String) -> Unit,
    onSetStarred: (id: Long, starred: Boolean) -> Unit,
    onMarkLearned: (WordEntity) -> Unit,
    onMarkLearning: (id: Long) -> Unit,
    onDelete: (id: Long) -> Unit,
    onIgnore: (WordEntity) -> Unit = {},
    onAddToAnki: (WordEntity, WordLookupEntity?) -> Unit,
    onAddAllToAnki: () -> Unit,
    onCheckAnki: () -> Unit = {},
    offerKnown: Boolean = false,
    ankiEnabled: Boolean = true,
    onOfferKnown: () -> Unit = {},
    onDismissOffer: () -> Unit = {},
) {
    when {
        words == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        words.isEmpty() && knownByReading.isEmpty() -> Text(
            text = if (uiState.query.isBlank()) stringResource(emptyText(filter)) else stringResource(R.string.words_search_empty, uiState.query.trim()),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
        )
        else -> LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 8.dp), modifier = Modifier.fillMaxSize()) {
            if (filter == WordFilter.LEARNING && ankiEnabled) {
                item(key = "ankiActions") {
                    // Wraps below the first button on a narrow screen or with large text.
                    FlowRow(
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    ) {
                        if (uiState.ankiProgress == null && uiState.notInAnki > 0) {
                            // No icon: with it the two buttons don't fit on one row of a phone.
                            FilledTonalButton(onClick = onAddAllToAnki) {
                                Text(stringResource(R.string.action_add_all_to_anki, uiState.notInAnki))
                            }
                        }
                        TextButton(
                            onClick = onCheckAnki,
                            enabled = !uiState.checkingAnki && uiState.ankiProgress == null,
                        ) { Text(stringResource(R.string.action_check_against_anki)) }
                    }
                }
            }
            if (filter == WordFilter.LEARNING && offerKnown) {
                item(key = "offerKnown") {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Text(stringResource(R.string.anki_known_offer), style = MaterialTheme.typography.bodyMedium)
                        Row {
                            TextButton(onClick = onOfferKnown) { Text(stringResource(R.string.anki_known_choose)) }
                            TextButton(onClick = onDismissOffer) { Text(stringResource(R.string.anki_known_not_now)) }
                        }
                    }
                }
            }
            if (filter == WordFilter.LEARNING) {
                item(key = "knownCard") { KnownWordsCard() }
            }
            items(words, key = { it.id }) { word ->
                val expanded = word.id == uiState.expanded
                WordRow(
                    word = word,
                    now = now,
                    steps = if (filter == WordFilter.LEARNING) uiState.steps[word.id] else null,
                    memory = if (filter == WordFilter.LEARNING) uiState.memory[word.id] else null,
                    frequency = uiState.frequencies[word.id],
                    due = word.id in dueIds,
                    expanded = expanded,
                    lookups = if (expanded) uiState.lookups.filter { it.wordId == word.id } else emptyList(),
                    onToggleExpanded = { onToggleExpanded(word.id) },
                    onSetStarred = { onSetStarred(word.id, it) },
                    onMarkLearned = { onMarkLearned(word) },
                    onMarkLearning = { onMarkLearning(word.id) },
                    onDelete = { onDelete(word.id) },
                    onIgnore = { onIgnore(word) },
                    onAddToAnki = { lookup -> onAddToAnki(word, lookup) },
                    ankiEnabled = ankiEnabled,
                    adding = word.id in uiState.addingToAnki,
                )
                HorizontalDivider()
            }
            items(knownByReading, key = { "read:" + it.first }) { (expression, reads) ->
                ReadWordRow(
                    expression = expression,
                    reads = reads,
                    details = uiState.readWord?.takeIf { it.expression == expression },
                    onToggle = { onToggleReadWord(expression) },
                )
                HorizontalDivider()
            }
        }
    }
}

/**
 * A word known from reading alone, with how often it was read. It was never looked up, so no
 * reading or meaning is saved: a tap asks the dictionaries ([details], null while closed).
 */
@Composable
private fun ReadWordRow(expression: String, reads: Int, details: ReadWordDetails?, onToggle: () -> Unit) {
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(expression, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            Text(
                text = pluralStringResource(R.plurals.word_read_times, reads, reads),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val entry = details?.entry
        when {
            details == null -> Unit
            details.loading -> CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            entry == null -> Text(
                text = stringResource(R.string.words_read_no_entry),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> {
                if (entry.reading.isNotEmpty() && entry.reading != expression) {
                    Text(entry.reading, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                }
                // The short meaning, as a lookup in the reader first shows it.
                entry.sections.firstOrNull()?.let { section ->
                    if (section.lines.isNotEmpty()) {
                        GlossLines(LookupGroups.brief(section.lines), onLookup = {}, scanTaps = false)
                    } else {
                        val senses = section.senses.take(LookupGroups.BRIEF_SENSES)
                        senses.forEachIndexed { i, sense ->
                            val glosses = sense.glosses.joinToString("; ")
                            Text(
                                text = if (section.senses.size > 1) "${i + 1}. $glosses" else glosses,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Progress towards known: a short bar and the percentage. */
@Composable
private fun StepsBar(steps: Int) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(top = 6.dp),
    ) {
        ProgressBar(
            progress = { steps.toFloat() / WordProgress.KNOWN },
            modifier = Modifier.width(96.dp),
        )
        Text(
            text = stringResource(R.string.word_progress, WordProgress.percent(steps)),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/**
 * Memory of a word in review: a ring, so it never reads as the steps bar of words not in
 * review, filling up to known by memory.
 */
@Composable
private fun MemoryRing(memory: Float) {
    val percent = (memory * 100).toInt()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(top = 6.dp),
    ) {
        CircularProgressIndicator(
            progress = { memory },
            modifier = Modifier.size(18.dp),
            strokeWidth = 3.dp,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )
        Text(
            text = stringResource(R.string.word_memory, percent),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/**
 * Exports the list shown as a file for Anki on a computer, from a small rounded menu like the
 * library's order: a package with audio, or text.
 */
/** The top bar's overflow menu (three dots): this session's words and the exports. */
@Composable
private fun WordsMenu(
    onShowHelp: () -> Unit,
    onShowSession: () -> Unit,
    onShowIgnored: () -> Unit,
    onExport: () -> Unit,
    onExportPackage: () -> Unit,
    onExportCsv: () -> Unit,
    exportEnabled: Boolean,
    ankiEnabled: Boolean = true,
) {
    var open by remember { mutableStateOf(false) }
    var showKnown by remember { mutableStateOf(false) }
    if (showKnown) KnownWordsSheet(onDismiss = { showKnown = false })
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.action_more_options))
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            shape = RoundedCornerShape(24.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.how_open), style = MaterialTheme.typography.bodyLarge) },
                leadingIcon = { Icon(Icons.Filled.Info, contentDescription = null) },
                colors = MenuDefaults.itemColors(leadingIconColor = MaterialTheme.colorScheme.onSurfaceVariant),
                onClick = {
                    open = false
                    onShowHelp()
                },
                modifier = Modifier
                    .padding(horizontal = 8.dp)
                    .clip(RoundedCornerShape(16.dp)),
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.session_open), style = MaterialTheme.typography.bodyLarge) },
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = null) },
                colors = MenuDefaults.itemColors(leadingIconColor = MaterialTheme.colorScheme.onSurfaceVariant),
                onClick = {
                    open = false
                    onShowSession()
                },
                modifier = Modifier
                    .padding(horizontal = 8.dp)
                    .clip(RoundedCornerShape(16.dp)),
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.ignored_words_open), style = MaterialTheme.typography.bodyLarge) },
                leadingIcon = { Icon(painterResource(R.drawable.ic_hidden), contentDescription = null) },
                colors = MenuDefaults.itemColors(leadingIconColor = MaterialTheme.colorScheme.onSurfaceVariant),
                onClick = {
                    open = false
                    onShowIgnored()
                },
                modifier = Modifier
                    .padding(horizontal = 8.dp)
                    .clip(RoundedCornerShape(16.dp)),
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.known_title), style = MaterialTheme.typography.bodyLarge) },
                leadingIcon = { Icon(Icons.Filled.Check, contentDescription = null) },
                colors = MenuDefaults.itemColors(leadingIconColor = MaterialTheme.colorScheme.onSurfaceVariant),
                onClick = {
                    open = false
                    showKnown = true
                },
                modifier = Modifier
                    .padding(horizontal = 8.dp)
                    .clip(RoundedCornerShape(16.dp)),
            )
            HorizontalDivider()
            Text(
                text = stringResource(R.string.words_export),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 8.dp),
            )
            if (ankiEnabled) ExportItem(
                title = R.string.export_package,
                detail = R.string.export_package_detail,
                icon = R.drawable.ic_add_card,
                enabled = exportEnabled,
                onClick = {
                    open = false
                    onExportPackage()
                },
            )
            if (ankiEnabled) ExportItem(
                title = R.string.export_text,
                detail = R.string.export_text_detail,
                icon = R.drawable.ic_export,
                enabled = exportEnabled,
                onClick = {
                    open = false
                    onExport()
                },
            )
            HorizontalDivider()
            ExportItem(
                title = R.string.export_csv,
                detail = R.string.export_csv_detail,
                icon = R.drawable.ic_export,
                enabled = exportEnabled,
                onClick = {
                    open = false
                    onExportCsv()
                },
            )
        }
    }
}

@Composable
private fun ExportItem(title: Int, detail: Int, icon: Int, enabled: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        enabled = enabled,
        text = {
            Column {
                Text(stringResource(title), style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = stringResource(detail),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        leadingIcon = { Icon(painterResource(icon), contentDescription = null) },
        colors = MenuDefaults.itemColors(leadingIconColor = MaterialTheme.colorScheme.onSurfaceVariant),
        onClick = onClick,
        modifier = Modifier
            .padding(horizontal = 8.dp)
            .clip(RoundedCornerShape(16.dp)),
    )
}

private fun filterLabel(filter: WordFilter) = when (filter) {
    WordFilter.LEARNING -> R.string.words_filter_learning
    WordFilter.KNOWN -> R.string.words_filter_known
}

private fun emptyText(filter: WordFilter) = when (filter) {
    WordFilter.LEARNING -> R.string.words_empty_learning
    WordFilter.KNOWN -> R.string.words_empty_known
}

/**
 * One saved word. Collapsed: the word, its reading and one line of the first meaning, with a star
 * if starred and a dot if due for review. A tap opens the rest (sentences, when and where it was
 * looked up, progress) and the row of actions.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WordRow(
    word: WordEntity,
    now: Long,
    expanded: Boolean,
    lookups: List<WordLookupEntity>,
    /** Progress towards known, shown in the learning list. */
    steps: Int?,
    /** Memory towards known (0 to 1) of a word in review, shown instead of [steps]. */
    memory: Float? = null,
    /** How common the word is, if the setting is on and a frequency dictionary knows it. */
    frequency: Frequency? = null,
    /** The word's card is due today. */
    due: Boolean = false,
    onToggleExpanded: () -> Unit,
    onSetStarred: (Boolean) -> Unit,
    onMarkLearned: () -> Unit,
    onMarkLearning: () -> Unit,
    onDelete: () -> Unit,
    onAddToAnki: (WordLookupEntity?) -> Unit,
    ankiEnabled: Boolean = true,
    adding: Boolean = false,
    onIgnore: () -> Unit = {},
) {
    val highlight = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggleExpanded)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // The reading follows the word, or moves below it when both don't fit on one line.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f),
            ) {
                Text(word.expression, style = MaterialTheme.typography.titleLarge)
                if (word.reading.isNotEmpty() && word.reading != word.expression) {
                    Text(
                        text = word.reading,
                        style = MaterialTheme.typography.bodyMedium,
                        color = muted,
                        modifier = Modifier
                            .align(Alignment.Bottom)
                            .padding(bottom = 3.dp),
                    )
                }
                if (expanded) frequency?.let { FrequencyBadge(it) }
            }
            if (word.starred) {
                Icon(
                    Icons.Filled.Star,
                    contentDescription = stringResource(R.string.word_starred),
                    tint = highlight,
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .size(16.dp),
                )
            }
            if (due) {
                val description = stringResource(R.string.word_due)
                Box(
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .size(8.dp)
                        .background(highlight, CircleShape)
                        .semantics { contentDescription = description },
                )
            }
        }
        if (word.gloss.isNotEmpty()) {
            Text(
                text = word.gloss,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = if (expanded) Int.MAX_VALUE else 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (expanded) {
            if (word.note.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.word_note_line, word.note),
                    style = MaterialTheme.typography.bodySmall,
                    color = muted,
                )
            }
            if (lookups.isNotEmpty()) {
                for (lookup in lookups) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.weight(1f)) {
                            Sentence(
                                sentence = sentenceText(
                                    lookup.sentence,
                                    lookup.surfaceStart,
                                    lookup.surfaceEnd,
                                    highlight,
                                ),
                                source = lookup.bookTitle,
                                maxLines = Int.MAX_VALUE,
                            )
                        }
                        // A card with this sentence instead of the latest one.
                        if (ankiEnabled && word.ankiNoteId == null && lookups.size > 1) {
                            IconButton(
                                onClick = { onAddToAnki(lookup) },
                                enabled = !adding,
                                modifier = Modifier.size(ACTION_SIZE),
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_add_card),
                                    contentDescription = stringResource(R.string.action_add_to_anki_with_sentence),
                                    tint = muted,
                                )
                            }
                        }
                    }
                }
            } else {
                Sentence(
                    sentence = sentenceText(word.lastSentence, word.lastSurfaceStart, word.lastSurfaceEnd, highlight),
                    source = null,
                    maxLines = Int.MAX_VALUE,
                )
            }
            if (memory != null) MemoryRing(memory) else steps?.let { StepsBar(it) }
            Text(
                text = details(word, now),
                style = MaterialTheme.typography.labelSmall,
                color = muted,
                modifier = Modifier.padding(top = 6.dp),
            )
            Row(
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
            ) {
                if (ankiEnabled) AnkiButton(inAnki = word.ankiNoteId != null, adding = adding, onClick = { onAddToAnki(null) })
                IconToggleButton(
                    checked = word.starred,
                    onCheckedChange = onSetStarred,
                    modifier = Modifier.size(ACTION_SIZE),
                ) {
                    Icon(
                        imageVector = if (word.starred) Icons.Filled.Star else Icons.Outlined.Star,
                        contentDescription = stringResource(
                            if (word.starred) R.string.action_unstar_word else R.string.action_star_word,
                        ),
                        tint = if (word.starred) highlight else muted,
                    )
                }
                if (word.status == WordStatus.LEARNED) {
                    IconButton(onClick = onMarkLearning, modifier = Modifier.size(ACTION_SIZE)) {
                        Icon(
                            Icons.Filled.Refresh,
                            contentDescription = stringResource(R.string.action_mark_learning),
                            tint = muted,
                        )
                    }
                } else {
                    IconButton(onClick = onMarkLearned, modifier = Modifier.size(ACTION_SIZE)) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = stringResource(R.string.action_mark_learned),
                            tint = muted,
                        )
                    }
                }
                // Names and wrong splits: no marks, no counts, no review (see WordEntity.ignored).
                IconButton(onClick = onIgnore, modifier = Modifier.size(ACTION_SIZE)) {
                    Icon(
                        painterResource(R.drawable.ic_hidden),
                        contentDescription = stringResource(R.string.action_ignore_word),
                        tint = muted,
                    )
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(ACTION_SIZE)) {
                    Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.action_delete), tint = muted)
                }
            }
        }
    }
}

/**
 * Adds the word to Anki: a spinner while the card is made (audio takes a few seconds), then a
 * check once it is there (tapping again says so).
 */
@Composable
private fun AnkiButton(inAnki: Boolean, adding: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = !adding, modifier = Modifier.size(ACTION_SIZE)) {
        if (adding) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        } else {
            Icon(
                painter = painterResource(if (inAnki) R.drawable.ic_in_anki else R.drawable.ic_add_card),
                contentDescription = stringResource(if (inAnki) R.string.word_in_anki else R.string.action_add_to_anki),
                tint = if (inAnki) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The word actions' touch size. */
private val ACTION_SIZE = 48.dp

@Composable
private fun Sentence(sentence: AnnotatedString, source: String?, maxLines: Int) {
    Column(modifier = Modifier.padding(top = 6.dp)) {
        Text(
            text = sentence,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
        )
        source?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** "3 lookups · 2 days ago · 吾輩は猫である" */
@Composable
private fun details(word: WordEntity, now: Long): String {
    // Words marked as known without a lookup (word lists, Anki decks) were never looked up.
    if (word.lookupCount == 0) return word.lastBookTitle.orEmpty()
    val count = pluralStringResource(R.plurals.words_lookup_count, word.lookupCount, word.lookupCount)
    // Android says "0 minutes ago" for the first minute.
    val time = if (now - word.lastLookedUpAt < DateUtils.MINUTE_IN_MILLIS) {
        stringResource(R.string.words_just_now)
    } else {
        DateUtils.getRelativeTimeSpanString(word.lastLookedUpAt, now, DateUtils.MINUTE_IN_MILLIS).toString()
    }
    return listOfNotNull(count, time, word.lastBookTitle).joinToString(" · ")
}

/** The sentence with the looked-up text emphasised. */
private fun sentenceText(sentence: String, start: Int, end: Int, color: Color): AnnotatedString {
    val from = start.coerceIn(0, sentence.length)
    val to = end.coerceIn(from, sentence.length)
    return buildAnnotatedString {
        append(sentence.substring(0, from))
        withStyle(SpanStyle(color = color, fontWeight = FontWeight.Bold)) {
            append(sentence.substring(from, to))
        }
        append(sentence.substring(to))
    }
}

@Preview
@Composable
private fun WordsPreview() {
    val now = 1_800_000_000_000L
    fun word(id: Long, expression: String, reading: String, gloss: String, count: Int, starred: Boolean = false) =
        WordEntity(
            id = id,
            expression = expression,
            reading = reading,
            gloss = gloss,
            starred = starred,
            lookupCount = count,
            firstLookedUpAt = now - 86_400_000L * 3,
            lastLookedUpAt = now - 3_600_000L,
            rankKey = 0.0,
            lastSentence = "吾輩は猫である。名前はまだ無い。",
            lastSurfaceStart = 8,
            lastSurfaceEnd = 10,
            lastBookTitle = "吾輩は猫である",
        )
    HonmaniTheme {
        WordsContent(
            uiState = WordsUiState(
                learning = listOf(
                    word(1, "名前", "なまえ", "name; full name", 4, starred = true),
                    word(2, "見当", "けんとう", "estimate; guess; aim", 2),
                ),
                known = emptyList(),
                steps = mapOf(1L to 4, 2L to 1),
                knownCount = 40,
                notInAnki = 2,
            ),
            now = now,
            padding = PaddingValues(),
            onFilter = {},
            onToggleExpanded = {},
            onToggleReadWord = {},
            onSetStarred = { _, _ -> },
            onMarkLearned = {},
            onMarkLearning = {},
            onDelete = {},
            onAddToAnki = { _, _ -> },
            onAddAllToAnki = {},
            onExport = {},
            onExportPackage = {},
        )
    }
}
