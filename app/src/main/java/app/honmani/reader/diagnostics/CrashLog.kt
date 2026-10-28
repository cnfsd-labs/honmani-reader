package app.honmani.reader.diagnostics

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Keeps why the app last stopped, for "Share diagnostics": the stack trace of the last crash
 * (Android keeps none the app can read) and, from Android 11, the system's record of recent
 * exits (not responding, killed for memory, native crashes). Stays on the phone; the reader
 * shares it or not.
 */
class CrashLog(private val context: Context, private val file: File = File(context.filesDir, FILE_NAME)) {

    /** Saves the stack trace of any uncaught exception, then lets Android crash the app as before. */
    fun install() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { record(thread.name, error) }
            previous?.uncaughtException(thread, error)
        }
    }

    fun record(threadName: String, error: Throwable, time: Long = System.currentTimeMillis()) {
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        file.writeText(
            "${format(time)}, version ${versionName()}, thread $threadName\n" +
                trace.take(MAX_TRACE_CHARS),
        )
    }

    fun report(): String = buildString {
        appendLine("== Last crash")
        appendLine(file.takeIf { it.exists() }?.readText()?.trimEnd() ?: "(none recorded)")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            appendLine()
            appendLine("== Recent exits (Android's record)")
            val exits = runCatching { recentExits() }.getOrElse { listOf("(not available: ${it.message})") }
            if (exits.isEmpty()) appendLine("(none)") else exits.forEach { appendLine(it) }
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun recentExits(): List<String> {
        val manager = context.getSystemService(ActivityManager::class.java)
        return manager.getHistoricalProcessExitReasons(context.packageName, 0, MAX_EXITS * 4)
            // WebView ends its renderer whenever no page needs it: only its failures matter.
            .filterNot { isRenderer(it.processName) && it.reason in ROUTINE_RENDERER_EXITS }
            .take(MAX_EXITS)
            .map { exit ->
                buildString {
                    append("${format(exit.timestamp)} ${processName(exit.processName)}: ${reasonName(exit.reason)}")
                    if (exit.status != 0) append(" (status ${exit.status})")
                    append(", ${importanceName(exit.importance)}")
                    if (exit.pss > 0) append(", ${exit.pss / 1024} MB")
                    exit.description?.let { append(": $it") }
                    if (exit.reason == ApplicationExitInfo.REASON_ANR) {
                        // The trace holds every thread; the first lines are the main thread's.
                        exit.traceInputStream?.bufferedReader()?.use { reader ->
                            reader.lineSequence().take(ANR_TRACE_LINES).forEach { append("\n    $it") }
                        }
                    }
                }
            }
    }

    /** "app", or the WebView's renderer ("com.google.android.webview:sandboxed_process0:…"). */
    private fun processName(name: String?): String = when {
        name == null || name == context.packageName -> "app"
        name.startsWith(context.packageName + ":") -> name.removePrefix(context.packageName)
        isRenderer(name) -> "page renderer"
        else -> name
    }

    private fun isRenderer(name: String?) = name?.contains("sandboxed_process") == true

    private fun versionName(): String? =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()

    private fun format(time: Long) = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date(time))

    companion object {
        const val FILE_NAME = "last-crash.txt"
        private const val MAX_TRACE_CHARS = 8_000
        private const val MAX_EXITS = 5
        private const val ANR_TRACE_LINES = 40
        private val ROUTINE_RENDERER_EXITS = setOf(
            ApplicationExitInfo.REASON_EXIT_SELF,
            ApplicationExitInfo.REASON_OTHER,
            ApplicationExitInfo.REASON_DEPENDENCY_DIED,
        )

        @RequiresApi(Build.VERSION_CODES.R)
        fun reasonName(reason: Int): String = when (reason) {
            ApplicationExitInfo.REASON_EXIT_SELF -> "exited"
            ApplicationExitInfo.REASON_SIGNALED -> "signalled"
            ApplicationExitInfo.REASON_LOW_MEMORY -> "low memory"
            ApplicationExitInfo.REASON_CRASH -> "crash"
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "native crash"
            ApplicationExitInfo.REASON_ANR -> "not responding"
            ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "initialization failure"
            ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "permission change"
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "excessive resource usage"
            ApplicationExitInfo.REASON_USER_REQUESTED -> "stopped by the user"
            ApplicationExitInfo.REASON_USER_STOPPED -> "user stopped"
            ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "dependency died"
            ApplicationExitInfo.REASON_OTHER -> "other"
            else -> "reason $reason"
        }

        private fun importanceName(importance: Int): String = when {
            importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND -> "in front"
            importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE -> "visible"
            else -> "in the background"
        }
    }
}
