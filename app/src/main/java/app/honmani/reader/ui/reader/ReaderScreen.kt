package app.honmani.reader.ui.reader

import app.honmani.reader.diagnostics.logWarning
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.withFrameNanos
import app.honmani.reader.data.library.BookText
import app.honmani.reader.data.library.BookmarkEntity
import app.honmani.reader.data.library.HighlightEntity
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.honmani.reader.HonmaniApplication
import app.honmani.reader.R
import app.honmani.reader.data.settings.ReaderSettings
import app.honmani.reader.data.words.SessionWords
import app.honmani.reader.ui.HomeNavigationBar
import app.honmani.reader.ui.HomeNavigationRail
import app.honmani.reader.ui.review.ReviewScreen
import app.honmani.reader.ui.review.ReviewScopeChoice
import app.honmani.reader.ui.HomeTab
import app.honmani.reader.ui.anki.rememberAnkiAccess
import app.honmani.reader.ui.anki.showAnkiResult
import app.honmani.reader.ui.anki.showVoiceProblem
import app.honmani.reader.ui.library.AddBookStart
import app.honmani.reader.ui.library.LibraryScreen
import app.honmani.reader.ui.library.LibraryViewModel
import app.honmani.reader.ui.onboarding.OnboardingScreen
import app.honmani.reader.ui.progress.ProgressTab
import app.honmani.reader.ui.settings.AutoBackupOffer
import app.honmani.reader.ui.settings.ReaderSettingsScreen
import app.honmani.reader.ui.settings.SettingsScreen
import app.honmani.reader.ui.settings.syncFailureReason
import app.honmani.reader.ui.words.SessionSheet
import app.honmani.reader.ui.words.WordsScreen
import app.honmani.reader.ui.words.openInDictionary
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The app's home: the library, words, dictionaries and settings tabs, and ッツ Ebook Reader with
 * the lookup panel once a book is open. The reader WebView stays loaded underneath the tabs (on
 * ttu's own book list, which they cover), so books open quickly and the library can hand new
 * books to ttu.
 */
@Composable
fun ReaderScreen(
    viewModel: ReaderViewModel = viewModel(factory = ReaderViewModel.Factory),
) {
    val supported = remember { isReaderWebViewSupported() }

    Surface(modifier = Modifier.fillMaxSize()) {
        if (!supported) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(stringResource(R.string.reader_webview_unsupported))
            }
        } else {
            ReaderWithTabs(viewModel)
        }
    }
}

