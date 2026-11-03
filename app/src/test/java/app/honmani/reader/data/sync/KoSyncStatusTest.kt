package app.honmani.reader.data.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class KoSyncStatusTest {
    private val preferences = ApplicationProvider.getApplicationContext<Context>()
        .getSharedPreferences("status-test", Context.MODE_PRIVATE)
    private var clock = 1_000L
    private val status = KoSyncStatus(preferences) { clock }

    @Test
    fun startsEmpty() {
        assertEquals(SyncState(null, null), status.state.value)
    }

    @Test
    fun aFailureKeepsTheReasonStatusBookAndTime() {
        status.failed(KoSyncException(KoSyncException.Reason.SERVER, "The server answered 502", status = 502), ttuId = 7)

        assertEquals(SyncFailure(SyncFailure.Kind.SERVER, 502, 7, 1_000), status.state.value.failure)
    }

    @Test
    fun otherErrorsAreOther_andNetworkErrorsAreNetwork() {
        status.failed(IOException("disk"))
        assertEquals(SyncFailure.Kind.OTHER, status.state.value.failure!!.reason)
        assertNull(status.state.value.failure!!.ttuId)

        status.failed(KoSyncException(KoSyncException.Reason.NETWORK, "refused"))
        assertEquals(SyncFailure.Kind.NETWORK, status.state.value.failure!!.reason)
        status.failed(KoSyncException(KoSyncException.Reason.UNAUTHORIZED, "Wrong login"))
        assertEquals(SyncFailure.Kind.UNAUTHORIZED, status.state.value.failure!!.reason)
    }

    @Test
    fun aSuccessClearsTheFailureAndKeepsItsTime() {
        status.failed(KoSyncException(KoSyncException.Reason.NETWORK, "refused"), ttuId = 7)
        clock = 5_000
        status.succeeded()

        assertEquals(SyncState(lastSuccessAt = 5_000, failure = null), status.state.value)
    }

    @Test
    fun aFailureKeepsTheLastSuccess() {
        status.succeeded()
        clock = 9_000
        status.failed(KoSyncException(KoSyncException.Reason.NETWORK, "refused"))

        assertEquals(1_000L, status.state.value.lastSuccessAt)
        assertEquals(9_000L, status.state.value.failure!!.at)
    }

    @Test
    fun theStateSurvivesARestart() {
        status.succeeded()
        clock = 2_000
        status.failed(KoSyncException(KoSyncException.Reason.NETWORK, "x", status = 404), ttuId = 3)

        assertEquals(status.state.value, KoSyncStatus(preferences).state.value)
        assertEquals(404, KoSyncStatus(preferences).state.value.failure!!.status)
    }

    @Test
    fun clearForgetsEverything() {
        status.succeeded()
        status.failed(IOException("x"), 1)
        status.clear()

        assertEquals(SyncState(null, null), status.state.value)
        assertEquals(SyncState(null, null), KoSyncStatus(preferences).state.value)
    }

    @Test
    fun aBookSeesItsOwnFailuresAndThoseNotForOneBook() {
        status.failed(IOException("x"), ttuId = 3)
        assertEquals(3L, status.state.value.failureFor(3)!!.ttuId)
        assertNull(status.state.value.failureFor(4))
        assertNull(status.state.value.failureFor(null))

        status.failed(IOException("x"), ttuId = null)
        assertEquals(SyncFailure.Kind.OTHER, status.state.value.failureFor(4)!!.reason)
    }
}
