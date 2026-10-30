package app.honmani.reader.ui.reader

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.Uri
import android.os.Looper
import android.view.KeyEvent
import android.webkit.ConsoleMessage
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import app.honmani.reader.MainActivity
import app.honmani.reader.HonmaniApplication
import app.honmani.reader.data.words.WordMark
import app.honmani.reader.data.words.WordMarksMode
import app.honmani.reader.diagnostics.ReaderDiagnostics
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonArray
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Origin the bundled ッツ Ebook Reader is served from (WebViewAssetLoader's default domain).
 * ttu's IndexedDB library is tied to it. Must match `basePath` of `:app:buildTtu`.
 */
const val READER_ORIGIN = "https://appassets.androidplatform.net"
private const val START_URL = "$READER_ORIGIN/manage"

/** Name of the object `tap-bridge.js` posts to. */
private const val BRIDGE_OBJECT = "honmani"
private val DOCUMENT_START_SCRIPTS = listOf(
    "honmani/ttu-defaults.js",
    "honmani/tap-bridge.js",
    "honmani/keep-position.js",
    "honmani/library-bridge.js",
    "honmani/viewport-log.js",
    "honmani/help-text.js",
    "honmani/button-labels.js",
    "honmani/book-end.js",
    "honmani/book-shown.js",
    "honmani/reading-activity.js",
    "honmani/book-frame.js",
    "honmani/reader-settings.js",
    "honmani/chapter-end.js",
    "honmani/word-marks.js",
)

/**
 * How long the reader stays covered at most while a book opens (see `book-shown.js`). ttu's
 * loading circle is hidden, so a large book waits on the library's cover spinner instead.
 */
private const val UNCOVER_TIMEOUT_MS = 6_000L

/** How long ttu's settings stay covered at most while they open. */
private const val UNCOVER_SETTINGS_TIMEOUT_MS = 1_500L

private const val SETTINGS_PATH = "/settings"

/**
 * After the page reports the book shown, the reader stays covered a moment longer: the WebView
 * can put a page on screen a frame or two after the page drew it.
 */
private const val UNCOVER_SETTLE_MS = 120L

/** Whether this device's WebView supports what the reader needs (WebView 87+, from 2020). */
fun isReaderWebViewSupported(): Boolean =
    WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) &&
        WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)

/**
 * ッツ Ebook Reader in a WebView. Taps on book text arrive in [onLookup], taps on the book away
 * from text in [onDismiss]. [highlight] marks characters from the last tapped one (null for
 * none) in [highlightColor], the accent colour by default. [onBookChanged] reports ttu's id of the open book (null when none is open).
 * [onAtLibraryChanged] reports whether ttu's book list is loaded; the app covers it with
 * its own library, which drives ttu through [ttuLibrary] and has its files served from
 * [booksDir]. Back from ttu's settings or statistics returns to where they were opened, back
 * from a book to the book list (see [ReaderBack]); on the book list, back goes to the caller.
 *
 * Callers must check [isReaderWebViewSupported] first.
 */
