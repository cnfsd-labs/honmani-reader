package app.honmani.reader.ui.reader

import app.honmani.reader.diagnostics.logWarning
import android.app.Activity
import android.content.Context
import android.content.ActivityNotFoundException
import android.content.pm.ApplicationInfo
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import app.honmani.reader.data.settings.ReadAloudSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.net.Uri
import app.honmani.reader.readaloud.AudiobookPhase
import app.honmani.reader.readaloud.AudiobookProblem
import androidx.compose.runtime.SideEffect
import app.honmani.reader.readaloud.LiveVoice
import app.honmani.reader.readaloud.ReadAloudController
import app.honmani.reader.readaloud.ReadAloudPhase
import app.honmani.reader.readaloud.SpokenSentence
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.delay
import android.content.ContextWrapper
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsBottomHeight
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
import app.honmani.reader.data.library.PronunciationEntity
import app.honmani.reader.readaloud.PronunciationRules
import app.honmani.reader.data.library.saveReplacingOverlaps
import app.honmani.reader.data.library.undoSave
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
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
import app.honmani.reader.ui.anki.label
import app.honmani.reader.ui.anki.rememberAnkiAccess
import app.honmani.reader.ui.anki.rememberAnkiEnabled
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

/** Android's text-to-speech settings, where a Japanese voice is installed; the main settings if there are none. */
private fun openVoiceSettings(context: Context) {
    val tts = Intent("com.android.settings.TTS_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(tts)
    } catch (_: ActivityNotFoundException) {
        try {
            context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            // No settings app: nothing more to offer.
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
    val ankiEnabled = rememberAnkiEnabled()
    val container = remember { (context.applicationContext as HonmaniApplication).container }
    val dictionaryUpdates by remember { container.dictionaryUpdateChecker.updates }.collectAsStateWithLifecycle(emptyList())
    // Reading time counts while a book is on screen and the app is in front.
    val tracker = remember { container.readingTracker }
    val showPreviousSentence by container.appearanceSettings.showPreviousSentence.collectAsStateWithLifecycle()
    val sentenceEnds by container.ankiSettings.sentenceEnds.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val readAloudMessages = remember { Channel<String>(Channel.UNLIMITED) }
    val audiobookMessages = remember { Channel<String>(Channel.UNLIMITED) }
    val pageMessages = remember { merge(readAloudMessages.receiveAsFlow(), audiobookMessages.receiveAsFlow()) }
    // The open book's "read this as that" list, set below once the book is known.
    val pronunciationRules = remember { arrayOf(PronunciationRules.EMPTY) }
    val readAloud = remember {
        // The spoken text is only logged by a debug build.
        val debuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        ReadAloudController(
            voice = LiveVoice(context),
            toPage = { readAloudMessages.trySend(it) },
            initialRate = container.readAloudSettings.rate.value,
            saveRate = container.readAloudSettings::setRate,
            onSpoken = tracker::onActivity,
            log = { if (debuggable) Log.i("ReadAloud", it) },
            pronunciations = { pronunciationRules[0] },
        )
    }
    val readAloudState by readAloud.state.collectAsStateWithLifecycle()
    // The audiobook: the same bar and the same marks on the page, but the book follows a recording.
    val audiobook = remember {
        val debuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        AudiobookSession(
            context = context,
            container = container,
            scope = scope,
            toPage = { audiobookMessages.trySend(it) },
            onPlaying = tracker::onActivity,
            log = { if (debuggable) Log.i("Audiobook", it) },
        )
    }
    val audiobookState by audiobook.controller.state.collectAsStateWithLifecycle()
    // The engine, the focus and the queue go with the reader.
    DisposableEffect(readAloud) { onDispose { readAloud.close() } }
    DisposableEffect(audiobook) { onDispose { audiobook.close() } }
    // About five times a second while the audio plays, the page is told which subtitle is heard.
    LaunchedEffect(audiobookState.running) {
        while (audiobookState.running) {
            audiobook.controller.tick()
            delay(AUDIOBOOK_TICK_MS)
        }
    }
    // The screen stays on while the book is read aloud, and not a moment longer.
    val rootView = LocalView.current
    // The window flag, not View.keepScreenOn: that one is only passed on at the next frame, which
    // a stopped or still screen never draws.
    val keepScreenOn = readAloudState.running || audiobookState.running
    DisposableEffect(rootView, keepScreenOn) {
        val window = rootView.context.findActivity()?.window
        if (keepScreenOn) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                readAloud.appStopped()
                audiobook.controller.appStopped()
                // The state collection is stopped too, so the flag is cleared here, not by the effect.
                context.findActivity()?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
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
    // Listen or Read sentence was tapped in the open panel: the volume buttons change the volume
    // until the panel closes (they would close the panel otherwise).
    var panelSpeaking by remember { mutableStateOf(false) }
    // With "Count time while a lookup is open" off, the timer pauses while the panel is open
    // (its touches are native: the book sees no activity).
    val countDuringLookup by container.readingTimeSettings.countDuringLookup.collectAsStateWithLifecycle()
    LaunchedEffect(countDuringLookup) { tracker.setCountLookupTime(countDuringLookup) }
    LaunchedEffect(panel != null) {
        panelSpeaking = false
        tracker.setLookupOpen(panel != null)
        // The panel's word audio never talks over the reader's voice: it ends before that goes on.
        if (panel == null) container.wordAudio.stop()
        readAloud.panelChanged(panel != null)
        audiobook.controller.panelChanged(panel != null)
    }
    LaunchedEffect(atLibrary) {
        if (atLibrary) {
            readAloud.close()
            audiobook.close()
        }
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
    // The open sub-screen came from the reader menu: Back returns there (a tap outside or a swipe down closes to the book).
    var openedFromMenu by rememberSaveable { mutableStateOf(false) }
    // ttu's table of contents also opens from its own bar, so the menu's Contents keeps its own mark.
    var contentsFromMenu by rememberSaveable { mutableStateOf(false) }
    // The words of this reading session, from the reader's bottom menu.
    var sessionShown by rememberSaveable { mutableStateOf(false) }
    // The search in the open book, from the reader's bottom menu; ttu's id of the book.
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
    var pronunciationsShown by rememberSaveable { mutableStateOf(false) }
    val pronunciations by remember(bookHash) {
        bookHash?.let { container.pronunciationDao.observe(it) } ?: kotlinx.coroutines.flow.flowOf(emptyList())
    }.collectAsState(emptyList())
    SideEffect { pronunciationRules[0] = PronunciationRules(pronunciations.map { it.text to it.reading }) }
    val sectionsTotal by produceState<Int?>(null, openTtuId) {
        value = openTtuId?.let { id -> container.bookDao.findByTtuId(id)?.let { container.sectionStarts(it)?.total } }
    }
    LaunchedEffect(atLibrary, openTtuId) { jumpSnackbar.currentSnackbarData?.dismiss() }
    // A passage the audiobook can't reach by turning pages: the book opens there, like a jump.
    SideEffect {
        audiobook.navigate = { span ->
            val id = openTtuId
            if (id != null) {
                scope.launch {
                    val text = container.searchableBook(id)
                    val total = text?.total ?: container.bookDao.findByTtuId(id)?.let { container.sectionStarts(it)?.total } ?: return@launch
                    val spot = text?.aroundPageStart(span.start)
                    jumper.jump(
                        id, span.start.toLong(), total.toLong(), spot?.hint.orEmpty(), spot?.match.orEmpty(), text,
                        resources.getString(R.string.jump_back_message, BookPlace.percent(span.start.toLong(), total.toLong())),
                        resources.getString(R.string.jump_back_action),
                    )
                }
            }
        }
    }
    // The audiobook's files, chosen with Android's own picker: the audio first, then its subtitles.
    var audiobookStep by rememberSaveable { mutableStateOf<AudiobookStep?>(null) }
    var pickedAudio by rememberSaveable { mutableStateOf<Uri?>(null) }
    var pairingVersion by remember { mutableIntStateOf(0) }
    val audiobookPaired = remember(bookHash, pairingVersion) { bookHash?.let { container.audiobookStore.pairing(it) } != null }
    val subtitlesUnreadable = stringResource(R.string.audiobook_subtitles_unreadable)
    val audiobookRemoved = stringResource(R.string.audiobook_removed)
    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            audiobook.keepAccess(uri)
            pickedAudio = uri
            audiobookStep = AudiobookStep.SUBTITLES
        }
    }
    val subtitlePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val audio = pickedAudio
        val hash = bookHash
        val id = openTtuId
        pickedAudio = null
        if (audio != null && hash != null) {
            if (uri == null || id == null) {
                audiobook.discard(hash, audio)
            } else {
                scope.launch {
                    audiobook.close()
                    readAloud.close()
                    if (audiobook.pair(hash, audio, uri)) {
                        pairingVersion++
                        audiobook.start(id)
                    } else {
                        audiobook.discard(hash, audio)
                        jumpSnackbar.currentSnackbarData?.dismiss()
                        jumpSnackbar.showSnackbar(subtitlesUnreadable)
                    }
                }
            }
        }
    }
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

    // The room the read-aloud bar takes: the book lays itself out above it, since a bar over the
    // page would cut the end off every column of vertical text.
    val readAloudBarShown = readAloudState.phase != ReadAloudPhase.OFF && !atLibrary
    val audiobookBarShown = audiobookState.phase != AudiobookPhase.OFF && !atLibrary
    var readAloudBarHeight by remember { mutableStateOf(0.dp) }
    val bookBottom = if (readAloudBarShown || audiobookBarShown) readAloudBarHeight else 0.dp
    val density = LocalDensity.current

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
                    openedFromMenu = false
                    readerSettings = true
                },
                onOpenMenu = viewModel::openMenu,
                onContentsClosedByBack = {
                    if (contentsFromMenu) viewModel.openMenu()
                    contentsFromMenu = false
                },
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
                marksKey = learning?.let { Triple(it, dueWords, sentenceEnds) },
                sentenceEnds = sentenceEnds,
                highlightsMessage = { bookId ->
                    container.bookDao.findByTtuId(bookId)?.let { book ->
                        container.sectionStarts(book)?.let { sections ->
                            highlightsMessage(bookId, sections, container.highlightDao.observe(book.contentHash).first())
                        }
                    }
                },
                highlightsKey = highlights,
                readAloudMessages = pageMessages,
                onReadAloud = { message ->
                    when (message) {
                        is BridgeMessage.ReadAloudSentences ->
                            readAloud.onSentences(message.gen, message.items.map { SpokenSentence(it.i, it.text) }, message.end)
                        is BridgeMessage.ReadAloudLost -> readAloud.onLost(message.gen)
                        is BridgeMessage.AudiobookLost -> audiobook.controller.onLost(message.gen)
                        is BridgeMessage.FollowState -> {
                            readAloud.viewDetached(message.detached)
                            audiobook.controller.viewDetached(message.detached)
                        }
                        else -> Unit
                    }
                },
                onBookShown = {
                    readAloud.pageReloaded()
                    audiobook.controller.pageReloaded()
                },
                volumeKeysTurnPages = VolumeKeys.turnPages(appSettings.volumeKeys, readAloudBarShown || audiobookBarShown, panelSpeaking),
                hideSelectionToolbar = appSettings.selectionLookup,
                panelOpen = panel != null,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = bookBottom)
                    .consumeWindowInsets(PaddingValues(bottom = bookBottom))
                    // In immersive mode a book fills the screen, also under bars a swipe shows for
                    // a moment: its size doesn't follow them, so they never reflow the page.
                    .windowInsetsPadding(if (appSettings.immersive && !atLibrary) bookInsets else WindowInsets.safeDrawing),
            )
            // Back ends read aloud first (composed after the WebView, so it comes before its back);
            // the lookup panel's own handler is composed later still and closes the panel before this.
            BackHandler(enabled = readAloudBarShown) { readAloud.close() }
            if (readAloudBarShown) {
                val problem = readAloudState.problem
                val ended = readAloudState.phase == ReadAloudPhase.ENDED
                ReadAloudBar(
                    playing = readAloudState.running,
                    rate = readAloudState.rate,
                    onPlayPause = readAloud::togglePlay,
                    onPrevious = readAloud::previous,
                    onNext = readAloud::next,
                    onSpeed = { readAloud.setRate(ReadAloudSettings.nextRate(readAloudState.rate)) },
                    onClose = readAloud::close,
                    message = when {
                        readAloudState.phase == ReadAloudPhase.PROBLEM && problem != null ->
                            stringResource(R.string.read_aloud_problem, stringResource(problem.label()))
                        ended -> stringResource(R.string.read_aloud_ended)
                        else -> null
                    },
                    messageAction = if (readAloudState.phase == ReadAloudPhase.PROBLEM) stringResource(R.string.read_aloud_voice_settings) else null,
                    onMessageAction = { openVoiceSettings(context) },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .onSizeChanged { readAloudBarHeight = with(density) { it.height.toDp() } },
                )
            }
            if (audiobookBarShown) {
                val problem = audiobookState.problem
                val audiobookPhase = audiobookState.phase
                val needsFiles = audiobookPhase == AudiobookPhase.PROBLEM && problem != AudiobookProblem.BOOK_UNSUPPORTED
                ReadAloudBar(
                    playing = audiobookState.running,
                    rate = audiobookState.rate,
                    onPlayPause = audiobook.controller::togglePlay,
                    onPrevious = audiobook.controller::previous,
                    onNext = audiobook.controller::next,
                    onSpeed = { audiobook.controller.setRate(ReadAloudSettings.nextRate(audiobookState.rate)) },
                    onClose = audiobook::close,
                    message = when {
                        audiobookPhase == AudiobookPhase.PROBLEM && problem != null ->
                            stringResource(R.string.audiobook_problem, stringResource(problem.label()))
                        audiobookPhase == AudiobookPhase.LOADING -> stringResource(R.string.audiobook_loading)
                        audiobookPhase == AudiobookPhase.ENDED -> stringResource(R.string.audiobook_ended)
                        audiobookState.doubtful -> stringResource(R.string.audiobook_doubtful, audiobookState.matched, audiobookState.total)
                        else -> null
                    },
                    messageAction = if (needsFiles) stringResource(R.string.audiobook_change) else null,
                    onMessageAction = {
                        openedFromMenu = false
                        audiobookStep = AudiobookStep.INTRO
                    },
                    previousDescription = stringResource(R.string.audiobook_previous),
                    nextDescription = stringResource(R.string.audiobook_next),
                    closeDescription = stringResource(R.string.audiobook_close),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .onSizeChanged { readAloudBarHeight = with(density) { it.height.toDp() } },
                )
            }
            // Over the bar, not in it: the book keeps its size when the button comes and goes. Above
            // ttu's 32 px footer (page counter) too, which sits just over the bar.
            val following = readAloudBarShown && readAloudState.detached || audiobookBarShown && audiobookState.detached
            if (following) {
                FollowButton(
                    onClick = {
                        readAloud.follow()
                        audiobook.controller.follow()
                    },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 12.dp, bottom = readAloudBarHeight + FOOTER_HEIGHT + 8.dp),
                )
            }
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
                        onPick = { color, _ -> scope.launch { container.highlightDao.setColor(existing.id, color) } },
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
                val extents = tap.selected
                val hash = bookHash
                val total = sectionsTotal
                if (!tap.bySelection || extents == null || hash == null || total == null || !appSettings.selectionLookup) return null
                return HighlightControls(
                    current = null,
                    extents = extents.available.mapValues { it.value.text },
                    rememberedExtent = container.highlightSettings.extent.value,
                    onExtentChosen = { container.highlightSettings.setExtent(it.name) },
                    onPick = { color, extent ->
                        val passage = extents[extent]
                        viewModel.dismiss()
                        scope.launch {
                            val same = highlights.firstOrNull { it.start == passage.start && it.end == passage.end }
                            jumpSnackbar.currentSnackbarData?.dismiss()
                            if (same != null) {
                                container.highlightDao.setColor(same.id, color)
                                jumpSnackbar.showSnackbar(savedMessage)
                            } else {
                                val saved = container.highlightDao.saveReplacingOverlaps(
                                    HighlightEntity(
                                        contentHash = hash, start = passage.start, end = passage.end, color = color,
                                        text = passage.text, total = total.toLong(), createdAt = System.currentTimeMillis(),
                                    ),
                                )
                                val result = jumpSnackbar.showSnackbar(savedMessage, undoLabel, duration = SnackbarDuration.Long)
                                if (result == SnackbarResult.ActionPerformed) container.highlightDao.undoSave(saved)
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
                    showAnki = ankiEnabled,
                    onSetStarred = viewModel::setStarred,
                    onSetIgnored = viewModel::setIgnored,
                    onSetFurigana = viewModel::setFurigana,
                    onEditLookup = { openInDictionary(context, it) },
                    onListen = { group ->
                        panelSpeaking = true
                        viewModel.listen(group)
                    },
                    onLookupWord = viewModel::lookupWord,
                    onShowKanji = viewModel::showKanji,
                    onBack = { viewModel.back() },
                    previousSentence = if (showPreviousSentence) state.tap.previousSentence else "",
                    onReadSentence = {
                        panelSpeaking = true
                        viewModel.readSentence()
                    },
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
                    // With the tabs at the side: what a screen pins above the bar stays clear of the system's.
                    else Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
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
                                dueIds = dueToday.mapTo(HashSet()) { it.id },
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
                                onOpenReaderSettings = {
                                    openedFromMenu = false
                                    readerSettings = true
                                },
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
        // Back from a sub-screen that came from the menu: close it and show the menu again; null otherwise.
        val backToMenu = { close: () -> Unit -> if (openedFromMenu) ({ close(); viewModel.openMenu() }) else null }
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
                    openedFromMenu = true
                    bookmarksShown = true
                },
                onHighlights = {
                    viewModel.closeMenu()
                    openedFromMenu = true
                    highlightsShown = true
                },
                onContents = {
                    viewModel.closeMenu()
                    contentsFromMenu = true
                    viewModel.ttuLibrary.openContents()
                },
                onSearch = {
                    viewModel.closeMenu()
                    openedFromMenu = true
                    searchShown = true
                },
                onReadAloud = {
                    viewModel.closeMenu()
                    // One voice at a time: the recording stops when the phone starts reading.
                    audiobook.close()
                    readAloud.start()
                },
                pronunciationCount = pronunciations.size,
                onPronunciations = {
                    viewModel.closeMenu()
                    openedFromMenu = true
                    pronunciationsShown = true
                },
                audiobookPaired = audiobookPaired,
                onAudiobook = {
                    viewModel.closeMenu()
                    openedFromMenu = true
                    audiobookStep = if (audiobookPaired) AudiobookStep.MANAGE else AudiobookStep.INTRO
                },
                onSettings = {
                    viewModel.closeMenu()
                    openedFromMenu = true
                    readerSettings = true
                },
                onSessionWords = {
                    viewModel.closeMenu()
                    openedFromMenu = true
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
        audiobookStep?.let { step ->
            AudiobookDialog(
                step = step,
                onChooseAudio = {
                    audiobookStep = null
                    audioPicker.launch(arrayOf("audio/*", "application/ogg"))
                },
                onChooseSubtitles = {
                    audiobookStep = null
                    subtitlePicker.launch(arrayOf("*/*"))
                },
                onPlay = {
                    audiobookStep = null
                    val id = openTtuId
                    if (id != null) {
                        readAloud.close()
                        if (audiobookState.phase == AudiobookPhase.PAUSED || audiobookState.phase == AudiobookPhase.ENDED) {
                            audiobook.controller.play()
                        } else if (audiobookState.phase != AudiobookPhase.PLAYING) {
                            audiobook.start(id)
                        }
                    }
                },
                onChange = { audiobookStep = AudiobookStep.INTRO },
                onRemove = {
                    audiobookStep = null
                    val hash = bookHash
                    if (hash != null) {
                        scope.launch {
                            audiobook.close()
                            audiobook.remove(hash)
                            pairingVersion++
                            jumpSnackbar.currentSnackbarData?.dismiss()
                            jumpSnackbar.showSnackbar(audiobookRemoved)
                        }
                    }
                },
                onDismiss = { audiobookStep = null },
                onBack = backToMenu { audiobookStep = null },
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
                onBack = backToMenu { bookmarksShown = false },
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
                onBack = backToMenu { highlightsShown = false },
            )
        }
        if (pronunciationsShown && !atLibrary) {
            val hash = bookHash
            val corrected by produceState(emptyList<Pair<String, String>>(), pronunciationsShown) {
                value = container.wordRepository.withCorrectedFurigana().mapNotNull { PronunciationRules.fromFurigana(it.expression, it.furigana) }
            }
            PronunciationsSheet(
                items = pronunciations,
                suggestions = corrected.filter { (text, _) -> pronunciations.none { it.text == text } },
                onSave = { text, reading ->
                    if (hash != null) scope.launch { container.pronunciationDao.put(PronunciationEntity(contentHash = hash, text = text, reading = reading, createdAt = System.currentTimeMillis())) }
                },
                onDelete = { item -> scope.launch { container.pronunciationDao.delete(item.id) } },
                onUndoDelete = { item -> scope.launch { container.pronunciationDao.put(item) } },
                onDismiss = { pronunciationsShown = false },
                onBack = backToMenu { pronunciationsShown = false },
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
                onClose = { searchShown = false; if (openedFromMenu) viewModel.openMenu() },
            )
        }
        if (sessionShown) {
            val session by container.wordRepository.session.collectAsStateWithLifecycle(SessionWords(null, emptyList()))
            SessionSheet(session, onDismiss = { sessionShown = false }, onBack = backToMenu { sessionShown = false })
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
            ReaderSettingsScreen(onClose = { readerSettings = false; if (openedFromMenu) viewModel.openMenu() })
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

/** Height of ttu's page footer, in dp (CSS px), above the read-aloud bar. */
private val FOOTER_HEIGHT = 32.dp

/** ttu's book list, which the tabs cover. */
private const val LIBRARY_PATH = "/manage"

/** How often the audiobook checks where the audio is, in milliseconds. */
private const val AUDIOBOOK_TICK_MS = 200L

/** The panel leaves most of the page visible. */
