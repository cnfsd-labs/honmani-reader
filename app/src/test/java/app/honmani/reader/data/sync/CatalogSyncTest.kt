package app.honmani.reader.data.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.honmani.reader.data.opds.CredentialCipher
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CatalogSyncTest {
    private val server = FakeKoSyncServer(cwa = true)
    private val settings = KoSyncSettings(
        ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("catalog-sync", Context.MODE_PRIVATE),
        object : CredentialCipher {
            override fun encrypt(plain: String) = plain

            override fun decrypt(encrypted: String) = encrypted
        },
    )
    private val sync = CatalogSync(settings, KoSyncClient(connectTimeoutMs = 2_000, readTimeoutMs = 2_000))
    private val catalog get() = server.url + "/opds"

    @After
    fun tearDown() {
        settings.signOut()
        server.stop()
    }

    @Test
    fun theCatalogueLoginTurnsOnSyncOnItsServer() = runBlocking {
        sync.turnOn(catalog, "reader", "secret")

        val account = settings.account.value!!
        assertEquals(server.url + "/kosync", account.server)
        assertEquals("secret", account.password)
        assertTrue(sync.isOn(catalog, "reader"))
        assertTrue(sync.isOn(server.url + "/opds/new?page=2", "reader"))
        assertFalse(sync.isOn(catalog, "someone else"))
        assertNull(sync.otherServer(catalog, "reader"))
    }

    @Test
    fun syncOnAnotherServerIsNamed_andOnlyThisServersIsTurnedOff() = runBlocking {
        settings.signIn(KoSyncAccount("https://sync.koreader.rocks", "reader", "key"))

        assertEquals("https://sync.koreader.rocks", sync.otherServer(catalog, "reader"))
        sync.turnOff(catalog, "reader")
        assertEquals("https://sync.koreader.rocks", settings.account.value?.server)

        sync.turnOn(catalog, "reader", "secret")
        sync.turnOff(catalog, "reader")
        assertNull(settings.account.value)
    }

    @Test
    fun aWrongPasswordLeavesSyncAsItWas() = runBlocking {
        try {
            sync.turnOn(catalog, "reader", "wrong")
            fail("Expected a refused login")
        } catch (e: KoSyncException) {
            assertEquals(KoSyncException.Reason.UNAUTHORIZED, e.reason)
        }
        assertNull(settings.account.value)
    }

    @Test
    fun theSyncServerIsTheCataloguesOrigin() {
        assertEquals("http://nas:8083", CatalogSync.origin("http://NAS:8083/opds"))
        assertEquals("https://books.example", CatalogSync.origin(" https://books.example/opds/ "))
    }

    @Test
    fun aDefaultPortSpelledOutIsTheSameServer() {
        settings.signIn(KoSyncAccount("http://nas/kosync", "reader", "key"))
        assertTrue(sync.isOn("http://nas:80/opds", "reader"))
        settings.signIn(KoSyncAccount("https://nas:443/kosync", "reader", "key"))
        assertTrue(sync.isOn("https://NAS/opds", "reader"))
        assertFalse(sync.isOn("http://nas/opds", "reader"))
    }
}
