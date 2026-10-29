package app.honmani.reader.ui.reader

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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.honmani.reader.HonmaniApplication
import app.honmani.reader.R
import app.honmani.reader.ui.HomeNavigationBar
import app.honmani.reader.ui.HomeNavigationRail
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
import app.honmani.reader.ui.words.WordsScreen
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

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
    var tab by rememberSaveable { mutableStateOf(HomeTab.LIBRARY) }
    val context = LocalContext.current
    val shareTitle = stringResource(R.string.action_share_diagnostics)
    val anki = rememberAnkiAccess()
    val container = remember { (context.applicationContext as HonmaniApplication).container }
    val dictionaryUpdates by remember { container.dictionaryUpdateChecker.updates }.collectAsStateWithLifecycle(emptyList())
    // Reading time counts while a book is on screen and the app is in front.
    val tracker = remember { container.readingTracker }
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
    LaunchedEffect(viewModel) {
        viewModel.ankiResults.collect { (expression, result) -> showAnkiResult(context, expression, result) }
    }
    LaunchedEffect(viewModel) {
        viewModel.voiceProblems.collect { showVoiceProblem(context, it) }
    }

    // While a book is open: the words being learned and how they were written, so marks follow
    // lookups (word-marks.js).
    val learning by produceState<Pair<Set<String>, Set<String>>?>(null, atLibrary) {
        value = null
        if (!atLibrary) container.wordKnowledge.map { it.learning to it.learningForms }.distinctUntilChanged().collect { value = it }
    }

    // The book's background, also behind the status and navigation bars while a book is shown.
    var bookBackground by remember { mutableStateOf<Color?>(null) }
    val frame = bookBackground.takeIf { !atLibrary && !readerSettings }
    BarIcons(background = frame)

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
                onAtLibraryChanged = {
                    atLibrary = it
                    if (it) viewModel.dismiss()
                },
                onBackground = { bookBackground = Color(it) },
                marks = { texts, mode -> container.wordMarker.mark(texts, mode) },
                marksKey = learning,
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing),
            )
            val state = panel
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
                    onListen = viewModel::listen,
                    onLookupWord = viewModel::lookupWord,
                    onShowKanji = viewModel::showKanji,
                    onBack = { viewModel.back() },
                    onOpenDictionaries = {
                        // Leave the book for the Dictionaries screen.
                        tab = HomeTab.SETTINGS
                        dictionariesShown = true
                        viewModel.ttuLibrary.show(LIBRARY_PATH)
                    },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .heightIn(max = maxHeight * PANEL_MAX_HEIGHT_FRACTION),
                )
            }
        }
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
            val onSelectTab = { selected: HomeTab ->
                tab = selected
                // A tab opens at its top level.
                dictionariesShown = false
            }
            val badges = if (dictionaryUpdates.isNotEmpty()) setOf(HomeTab.SETTINGS) else emptySet()
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                // In landscape (wider than tall) the tabs go at the side and the screens get no bottom bar.
                val atTheSide = maxWidth > maxHeight
                val bottomBar = @Composable {
                    if (!atTheSide) HomeNavigationBar(selected = tab, onSelect = onSelectTab, badges = badges)
                }
                Row(modifier = Modifier.fillMaxSize()) {
                    if (atTheSide) HomeNavigationRail(selected = tab, onSelect = onSelectTab, badges = badges)
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
                            )
                            HomeTab.WORDS -> WordsScreen(bottomBar = bottomBar)
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
        if (readerSettings) {
            // On top of everything; closing it reloads the reader if anything changed.
            ReaderSettingsScreen(onClose = { readerSettings = false })
        }
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

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** ttu's book list, which the tabs cover. */
private const val LIBRARY_PATH = "/manage"

/** The panel leaves most of the page visible. */
private const val PANEL_MAX_HEIGHT_FRACTION = 0.55f
