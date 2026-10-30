package app.honmani.reader.diagnostics

import android.util.Log

/**
 * A warning in logcat for a failure that is handled (a fallback is used) but should not pass
 * unseen. Plain JVM unit tests have no `Log`; there it does nothing.
 */
fun logWarning(tag: String, message: String, error: Throwable? = null) {
    try {
        Log.w(tag, message, error)
    } catch (_: RuntimeException) {
        // "Method w in android.util.Log not mocked" on the JVM.
    }
}