@Composable
fun ReaderWebView(
    onLookup: (TapLookup) -> Unit,
    onDismiss: () -> Unit,
    highlight: StateFlow<Highlight?>,
    diagnostics: ReaderDiagnostics,
    ttuLibrary: TtuLibrary,
    booksDir: File,
    onAtLibraryChanged: (Boolean) -> Unit,
    onBookChanged: (ttuId: Long?) -> Unit,
    modifier: Modifier = Modifier,
    /** The reader touched or scrolled the book (for reading time). */
    onActivity: () -> Unit = {},
    /**
     * A page turn in book `ttuId` (ttu's id): characters read up to the new position, and where
     * it is in the book (0 to 1; null if ttu didn't say).
     */
    onPage: (ttuId: Long, characters: Long, progress: Float?) -> Unit = { _, _, _ -> },
    /** The Settings entry in the reader's top bar: Honmani's reader settings, not ttu's page. */
    onOpenSettings: () -> Unit = {},
    /** The book page's background colour (ARGB) changed, for the bars around the reader. */
    onBackground: (Int) -> Unit = {},
    highlightColor: Color = MaterialTheme.colorScheme.primary,
    /** The words to mark in each paragraph (`word-marks.js`); null: none can be found. */
    marks: suspend (texts: List<String>, mode: WordMarksMode, isStale: () -> Boolean) -> List<List<WordMark>>? =
        { _, _, _ -> null },
    /** Changes when the reader's words change, so the page asks for its marks again. */
    marksKey: Any? = null,
    /** The volume buttons turn pages while a book is open (they close the lookup panel first). */
    volumeKeysTurnPages: Boolean = false,
) {
    val currentOnLookup by rememberUpdatedState(onLookup)
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    val currentOnAtLibraryChanged by rememberUpdatedState(onAtLibraryChanged)
    val currentOnBookChanged by rememberUpdatedState(onBookChanged)
    val currentOnActivity by rememberUpdatedState(onActivity)
    val currentOnPage by rememberUpdatedState(onPage)
    val currentOnOpenSettings by rememberUpdatedState(onOpenSettings)
    val currentOnBackground by rememberUpdatedState(onBackground)
    val currentMarks by rememberUpdatedState(marks)
    val currentHighlightColor by rememberUpdatedState(highlightColor)
    val webViewRef = remember { WebViewRef() }
    var atLibrary by remember { mutableStateOf(true) }
    var inBook by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // While a book opens, the caller keeps covering the reader until the book is on screen (or
    // a little while has passed), so ttu's blank page and spinner don't flash up.
    val uncover = remember { arrayOfNulls<Job>(1) }
    fun uncoverAfter(delayMs: Long) {
        uncover[0]?.cancel()
        uncover[0] = scope.launch {
            delay(delayMs)
            uncover[0] = null
            if (!atLibrary) currentOnAtLibraryChanged(false)
        }
    }

    // ttu's <input type="file"> (book import, backups, fonts) goes through the system picker.
    var pendingFileCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    var pendingAllowsMultiple by remember { mutableStateOf(false) }
    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        val selected = if (pendingAllowsMultiple) uris else uris.take(1)
        pendingFileCallback?.onReceiveValue(selected.toTypedArray())
        pendingFileCallback = null
    }

    BackHandler(enabled = !atLibrary) { webViewRef.view?.let(::goBack) }

    // ttu turns pages on PageDown and PageUp in every reading mode and direction.
    val activity = LocalActivity.current as? MainActivity
    DisposableEffect(activity, volumeKeysTurnPages && inBook) {
        if (activity != null && volumeKeysTurnPages && inBook) {
            activity.volumePageTurn = { next ->
                webViewRef.view?.let { view ->
                    currentOnDismiss()
                    turnPage(view, next)
                }
            }
        }
        onDispose { activity?.volumePageTurn = null }
    }

    // ttu's settings use the app's accent colour for selected options.
    val accent = MaterialTheme.colorScheme.primary
    val onAccent = MaterialTheme.colorScheme.onPrimary
    LaunchedEffect(accent, onAccent) {
        ttuLibrary.setAccent(accent.toHex(), onAccent.toHex())
    }

    // Collected rather than passed as a value: a change reaches the page at once, not after the
    // composition that also builds the lookup panel.
    LaunchedEffect(highlight, highlightColor) {
        highlight.collect { shown ->
            val message = if (shown != null) {
                val color = highlightColor.toHex()
                """{"type":"highlight","length":${shown.length},"color":"$color","tap":${shown.tapNumber}}"""
            } else {
                """{"type":"clear"}"""
            }
            // Replies go to the page that sent the last message; nothing to clear before that.
            if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
                webViewRef.replyProxy?.postMessage(message)
            }
        }
    }

    // Asked for again after the reader's words changed (a lookup, say), not when first known.
    var lastMarksKey by remember { mutableStateOf<Any?>(null) }
    // Bumped at every reset: requests asked before it are skipped, the page has forgotten them.
    val marksGeneration = remember { AtomicInteger() }
    LaunchedEffect(marksKey) {
        val changed = lastMarksKey != null && marksKey != null
        if (marksKey != null) lastMarksKey = marksKey
        if (changed) marksGeneration.incrementAndGet()
        if (changed && WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            webViewRef.replyProxy?.postMessage("""{"type":"marks.reset"}""")
        }
    }

    // A new WebView after the page's renderer died (crashed, or killed by Android for memory):
    // the old one cannot be used again, and without this Android would close the whole app.
    var renderer by remember { mutableIntStateOf(0) }

    key(renderer) {
        AndroidView(
            modifier = modifier,
            factory = { context ->
                createReaderWebView(
                    context = context,
                    onRenderProcessGone = { renderer++ },
                    onMessage = { message, replyProxy ->
                        webViewRef.replyProxy = replyProxy
                        when (message) {
                            is BridgeMessage.Lookup -> currentOnLookup(message.tap)
                            BridgeMessage.Dismiss -> currentOnDismiss()
                            is BridgeMessage.Library -> ttuLibrary.onMessage(message.message)
                            is BridgeMessage.BookShown, BridgeMessage.SettingsShown ->
                                if (uncover[0] != null) uncoverAfter(UNCOVER_SETTLE_MS)
                            BridgeMessage.Activity -> currentOnActivity()
                            is BridgeMessage.Position -> currentOnPage(message.id, message.characters, message.progress)
                            BridgeMessage.OpenSettings -> currentOnOpenSettings()
                            is BridgeMessage.Background -> currentOnBackground(message.color)
                            is BridgeMessage.Marks -> {
                                val generation = marksGeneration.get()
                                scope.launch {
                                    val stale = { marksGeneration.get() != generation }
                                    // A stale request needs no answer: the reset made the page forget it.
                                    // Otherwise null (no dictionary) still answers, with no marks, so the
                                    // page drops its pending entry.
                                    val found = currentMarks(message.blocks.map { it.text }, message.mode, stale)
                                    if (stale()) return@launch
                                    val reply = marksReply(message, found.orEmpty(), currentHighlightColor.toHex())
                                    if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
                                        replyProxy.postMessage(reply)
                                    }
                                }
                            }
                        }
                    },
                    diagnostics = diagnostics,
                    ttuLibrary = ttuLibrary,
                    booksDir = booksDir,
                    onPathChanged = { path, bookId ->
                        val wasAtLibrary = atLibrary
                        atLibrary = path in ReaderBack.LIBRARY_PATHS
                        inBook = bookId != null
                        when {
                            wasAtLibrary && !atLibrary && bookId != null -> uncoverAfter(UNCOVER_TIMEOUT_MS)
                            // ttu's settings likewise stay covered until drawn, or the book list shows.
                            wasAtLibrary && path == SETTINGS_PATH -> uncoverAfter(UNCOVER_SETTINGS_TIMEOUT_MS)
                            // Another report for the page still opening: stays covered until shown.
                            uncover[0] != null && (bookId != null || path == SETTINGS_PATH) -> Unit
                            else -> {
                                uncover[0]?.cancel()
                                uncover[0] = null
                                currentOnAtLibraryChanged(atLibrary)
                            }
                        }
                        currentOnBookChanged(bookId)
                        if (atLibrary) ttuLibrary.requestProgress()
                    },
                    onShowFileChooser = { callback, allowsMultiple ->
                        // The WebView never opens a second chooser before answering the first.
                        pendingFileCallback?.onReceiveValue(null)
                        pendingFileCallback = callback
                        pendingAllowsMultiple = allowsMultiple
                        // ttu's accept lists mix extensions and MIME types, and file managers often
                        // report EPUBs as octet-stream, so show everything; ttu validates the file.
                        filePicker.launch(arrayOf("*/*"))
                    },
                ).also { webViewRef.view = it }
            },
            onRelease = { view ->
                ttuLibrary.detach()
                pendingFileCallback?.onReceiveValue(null)
                pendingFileCallback = null
                view.destroy()
                // After a renderer loss the new WebView may already be in place.
                if (webViewRef.view === view) {
                    webViewRef.view = null
                    webViewRef.replyProxy = null
                }
            },
        )
    }
}