@Composable
private fun ReaderWithTabs(viewModel: ReaderViewModel) {
    val panel by viewModel.panel.collectAsStateWithLifecycle()
    var atLibrary by remember { mutableStateOf(true) }
    // A picture of the book open in the zoom viewer (a tap on it).
    var picture by remember { mutableStateOf<ByteArray?>(null) }
    var tab by rememberSaveable { mutableStateOf(HomeTab.LIBRARY) }
    val context = LocalContext.current
    val resources = LocalResources.current
    val shareTitle = stringResource(R.string.action_share_diagnostics)
    val anki = rememberAnkiAccess()
    val container = remember { (context.applicationContext as HonmaniApplication).container }
    val dictionaryUpdates by remember { container.dictionaryUpdateChecker.updates }.collectAsStateWithLifecycle(emptyList())
    // Reading time counts while a book is on screen and the app is in front.
    val tracker = remember { container.readingTracker }
    val showPreviousSentence by container.appearanceSettings.showPreviousSentence.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            tracker.setForeground(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
            // Characters read count from ttu's positions; ask before the app may be closed.
            if (event == Lifecycle.Event.ON_STOP) {
                container.ttuLibrary.requestProgress()
                container.progressSync.flush()
                container.saveAutoBackup()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            tracker.setForeground(false)
        }
    }
    // With "Count time while a lookup is open" off, the timer pauses while the panel is open
    // (its touches are native: the book sees no activity).
    val countDuringLookup by container.readingTimeSettings.countDuringLookup.collectAsStateWithLifecycle()
    LaunchedEffect(countDuringLookup) { tracker.setCountLookupTime(countDuringLookup) }
    LaunchedEffect(panel != null) { tracker.setLookupOpen(panel != null) }
    LaunchedEffect(atLibrary) {
        tracker.setVisible(!atLibrary)
        // Back at the library: the position reached goes to the sync server now.
        if (atLibrary) container.progressSync.flush()
    }
    // The first-start screen, once: not for a library that has books or dictionaries already.
    val onboardingDone by container.onboardingSettings.done.collectAsStateWithLifecycle()
    var onboardingChecked by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!container.onboardingSettings.done.value) {
            val books = container.bookDao.observeAll().first()
            val dictionaries = container.dictionaryRepository.dictionaries.first()
            if (books.isNotEmpty() || dictionaries.isNotEmpty()) container.onboardingSettings.finish()
        }
        onboardingChecked = true
    }
    var addBookOnStart by remember { mutableStateOf<AddBookStart?>(null) }
    // Honmani's reader settings, from the Settings tab or the reader's top bar.
    var readerSettings by rememberSaveable { mutableStateOf(false) }
    // The words of this reading session, from the reader's bottom menu.
    var sessionShown by rememberSaveable { mutableStateOf(false) }
    // The search in the open book, from the reader's bottom menu; ttu's id of the book.
    val scope = rememberCoroutineScope()
    var searchShown by rememberSaveable { mutableStateOf(false) }
    var openTtuId by rememberSaveable { mutableStateOf<Long?>(null) }
    // Bookmarks and jumps in the open book (reader menu): the list, a bookmark being named, "Back to where you were".
    var bookmarksShown by rememberSaveable { mutableStateOf(false) }
    var bookmarkDraft by remember { mutableStateOf<BookmarkDraft?>(null) }
    val jumpSnackbar = remember { SnackbarHostState() }
    val jumper = remember(viewModel) { PlaceJumper(viewModel.ttuLibrary, container.progressSync, scope, jumpSnackbar) }
    val bookHash by produceState<String?>(null, openTtuId) {
        value = openTtuId?.let { container.bookDao.findByTtuId(it)?.contentHash }
    }
    val bookmarks by remember(bookHash) {
        bookHash?.let { container.bookmarkDao.observe(it) } ?: kotlinx.coroutines.flow.flowOf(emptyList())
    }.collectAsState(emptyList())
    // Highlights: the list (reader menu), and the book's section table the page needs to draw them
    // and the lookup panel to save one (null for a book that is not an EPUB: no highlighting there).
    var highlightsShown by rememberSaveable { mutableStateOf(false) }
    val highlights by remember(bookHash) {
        bookHash?.let { container.highlightDao.observe(it) } ?: kotlinx.coroutines.flow.flowOf(emptyList())
    }.collectAsState(emptyList())
    val sectionsTotal by produceState<Int?>(null, openTtuId) {
        value = openTtuId?.let { id -> container.bookDao.findByTtuId(id)?.let { container.sectionStarts(it)?.total } }
    }
    LaunchedEffect(atLibrary, openTtuId) { jumpSnackbar.currentSnackbarData?.dismiss() }
    // The Dictionaries screen, under Settings; lookups without a dictionary open it directly.
    var dictionariesShown by rememberSaveable { mutableStateOf(false) }
    val library: LibraryViewModel = viewModel(factory = LibraryViewModel.Factory)
    // Books opened with Honmani from other apps: added in the library, a single one opened.
    LaunchedEffect(library) {
        container.openedBooks.received.collect { uris ->
            tab = HomeTab.LIBRARY
            library.addOpened(uris)
        }
    }
    // The launcher's "Search" shortcut: Words with its search field focused, out of an open book.
    var focusSearch by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(container) {
        container.searchRequests.received.collect {
            if (!atLibrary) viewModel.closeBook()
            tab = HomeTab.WORDS
            focusSearch = true
        }
    }
    // The review screen, from Words or the launcher's "Review" shortcut.
    var reviewShown by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(container) {
        container.searchRequests.reviewReceived.collect {
            if (!atLibrary) viewModel.closeBook()
            tab = HomeTab.WORDS
            reviewShown = true
        }
    }
    // Words due, for the badge on the Words tab, and the new words a session would start.
    val reviewScope by container.reviewScope.collectAsStateWithLifecycle()
    val reviewBooks by remember(container) { container.bookDao.observeAll() }.collectAsStateWithLifecycle(emptyList())
    val dueToday by remember(container) {
        flow { emitAll(container.reviewRepository.observeDueToday(container.reviewSettings.reviewsPerDay)) }
    }.collectAsStateWithLifecycle(emptyList())
    val reviewDue = dueToday.size
    val reviewNew by remember(container) {
        flow { emitAll(container.reviewRepository.observeNewAvailable(container.reviewSettings.newPerDay)) }
    }.collectAsStateWithLifecycle(0)
    LaunchedEffect(viewModel) {
        viewModel.ankiResults.collect { (expression, result) -> showAnkiResult(context, expression, result) }
    }
    LaunchedEffect(viewModel) {
        viewModel.voiceProblems.collect { showVoiceProblem(context, it) }
    }

    // The words with a review card due today, for their tint in the book (word-marks.js).
    val dueWords = remember(dueToday) { dueToday.mapTo(HashSet()) { it.expression } as Set<String> }

    // While a book is open: the words being learned and how they were written, so marks follow
    // lookups (word-marks.js).
    val learning by produceState<Pair<Set<String>, Set<String>>?>(null, atLibrary) {
        value = null
        if (!atLibrary) container.wordKnowledge.map { it.learning to it.learningForms }.distinctUntilChanged().collect { value = it }
    }

    // Reader settings the app itself acts on (volume buttons, long-press lookup), kept in ttu's
    // storage, read again when a book opens and after the reader settings close.
    var settingsVersion by remember { mutableStateOf(0) }
    val appSettings by produceState(ReaderSettings(), atLibrary, readerSettings, settingsVersion) {
        if (atLibrary || readerSettings) return@produceState
        value = try {
            ReaderSettings.fromStorage(viewModel.ttuLibrary.readSettings())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logWarning("Honmani.reader", "Reading the reader settings failed", e)
            value
        }
    }

    // The book's background, also behind the status and navigation bars while a book is shown.
    var bookBackground by remember { mutableStateOf<Color?>(null) }
    val frame = bookBackground.takeIf { !atLibrary && !readerSettings }
    BarIcons(background = frame)
    ImmersiveBars(hidden = appSettings.hidesBars(atLibrary, readerSettings))

    Box(modifier = Modifier.fillMaxSize()) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize().then(frame?.let { Modifier.background(it) } ?: Modifier)) {
            ReaderWebView(
                onLookup = viewModel::onTap,
                onDismiss = viewModel::dismiss,
                highlight = viewModel.highlight,
                diagnostics = viewModel.diagnostics,
                ttuLibrary = viewModel.ttuLibrary,
                booksDir = viewModel.booksDir,
                onBookChanged = {
                    openTtuId = it
                    viewModel.onBookChanged(it)
                    tracker.setBook(it)
                },
                onActivity = tracker::onActivity,
                onPage = { ttuId, characters, progress ->
                    tracker.onPage(ttuId, characters, progress)
                    container.progressSync.onPosition(ttuId, characters)
                },
                onOpenSettings = {
                    viewModel.dismiss()
                    readerSettings = true
                },
                onOpenMenu = viewModel::openMenu,
                onImage = {
                    viewModel.dismiss()
                    picture = it
                },
                closeRequests = viewModel.closeRequests,
                onAtLibraryChanged = {
                    atLibrary = it
                    if (it) {
                        viewModel.dismiss()
                        library.bookClosed()
                    }
                },
                onBackground = { bookBackground = Color(it) },
                marks = { texts, mode, knownWords, sentences, dueMarks, isStale ->
                    container.wordMarker.mark(texts, mode, knownWords, sentences, dueMarks, isStale)
                },
                marksKey = learning?.let { it to dueWords },
                highlightsMessage = { bookId ->
                    container.bookDao.findByTtuId(bookId)?.let { book ->
                        container.sectionStarts(book)?.let { sections ->
                            highlightsMessage(bookId, sections, container.highlightDao.observe(book.contentHash).first())
                        }
                    }
                },
                highlightsKey = highlights,
                volumeKeysTurnPages = appSettings.volumeKeys,
                hideSelectionToolbar = appSettings.selectionLookup,
                panelOpen = panel != null,
                modifier = Modifier
                    .fillMaxSize()
                    // In immersive mode a book fills the screen, also under bars a swipe shows for
                    // a moment: its size doesn't follow them, so they never reflow the page.
                    .windowInsetsPadding(if (appSettings.immersive && !atLibrary) bookInsets else WindowInsets.safeDrawing),
            )
            val state = panel
            // What the lookup panel offers for highlighting: the colours of a new selection (when
            // selection lookup is on), or of the highlight a tap landed on, with "Remove highlight".
            val savedMessage = stringResource(R.string.highlight_saved)
            val removedMessage = stringResource(R.string.highlight_removed)
            val undoLabel = stringResource(R.string.action_undo)
            fun highlightControls(tap: TapLookup): HighlightControls? {
                val existing = tap.highlightId?.let { id -> highlights.firstOrNull { it.id == id } }
                if (existing != null) {
                    return HighlightControls(
                        current = existing.color,
                        onPick = { color -> scope.launch { container.highlightDao.setColor(existing.id, color) } },
                        onRemove = {
                            viewModel.dismiss()
                            scope.launch {
                                container.highlightDao.delete(existing.id)
                                jumpSnackbar.currentSnackbarData?.dismiss()
                                val result = jumpSnackbar.showSnackbar(removedMessage, undoLabel, duration = SnackbarDuration.Long)
                                if (result == SnackbarResult.ActionPerformed) container.highlightDao.put(existing)
                            }
                        },
                    )
                }
                val passage = tap.selected
                val hash = bookHash
                val total = sectionsTotal
                if (!tap.bySelection || passage == null || hash == null || total == null || !appSettings.selectionLookup) return null
                return HighlightControls(
                    current = null,
                    onPick = { color ->
                        viewModel.dismiss()
                        scope.launch {
                            val same = highlights.firstOrNull { it.start == passage.start && it.end == passage.end }
                            jumpSnackbar.currentSnackbarData?.dismiss()
                            if (same != null) {
                                container.highlightDao.setColor(same.id, color)
                                jumpSnackbar.showSnackbar(savedMessage)
                            } else {
                                val id = container.highlightDao.put(
                                    HighlightEntity(
                                        contentHash = hash, start = passage.start, end = passage.end, color = color,
                                        text = passage.text, total = total.toLong(), createdAt = System.currentTimeMillis(),
                                    ),
                                )
                                val result = jumpSnackbar.showSnackbar(savedMessage, undoLabel, duration = SnackbarDuration.Long)
                                if (result == SnackbarResult.ActionPerformed) container.highlightDao.delete(id)
                            }
                        }
                    },
                    onRemove = null,
                )
            }
            val countedTap by viewModel.countedTap.collectAsStateWithLifecycle()
            if (state != null && !atLibrary) {
                // The frame after this state was first composed: it has been drawn (for the log).
                LaunchedEffect(state) {
                    withFrameNanos {}
                    viewModel.onPanelDrawn(state)
                }
                // Composed after the WebView, so back closes the panel first.
                BackHandler { if (!viewModel.back()) viewModel.dismiss() }
                LookupPanel(
                    state = state,
                    onClose = viewModel::dismiss,
                    onAddToAnki = { group -> anki.whenReady { viewModel.addToAnki(group) } },
                    onSetStarred = viewModel::setStarred,
                onSetIgnored = viewModel::setIgnored,
                    onEditLookup = { openInDictionary(context, it) },
                    onListen = viewModel::listen,
                    onLookupWord = viewModel::lookupWord,
                    onShowKanji = viewModel::showKanji,
                    onBack = { viewModel.back() },
                    previousSentence = if (showPreviousSentence) state.tap.previousSentence else "",
                    onReadSentence = viewModel::readSentence,
                    onDontCount = if (countedTap == state.tap) viewModel::dontCount else null,
                    onShorterMatch = { viewModel.changeMatch(longer = false) },
                    onLongerMatch = { viewModel.changeMatch(longer = true) },
                    onOpenDictionaries = {
                        // Leave the book for the Dictionaries screen.
                        tab = HomeTab.SETTINGS
                        dictionariesShown = true
                        viewModel.ttuLibrary.show(LIBRARY_PATH)
                    },
                    highlight = highlightControls(state.tap),
                    availableHeight = maxHeight,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
            val viewing = picture
            if (viewing != null && !atLibrary) {
                PictureViewer(viewing, onClose = { picture = null })
            }
        }
        // A picture belongs to the book it was in.
        LaunchedEffect(atLibrary) { if (atLibrary) picture = null }
        if (atLibrary && !onboardingDone) {
            if (onboardingChecked) {
                OnboardingScreen(
                    onTrySample = {
                        tab = HomeTab.LIBRARY
                        container.onboardingSettings.finish()
                        library.addSample()
                    },
                    onAddBook = {
                        tab = HomeTab.LIBRARY
                        addBookOnStart = AddBookStart.FILES
                        container.onboardingSettings.finish()
                    },
                    onAddServer = {
                        tab = HomeTab.LIBRARY
                        addBookOnStart = AddBookStart.SERVER
                        container.onboardingSettings.finish()
                    },
                    onSkip = container.onboardingSettings::finish,
                )
            } else {
                // A moment while the app checks for books; covers ttu's list like the tabs.
                Surface(modifier = Modifier.fillMaxSize()) {}
            }
        } else if (atLibrary) {
            // Opaque, and on top: the tabs take all touches while ttu's list stays loaded below.
            BackHandler(enabled = tab != HomeTab.LIBRARY) { tab = HomeTab.LIBRARY }
            // Tapping the open tab again scrolls its list to the top.
            var libraryTaps by remember { mutableIntStateOf(0) }
            var wordsTaps by remember { mutableIntStateOf(0) }
            val onSelectTab = { selected: HomeTab ->
                if (selected == tab) {
                    when (selected) {
                        HomeTab.LIBRARY -> libraryTaps++
                        HomeTab.WORDS -> wordsTaps++
                        else -> Unit
                    }
                }
                tab = selected
                // A tab opens at its top level.
                dictionariesShown = false
            }
            val badges = if (dictionaryUpdates.isNotEmpty()) setOf(HomeTab.SETTINGS) else emptySet()
            val counts = mapOf(HomeTab.WORDS to reviewDue)
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                // In landscape (wider than tall) the tabs go at the side and the screens get no bottom bar.
                val atTheSide = maxWidth > maxHeight
                val bottomBar = @Composable {
                    if (!atTheSide) HomeNavigationBar(selected = tab, onSelect = onSelectTab, badges = badges, counts = counts)
                }
                Row(modifier = Modifier.fillMaxSize()) {
                    if (atTheSide) HomeNavigationRail(selected = tab, onSelect = onSelectTab, badges = badges, counts = counts)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            // The rail keeps clear of the system bars on its side.
                            .then(if (atTheSide) Modifier.consumeWindowInsets(WindowInsets.safeDrawing.only(WindowInsetsSides.Start)) else Modifier),
                    ) {
                        when (tab) {
                            HomeTab.LIBRARY -> LibraryScreen(
                                bottomBar = bottomBar,
                                viewModel = library,
                                addBookOnStart = addBookOnStart,
                                onAddBookStarted = { addBookOnStart = null },
                                onShowProgress = { tab = HomeTab.PROGRESS },
                                scrollToTop = libraryTaps,
                            )
                            HomeTab.WORDS -> WordsScreen(
                                bottomBar = bottomBar,
                                scrollToTop = wordsTaps,
                                focusSearch = focusSearch,
                                onSearchFocused = { focusSearch = false },
                                reviewDue = reviewDue,
                                reviewNew = reviewNew,
                                onStartReview = { reviewShown = true },
                                reviewChoice = ReviewScopeChoice(reviewScope, reviewBooks.filter { !it.hidden }) {
                                    container.reviewScope.value = it
                                },
                            )
                            HomeTab.PROGRESS -> ProgressTab(bottomBar = bottomBar, onOpenBook = library::open)
                            HomeTab.SETTINGS -> SettingsScreen(
                                bottomBar = bottomBar,
                                showingDictionaries = dictionariesShown,
                                onShowDictionaries = { dictionariesShown = it },
                                onOpenReaderSettings = { readerSettings = true },
                                onOpenSample = {
                                    // Back from the book is the library, where it now is.
                                    tab = HomeTab.LIBRARY
                                    library.addSample(it)
                                },
                                onShareDiagnostics = {
                                    val share = Intent(Intent.ACTION_SEND)
                                        .setType("text/plain")
                                        .putExtra(Intent.EXTRA_SUBJECT, "Honmani diagnostics")
                                        .putExtra(Intent.EXTRA_TEXT, viewModel.diagnostics.report(context))
                                    context.startActivity(Intent.createChooser(share, shareTitle))
                                },
                            )
                        }
                    }
                }
            }
        }
        // A backup Android brought back from the previous phone, offered once on a new install.
        AutoBackupOffer()
        // Progress sync asks before going back to another device's earlier place in a book.
        val syncQuestion by library.syncQuestion.collectAsStateWithLifecycle()
        syncQuestion?.let { question ->
            AlertDialog(
                onDismissRequest = { library.answerSync(false) },
                title = { Text(stringResource(R.string.sync_question_title)) },
                text = { Text(stringResource(R.string.sync_question_message, question.device, question.theirs, question.ours)) },
                confirmButton = {
                    TextButton(onClick = { library.answerSync(false) }) {
                        Text(stringResource(R.string.sync_question_stay, question.ours))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { library.answerSync(true) }) {
                        Text(stringResource(R.string.sync_question_go, question.theirs))
                    }
                },
            )
        }
        val menuOpen by viewModel.menuOpen.collectAsStateWithLifecycle()
        // The book's text, only while the menu, the bookmark list or a new bookmark needs it (the slider, names, the page's spot).
        val textWanted = !atLibrary && (menuOpen || bookmarksShown || bookmarkDraft != null || highlightsShown)
        val bookText by produceState<BookText?>(null, openTtuId, textWanted) {
            val id = openTtuId
            value = if (textWanted && id != null) container.searchableBook(id) else null
        }
        val jumpMessage = { percent: Int -> resources.getString(R.string.jump_back_message, percent) }
        val backLabel = stringResource(R.string.jump_back_action)
        val defaultName = { percent: Int -> resources.getString(R.string.bookmark_default_name, percent) }
        if (menuOpen && !atLibrary) {
            val syncState by container.koSyncStatus.state.collectAsStateWithLifecycle()
            val syncFailure = syncState.failureFor(openTtuId)
            val place by produceState<MenuPlace?>(null, bookText) {
                val id = openTtuId
                val text = bookText
                value = if (id == null || text == null) null else {
                    val characters = try {
                        viewModel.ttuLibrary.bookmark(id).first
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
                    MenuPlace(characters ?: 0L, text.total)
                }
            }
            ReaderMenuSheet(
                showProgress = appSettings.showProgress,
                syncFailure = syncFailure?.let { syncFailureReason(it) },
                place = place,
                onJump = { characters ->
                    val id = openTtuId
                    val text = bookText
                    if (id != null && text != null) {
                        viewModel.closeMenu()
                        val spot = text.around(characters.toInt())
                        jumper.jump(
                            id, characters, text.total.toLong(), spot?.hint.orEmpty(), spot?.match.orEmpty(), text,
                            jumpMessage(BookPlace.percent(characters, text.total.toLong())), backLabel,
                        )
                    }
                },
                onAddBookmark = {
                    val text = bookText
                    val here = place
                    if (text != null && here != null) {
                        viewModel.closeMenu()
                        val name = BookPlace.defaultName(text.around(here.characters.toInt())?.after.orEmpty())
                            ?: defaultName(BookPlace.percent(here.characters, here.total.toLong()))
                        bookmarkDraft = BookmarkDraft(here.characters, here.total.toLong(), name)
                    }
                },
                onBookmarks = {
                    viewModel.closeMenu()
                    bookmarksShown = true
                },
                onHighlights = {
                    viewModel.closeMenu()
                    highlightsShown = true
                },
                onContents = {
                    viewModel.closeMenu()
                    viewModel.ttuLibrary.openContents()
                },
                onSearch = {
                    viewModel.closeMenu()
                    searchShown = true
                },
                onSettings = {
                    viewModel.closeMenu()
                    readerSettings = true
                },
                onSessionWords = {
                    viewModel.closeMenu()
                    sessionShown = true
                },
                onCloseBook = viewModel::closeBook,
                onSetShowProgress = {
                    // ttu reads the setting when the page loads: the page reloads, in place.
                    viewModel.ttuLibrary.writeSettings(ReaderSettings.progressStorage(it))
                    settingsVersion++
                    viewModel.closeMenu()
                    viewModel.ttuLibrary.reload()
                },
                onDismiss = viewModel::closeMenu,
            )
        }
        bookmarkDraft?.let { draft ->
            val added = stringResource(R.string.bookmark_added)
            BookmarkNameDialog(
                title = stringResource(R.string.bookmark_dialog_title),
                initial = draft.name,
                onSave = { name ->
                    bookmarkDraft = null
                    val hash = bookHash
                    if (hash != null) {
                        scope.launch {
                            container.bookmarkDao.put(
                                BookmarkEntity(contentHash = hash, name = name, characters = draft.characters, total = draft.total, createdAt = System.currentTimeMillis()),
                            )
                            jumpSnackbar.currentSnackbarData?.dismiss()
                            jumpSnackbar.showSnackbar(added)
                        }
                    }
                },
                onDismiss = { bookmarkDraft = null },
            )
        }
        if (bookmarksShown && !atLibrary) {
            BookmarksSheet(
                bookmarks = bookmarks,
                onOpen = { bookmark ->
                    bookmarksShown = false
                    val id = openTtuId
                    if (id != null) {
                        val text = bookText
                        val spot = text?.aroundPageStart(bookmark.characters.toInt())
                        jumper.jump(
                            id, bookmark.characters, bookmark.total, spot?.hint.orEmpty(), spot?.match.orEmpty(), text,
                            jumpMessage(bookmark.percent), backLabel,
                        )
                    }
                },
                onRename = { bookmark, name -> scope.launch { container.bookmarkDao.rename(bookmark.id, name) } },
                onDelete = { bookmark -> scope.launch { container.bookmarkDao.delete(bookmark.id) } },
                onUndoDelete = { bookmark -> scope.launch { container.bookmarkDao.put(bookmark) } },
                onDismiss = { bookmarksShown = false },
            )
        }
        if (highlightsShown && !atLibrary) {
            HighlightsSheet(
                highlights = highlights,
                onOpen = { highlight ->
                    highlightsShown = false
                    val id = openTtuId
                    if (id != null) {
                        val text = bookText
                        val spot = text?.aroundPageStart(highlight.start.toInt())
                        jumper.jump(
                            id, highlight.start, highlight.total, spot?.hint.orEmpty(), spot?.match.orEmpty(), text,
                            jumpMessage(highlight.percent), backLabel,
                        )
                    }
                },
                onDelete = { highlight -> scope.launch { container.highlightDao.delete(highlight.id) } },
                onUndoDelete = { highlight -> scope.launch { container.highlightDao.put(highlight) } },
                onDismiss = { highlightsShown = false },
            )
        }
        val searchedBook = openTtuId
        if (searchShown && searchedBook != null && !atLibrary) {
            BookSearchScreen(
                loadText = { container.searchableBook(searchedBook) },
                onOpenHit = { hit, total ->
                    searchShown = false
                    jumper.jump(
                        searchedBook, hit.characters.toLong(), total.toLong(), hit.hint, hit.match, null,
                        resources.getString(R.string.jump_back_message, hit.percent), backLabel,
                    )
                },
                onClose = { searchShown = false },
            )
        }
        if (sessionShown) {
            val session by container.wordRepository.session.collectAsStateWithLifecycle(SessionWords(null, emptyList()))
            SessionSheet(session, onDismiss = { sessionShown = false })
        }
        if (reviewShown) {
            ReviewScreen(
                onClose = { reviewShown = false },
                onOpenBook = {
                    // Back from the book returns to Words and its review card.
                    reviewShown = false
                    library.open(it)
                },
            )
        }
        if (readerSettings) {
            // On top of everything; closing it reloads the reader if anything changed.
            ReaderSettingsScreen(onClose = { readerSettings = false })
        }
        SnackbarHost(jumpSnackbar, modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
    }
}

