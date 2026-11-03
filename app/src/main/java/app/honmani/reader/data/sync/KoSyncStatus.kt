package app.honmani.reader.data.sync

import android.content.SharedPreferences
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Why the last sync attempt failed; [status] is the server's HTTP status when it gave one. */
data class SyncFailure(
    val reason: Kind,
    val status: Int?,
    /** The book (ttu id) the attempt was for; null when it was not for one book. */
    val ttuId: Long?,
    /** Milliseconds since 1970. */
    val at: Long,
) {
    enum class Kind { UNAUTHORIZED, NETWORK, SERVER, HOTSPOT, OTHER }
}

/** What the sync did last: when it last worked, and the last attempt that failed (cleared by a success). */
data class SyncState(val lastSuccessAt: Long?, val failure: SyncFailure?) {
    /** The failure to show in a book: one for that book, or one that was not for a single book. */
    fun failureFor(ttuId: Long?): SyncFailure? = failure?.takeIf { it.ttuId == null || it.ttuId == ttuId }
}

/**
 * Progress sync status in the `device` preferences (never backed up: it describes this phone's
 * connection). Only the last success and the last failure are kept; a success clears the failure.
 */
class KoSyncStatus(
    private val preferences: SharedPreferences,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val _state = MutableStateFlow(load())
    val state: StateFlow<SyncState> = _state.asStateFlow()

    fun succeeded() {
        val at = now()
        preferences.edit().putLong(KEY_OK_AT, at).clearFailure().apply()
        _state.value = SyncState(at, null)
    }

    /** [ttuId]: the book the attempt was for, if one. */
    fun failed(error: IOException, ttuId: Long? = null) {
        val kex = error as? KoSyncException
        val kind = when (kex?.reason) {
            KoSyncException.Reason.UNAUTHORIZED -> SyncFailure.Kind.UNAUTHORIZED
            KoSyncException.Reason.NETWORK -> SyncFailure.Kind.NETWORK
            KoSyncException.Reason.SERVER -> SyncFailure.Kind.SERVER
            KoSyncException.Reason.HOTSPOT -> SyncFailure.Kind.HOTSPOT
            null -> SyncFailure.Kind.OTHER
        }
        val failure = SyncFailure(kind, kex?.status, ttuId, now())
        preferences.edit()
            .putString(KEY_ERR_KIND, kind.name)
            .putInt(KEY_ERR_STATUS, failure.status ?: NO_STATUS)
            .putLong(KEY_ERR_TTU, ttuId ?: NO_BOOK)
            .putLong(KEY_ERR_AT, failure.at)
            .apply()
        _state.value = _state.value.copy(failure = failure)
    }

    /** Signed out or into another account: the old account's history means nothing. */
    fun clear() {
        preferences.edit().remove(KEY_OK_AT).clearFailure().apply()
        _state.value = SyncState(null, null)
    }

    private fun SharedPreferences.Editor.clearFailure() =
        remove(KEY_ERR_KIND).remove(KEY_ERR_STATUS).remove(KEY_ERR_TTU).remove(KEY_ERR_AT)

    private fun load(): SyncState {
        val ok = preferences.getLong(KEY_OK_AT, 0).takeIf { it > 0 }
        val kind = preferences.getString(KEY_ERR_KIND, null)?.let { runCatching { SyncFailure.Kind.valueOf(it) }.getOrNull() }
        val failure = kind?.let {
            SyncFailure(
                reason = it,
                status = preferences.getInt(KEY_ERR_STATUS, NO_STATUS).takeIf { s -> s != NO_STATUS },
                ttuId = preferences.getLong(KEY_ERR_TTU, NO_BOOK).takeIf { id -> id != NO_BOOK },
                at = preferences.getLong(KEY_ERR_AT, 0),
            )
        }
        return SyncState(ok, failure)
    }

    private companion object {
        const val KEY_OK_AT = "kosync_status_ok_at"
        const val KEY_ERR_KIND = "kosync_status_err_kind"
        const val KEY_ERR_STATUS = "kosync_status_err_http"
        const val KEY_ERR_TTU = "kosync_status_err_book"
        const val KEY_ERR_AT = "kosync_status_err_at"
        const val NO_STATUS = -1
        const val NO_BOOK = Long.MIN_VALUE
    }
}
