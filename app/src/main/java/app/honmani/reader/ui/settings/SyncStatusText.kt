package app.honmani.reader.ui.settings

import android.text.format.DateUtils
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.honmani.reader.R
import app.honmani.reader.data.sync.SyncFailure

/** A short human reason for [failure] ("server unreachable"), lower case, for use in a sentence. */
@Composable
fun syncFailureReason(failure: SyncFailure): String = when (failure.reason) {
    SyncFailure.Kind.UNAUTHORIZED -> stringResource(R.string.sync_fail_unauthorized)
    SyncFailure.Kind.NETWORK ->
        failure.status?.let { stringResource(R.string.sync_fail_network_http, it) } ?: stringResource(R.string.sync_fail_network)
    SyncFailure.Kind.SERVER ->
        failure.status?.let { stringResource(R.string.sync_fail_server_http, it) } ?: stringResource(R.string.sync_fail_server)
    SyncFailure.Kind.HOTSPOT -> stringResource(R.string.sync_fail_hotspot)
    SyncFailure.Kind.OTHER -> stringResource(R.string.sync_fail_other)
}

/** "2 minutes ago" (Android says "0 minutes ago" for the first minute). */
@Composable
fun syncAgo(at: Long, now: Long = System.currentTimeMillis()): String =
    if (now - at < DateUtils.MINUTE_IN_MILLIS) {
        stringResource(R.string.words_just_now)
    } else {
        DateUtils.getRelativeTimeSpanString(at, now, DateUtils.MINUTE_IN_MILLIS).toString()
    }