/**
 * Status and navigation bar icons dark on a light [background] and light on a dark one, while
 * there is one (a book on screen); otherwise as the app's theme set them.
 */
@Composable
private fun BarIcons(background: Color?) {
    val view = LocalView.current
    DisposableEffect(view, background) {
        val window = view.context.findActivity()?.window
        if (background == null || window == null) return@DisposableEffect onDispose {}
        val controller = WindowCompat.getInsetsController(window, view)
        val status = controller.isAppearanceLightStatusBars
        val navigation = controller.isAppearanceLightNavigationBars
        val light = background.luminance() > 0.5f
        controller.isAppearanceLightStatusBars = light
        controller.isAppearanceLightNavigationBars = light
        onDispose {
            controller.isAppearanceLightStatusBars = status
            controller.isAppearanceLightNavigationBars = navigation
        }
    }
}

/**
 * Hides the status and navigation bars while [hidden] (immersive mode, over a book); a swipe
 * from the screen edge shows them for a moment without moving the page. They come back when
 * [hidden] ends, and are hidden again after the app was in the background.
 */
@Composable
private fun ImmersiveBars(hidden: Boolean) {
    val view = LocalView.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(view, lifecycle, hidden) {
        val window = view.context.findActivity()?.window
        if (!hidden || window == null) return@DisposableEffect onDispose {}
        val controller = WindowCompat.getInsetsController(window, view)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) controller.hide(WindowInsetsCompat.Type.systemBars())
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** The room an immersive book keeps clear: the display cutout and the keyboard, never the bars. */
private val bookInsets: WindowInsets
    @Composable get() = WindowInsets.displayCutout.union(WindowInsets.ime)

/** ttu's book list, which the tabs cover. */
private const val LIBRARY_PATH = "/manage"

/** The panel leaves most of the page visible. */
