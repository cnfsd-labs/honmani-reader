package app.honmani.reader.diagnostics

import android.content.Context
import android.os.Build
import androidx.webkit.WebViewCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Collects what is needed to debug the reader on a user's device: why the app last stopped
 * ([CrashLog]), the WebView's console output and a snapshot of the last opened book's layout. Kept in memory only; the user shares it
 * explicitly.
 */
class ReaderDiagnostics {
    private val lines = ArrayDeque<String>()
    private val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT)

    /** JSON from [BOOK_SNAPSHOT_SCRIPT], taken a few seconds after a book was opened. */
    @Volatile
    var bookSnapshot: String? = null

    @Synchronized
    fun log(line: String) {
        lines.addLast("${time.format(Date())} $line")
        while (lines.size > MAX_LINES) lines.removeFirst()
    }

    @Synchronized
    fun report(context: Context): String = buildString {
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        val webView = WebViewCompat.getCurrentWebViewPackage(context)
        appendLine("Honmani ${packageInfo.versionName} (${context.packageName})")
        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("WebView: ${webView?.packageName} ${webView?.versionName}")
        appendLine()
        append(CrashLog(context).report())
        appendLine()
        appendLine("== Last opened book")
        appendLine(bookSnapshot ?: "(no book opened since the app started)")
        appendLine()
        appendLine("== Network activity (last ${NetworkLog.shared.entries().size} requests, hosts only)")
        NetworkLog.shared.entries().forEach { appendLine(it.line()) }
        appendLine()
        appendLine("== Reader console (last ${lines.size} lines)")
        lines.forEach { appendLine(it) }
    }

    companion object {
        private const val MAX_LINES = 300

        /** Delay after opening a book before taking [bookSnapshot], so layout has settled. */
        const val SNAPSHOT_DELAY_MS = 4_000L

        /**
         * Describes how the book is laid out: settings, fonts, the reading container and the
         * first paragraph, and what covers the middle of the screen. Includes the first 40
         * characters of the book so a blank page can be told from missing text.
         */
        val BOOK_SNAPSHOT_SCRIPT = """
            (() => {
              const rect = (el) => {
                if (!el) return null;
                const r = el.getBoundingClientRect();
                return [r.left, r.top, r.width, r.height].map(Math.round);
              };
              const style = (el) => {
                if (!el) return null;
                const s = getComputedStyle(el);
                return {
                  display: s.display, visibility: s.visibility, opacity: s.opacity, color: s.color,
                  fontFamily: s.fontFamily, fontSize: s.fontSize, writingMode: s.writingMode,
                  columnWidth: s.columnWidth, transform: s.transform,
                };
              };
              const content = document.querySelector('.book-content');
              const paragraph = content && content.querySelector('p');
              const center = document.elementFromPoint(innerWidth / 2, innerHeight / 2);
              const settings = {};
              for (const key of ['theme', 'fontFamilyGroupOne', 'fontFamilyGroupTwo', 'fontSize',
                'writingMode', 'viewMode', 'furiganaStyle']) {
                settings[key] = localStorage.getItem(key);
              }
              let textRect = null;
              if (paragraph && paragraph.firstChild) {
                const range = document.createRange();
                range.selectNodeContents(paragraph);
                textRect = Array.from(range.getClientRects()).slice(0, 3)
                  .map((r) => [r.left, r.top, r.width, r.height].map(Math.round));
              }
              return JSON.stringify({
                url: location.pathname + location.search,
                viewport: [innerWidth, innerHeight, devicePixelRatio],
                visualViewport: window.visualViewport &&
                  [visualViewport.width, visualViewport.height, visualViewport.scale].map((v) => Math.round(v * 100) / 100),
                documentSize: [document.documentElement.clientWidth, document.documentElement.clientHeight],
                columnSize: content && [
                  getComputedStyle(content).getPropertyValue('--book-content-child-width'),
                  getComputedStyle(content).getPropertyValue('--book-content-child-height'),
                ],
                settings,
                fonts: {
                  status: document.fonts.status,
                  loaded: Array.from(document.fonts).filter((f) => f.status === 'loaded').map((f) => f.family),
                  failed: Array.from(document.fonts).filter((f) => f.status === 'error').map((f) => f.family).length,
                },
                content: content && {
                  rect: rect(content), style: style(content), textLength: content.textContent.length,
                  paragraphs: content.querySelectorAll('p').length,
                },
                paragraph: paragraph && {
                  rect: rect(paragraph), textRects: textRect, style: style(paragraph),
                  sample: paragraph.textContent.slice(0, 40),
                },
                center: center && (center.tagName + '.' + center.className).slice(0, 120),
              });
            })()
        """.trimIndent()
    }
}