/** The answer to a [BridgeMessage.Marks] request, as `word-marks.js` reads it. */
private fun marksReply(request: BridgeMessage.Marks, marks: List<List<WordMark>>, color: String): String =
    buildJsonObject {
        put("type", "marks")
        put("requestId", request.requestId)
        put("color", color)
        putJsonArray("blocks") {
            request.blocks.zip(marks) { block, found ->
                addJsonObject {
                    put("id", block.id)
                    putJsonArray("marks") {
                        for (mark in found) {
                            addJsonArray {
                                add(mark.start)
                                add(mark.length)
                                add(mark.kind.ordinal)
                            }
                        }
                    }
                }
            }
        }
    }.toString()

/** `#RRGGBB`, as CSS takes it. */
private fun Color.toHex(): String = "#%06X".format(toArgb() and 0xFFFFFF)

/** Marks [length] characters from the tap of lookup [lookupId], which makes each mark distinct. */
data class Highlight(val lookupId: Long, val length: Int, val tapNumber: Int = 0)

private class WebViewRef {
    var view: WebView? = null

    /** Channel back to the page that sent the last bridge message. */
    var replyProxy: JavaScriptReplyProxy? = null
}

@SuppressLint("SetJavaScriptEnabled")
private fun createReaderWebView(
    context: Context,
    onMessage: (BridgeMessage, JavaScriptReplyProxy) -> Unit,
    diagnostics: ReaderDiagnostics,
    ttuLibrary: TtuLibrary,
    booksDir: File,
    onPathChanged: (path: String, ttuBookId: Long?) -> Unit,
    onShowFileChooser: (ValueCallback<Array<Uri>>, allowsMultiple: Boolean) -> Unit,
    onRenderProcessGone: () -> Unit,
): WebView {
    val debuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    WebView.setWebContentsDebuggingEnabled(debuggable)

    // Handlers are tried in order; ttu's handles everything else.
    val assetLoader = WebViewAssetLoader.Builder()
        .addPathHandler(TtuLibrary.BOOK_FILES_PATH, BookPathHandler(booksDir))
        .addPathHandler(
            TtuLibrary.FONT_FILES_PATH,
            FontPathHandler((context.applicationContext as HonmaniApplication).container.fontsDir),
        )
        .addPathHandler("/", TtuPathHandler(context.assets))
        .build()

    return WebView(context).apply {
        // Behind the page before ttu paints it: black in dark mode (ttu's black theme), not white.
        val night = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
        setBackgroundColor(if (night) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = false
        // The reader is local: everything on its origin comes from the APK (intercepted
        // requests are not network loads), and nothing else may load.
        settings.blockNetworkLoads = true

        webViewClient = ReaderWebViewClient(assetLoader, diagnostics, ttuLibrary, onPathChanged, onRenderProcessGone)
        webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                diagnostics.log(
                    "${message.messageLevel()} ${message.sourceId().substringAfterLast('/')}:" +
                        "${message.lineNumber()} ${message.message()}",
                )
                return true
            }

            override fun onShowFileChooser(
                webView: WebView,
                filePathCallback: ValueCallback<Array<Uri>>,
                fileChooserParams: WebChromeClient.FileChooserParams,
            ): Boolean {
                val mode = fileChooserParams.mode
                if (mode == WebChromeClient.FileChooserParams.MODE_SAVE) return false
                onShowFileChooser(
                    filePathCallback,
                    mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE,
                )
                return true
            }
        }

        // Callers check isReaderWebViewSupported(); the local checks keep lint's
        // RequiresFeature check satisfied.
        val allowedOrigins = setOf(READER_ORIGIN)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            for (script in DOCUMENT_START_SCRIPTS) {
                val source = context.assets.open(script).bufferedReader().use { it.readText() }
                WebViewCompat.addDocumentStartJavaScript(this, source, allowedOrigins)
            }
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(
                this,
                BRIDGE_OBJECT,
                allowedOrigins,
            ) { _, message, _, isMainFrame, replyProxy ->
                if (isMainFrame) BridgeMessage.parse(message.data)?.let { onMessage(it, replyProxy) }
            }
        }

        loadUrl(START_URL)
    }
}

