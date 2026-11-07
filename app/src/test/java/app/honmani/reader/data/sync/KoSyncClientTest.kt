package app.honmani.reader.data.sync

import app.honmani.reader.data.net.LoginGate
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class KoSyncClientTest {
    private val server = FakeKoSyncServer()
    private val client = KoSyncClient(connectTimeoutMs = 2_000, readTimeoutMs = 2_000)
    private val account = KoSyncAccount(server.url, "reader", KoSyncAccount.userKey("secret"))

    @After
    fun tearDown() = server.stop()

    @Test
    fun theKeyIsTheMd5OfThePassword() {
        assertEquals("5ebe2294ecd0e0f08eab7690d2a6ee69", KoSyncAccount.userKey("secret"))
    }

    @Test
    fun signsIn_savesAndReadsProgress() = runTest {
        client.authorize(account)
        assertNull(client.progress(account, "abc"))

        client.update(account, "abc", "/body/DocFragment[2]/body/p/text().3", 0.58, "Honmani (Test)", "DEV1")
        val saved = client.progress(account, "abc")!!
        assertEquals("/body/DocFragment[2]/body/p/text().3", saved.progress)
        assertEquals(0.58, saved.percentage, 0.0)
        assertEquals("Honmani (Test)", saved.device)
        assertEquals("DEV1", saved.deviceId)
        assertEquals(server.now, saved.timestamp)
        assertEquals("reader", server.requests.last().headers["x-auth-user"])
    }

    @Test
    fun signsInToKOReaderServersWithTheKeyOnly() = runTest {
        val signedIn = client.signIn(" ${server.url} ", " reader ", "secret")
        assertEquals(account, signedIn)
        assertNull(signedIn.password)
        assertTrue(server.requests.all { it.headers["authorization"] == null })
    }

    @Test
    fun aWrongPasswordNeverGoesToAKOReaderServerInPlainText() = runTest {
        assertFails(KoSyncException.Reason.UNAUTHORIZED) { client.signIn(server.url, "reader", "wrong") }
        assertTrue(server.requests.isNotEmpty())
        assertTrue(server.requests.all { it.headers["authorization"] == null })
    }

    @Test
    fun signsInToCalibreWebAutomated_fromItsOwnAddress() = runTest {
        val cwa = FakeKoSyncServer(cwa = true)
        try {
            // The address as CWA's KOReader page shows it, without /kosync.
            val signedIn = client.signIn(cwa.url + "/", "reader", "secret")
            assertEquals(cwa.url + "/kosync", signedIn.server)
            assertEquals("secret", signedIn.password)

            client.update(signedIn, "abc", "/body/DocFragment[2]/body/p/text().3", 0.5, "Honmani (Test)", "DEV1")
            assertEquals("/body/DocFragment[2]/body/p/text().3", client.progress(signedIn, "abc")!!.progress)
            assertNull(cwa.requests.last().headers["x-auth-key"])

            assertFails(KoSyncException.Reason.UNAUTHORIZED) { client.signIn(cwa.url, "reader", "wrong") }
        } finally {
            cwa.stop()
        }
    }

    @Test
    fun signsInToKomga_withItsApiKeyFromItsPlainAddress() = runTest {
        val komga = FakeKoSyncServer(user = "api-key", password = "dummy", komga = true)
        try {
            // Komga's web interface answers 200 at the plain address, and 403 without the login.
            val signedIn = client.signIn(komga.url, "api-key", "dummy")
            assertEquals(komga.url + "/koreader", signedIn.server)
            assertNull(signedIn.password)
            // Over http the login follows the server's refusal, as with a 401.
            client.update(signedIn, "abc", "/body/DocFragment[2]/body/p/text().3", 0.5, "Honmani (Test)", "DEV1")
            assertEquals("/body/DocFragment[2]/body/p/text().3", client.progress(signedIn, "abc")!!.progress)
            assertEquals("api-key", komga.requests.last().headers["x-auth-user"])

            assertFails(KoSyncException.Reason.UNAUTHORIZED) { client.signIn(komga.url, "wrong-key", "dummy") }
            assertFails(KoSyncException.Reason.UNAUTHORIZED) { client.signIn(komga.url + "/koreader", "wrong-key", "dummy") }
        } finally {
            komga.stop()
        }
    }

    @Test
    fun overHttpTheLoginWaitsForTheServerToAsk() = runTest {
        client.authorize(account)
        client.progress(account, "abc")
        val sent = server.requests.map { it.headers["x-auth-key"] }
        assertEquals(listOf(null, account.userKey, account.userKey), sent)
    }

    @Test
    fun onAHotspotTheLoginIsNotSentOverHttp() = runTest {
        val hotspot = KoSyncClient(connectTimeoutMs = 2_000, readTimeoutMs = 2_000, logins = LoginGate(hotspot = { true }))
        assertFails(KoSyncException.Reason.HOTSPOT) { hotspot.signIn(server.url, "reader", "secret") }
        assertTrue(server.requests.all { it.headers["x-auth-key"] == null && it.headers["authorization"] == null })
    }

    @Test
    fun errorsSayWhatWentWrong() = runTest {
        assertFails(KoSyncException.Reason.UNAUTHORIZED) { client.authorize(account.copy(userKey = "wrong")) }
        assertFails(KoSyncException.Reason.NETWORK) { client.authorize(account.copy(server = server.url + "/nothing-here")) }
        assertFails(KoSyncException.Reason.NETWORK) { client.authorize(account.copy(server = "ftp://example.com")) }
    }

    @Test
    fun serverAddressesAsTyped() {
        assertEquals("https://sync.koreader.rocks/users/auth", KoSyncClient.endpoint("sync.koreader.rocks", "/users/auth"))
        assertEquals("https://cwa.example/kosync/users/auth", KoSyncClient.endpoint(" https://cwa.example/kosync/ ", "/users/auth"))
        assertEquals("http://nas:8081/users/auth", KoSyncClient.endpoint("http://nas:8081", "/users/auth"))
    }

    private suspend fun assertFails(reason: KoSyncException.Reason, block: suspend () -> Unit) {
        try {
            block()
            fail("Expected $reason")
        } catch (e: KoSyncException) {
            assertEquals(e.message, reason, e.reason)
        }
    }
}