/** Handles back anywhere but on ttu's book list; see [ReaderBack.from]. */
/** Sends PageDown (next) or PageUp to the page, as a keyboard would. */
private fun turnPage(view: WebView, next: Boolean) {
    val code = if (next) KeyEvent.KEYCODE_PAGE_DOWN else KeyEvent.KEYCODE_PAGE_UP
    view.requestFocus()
    view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
    view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
}

private fun goBack(view: WebView) {
    val history = view.copyBackForwardList()
    val paths = (0 until history.size).map { history.getItemAtIndex(it).url.toUri().path.orEmpty() }
    when (val back = ReaderBack.from(paths, history.currentIndex)) {
        is ReaderBack.Step -> {
            // Asked of the page, not of the WebView: Chromium's back skips entries a page added
            // without a tap inside it, and books open from the native library (no tap in the
            // page), so WebView.goBackOrForward did nothing for the first book after start.
            val client = view.webViewClient as? ReaderWebViewClient
            val routes = client?.routeChanges
            view.evaluateJavascript("history.go(${back.steps});", null)
            view.postDelayed({
                // The page did not move (busy or broken): reload the book list instead.
                if (view.isAttachedToWindow && client != null && client.routeChanges == routes) {
                    view.loadUrl(START_URL)
                }
            }, BACK_FALLBACK_MS)
        }
        ReaderBack.LoadLibrary -> view.loadUrl(START_URL)
    }
}

/** How long a history step from [goBack] may take before the book list is loaded instead. */
private const val BACK_FALLBACK_MS = 1_500L

private class ReaderWebViewClient(
    private val assetLoader: WebViewAssetLoader,
    private val diagnostics: ReaderDiagnostics,
    private val ttuLibrary: TtuLibrary,
    private val onPathChanged: (path: String, ttuBookId: Long?) -> Unit,
    private val onRenderProcessGone: () -> Unit,
) : WebViewClient() {
    /** Counts route changes, so [goBack] can tell whether a history step happened. */
    var routeChanges = 0
        private set

    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
        ttuLibrary.detach()
    }

    override fun onPageFinished(view: WebView, url: String?) {
        ttuLibrary.attach(
            object : TtuPage {
                override fun evaluate(script: String) = onMainThread { view.evaluateJavascript(script, null) }

                override fun load(path: String) = onMainThread { view.loadUrl(READER_ORIGIN + path) }

                // WebView throws (and the app closes) when called from another thread.
                private fun onMainThread(action: () -> Unit) {
                    if (Looper.myLooper() == Looper.getMainLooper()) action() else view.post(action)
                }
            },
        )
        if (url?.toUri()?.path.orEmpty() in ReaderBack.LIBRARY_PATHS) ttuLibrary.requestProgress()
    }

    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest,
    ): WebResourceResponse? = assetLoader.shouldInterceptRequest(request.url)

    /**
     * Links that leave the reader origin open in the user's browser, only when tapped: a page
     * leaving on its own (a script in a book) could otherwise carry data out in the address.
     */
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val url = request.url
        if (url.scheme == "https" && url.host == WebViewAssetLoader.DEFAULT_DOMAIN) return false
        if (!request.hasGesture()) {
            diagnostics.log("blocked a navigation without a tap to ${url.scheme}://${url.host}")
            return true
        }
        if (url.scheme == "http" || url.scheme == "https") {
            try {
                view.context.startActivity(
                    Intent(Intent.ACTION_VIEW, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            } catch (_: ActivityNotFoundException) {
                // No browser installed; ignore the link.
            }
        }
        return true
    }

    /** The page's renderer died: the reader starts again on a new WebView, at the book list. */
    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        diagnostics.log("renderer gone (crashed: ${detail.didCrash()})")
        onRenderProcessGone()
        return true
    }

    /** ttu is a single-page app; this runs whenever it changes route. */
    override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
        routeChanges++
        val uri = url?.toUri()
        val path = uri?.path.orEmpty()
        diagnostics.log("route $path")
        val bookId = if (path == TtuLibrary.BOOK_PATH) uri?.getQueryParameter("id")?.toLongOrNull() else null
        onPathChanged(path, bookId)
        if (path == TtuLibrary.BOOK_PATH) {
            view.postDelayed({ snapshotBook(view) }, ReaderDiagnostics.SNAPSHOT_DELAY_MS)
        }
    }

    private fun snapshotBook(view: WebView) {
        // The reader may have been closed (and the WebView destroyed) in the meantime.
        if (!view.isAttachedToWindow || view.url?.toUri()?.path != TtuLibrary.BOOK_PATH) return
        view.evaluateJavascript(ReaderDiagnostics.BOOK_SNAPSHOT_SCRIPT) { result ->
            // evaluateJavascript returns the value as JSON; the script returns a string.
            diagnostics.bookSnapshot = runCatching {
                (Json.parseToJsonElement(result) as JsonPrimitive).content
            }.getOrDefault(result)
        }
    }
}
