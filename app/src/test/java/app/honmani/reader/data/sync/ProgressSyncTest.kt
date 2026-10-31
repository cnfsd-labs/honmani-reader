package app.honmani.reader.data.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.honmani.reader.data.HonmaniDatabase
import app.honmani.reader.data.library.BookEntity
import app.honmani.reader.data.library.BookFormat
import app.honmani.reader.data.opds.CredentialCipher
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ProgressSyncTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val server = FakeKoSyncServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var database: HonmaniDatabase
    private lateinit var settings: KoSyncSettings
    private lateinit var sync: ProgressSync
    private lateinit var book: BookEntity
    private lateinit var document: String

    /** What the sync wrote into ttu's bookmark. */
    private var written: Pair<Long, Double>? = null

    /** This phone's clock, in milliseconds; the fake server's times are in seconds. */
    private var clock = 5_000_000L
    private lateinit var unsentFile: File

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, HonmaniDatabase::class.java).allowMainThreadQueries().build()
        val books = folder.newFolder("books")
        val file = testEpub(File(books, "h.epub"))
        document = PartialMd5.of(file)
        val id = database.bookDao().insert(
            BookEntity(contentHash = "h", title = "猫", format = BookFormat.EPUB, fileName = "h.epub", addedAt = 0, ttuId = 5),
        )
        book = database.bookDao().find(id)!!
        settings = KoSyncSettings(context.getSharedPreferences("sync-test", Context.MODE_PRIVATE), PlainCipher)
        settings.signIn(KoSyncAccount(server.url, "reader", KoSyncAccount.userKey("secret")))
        unsentFile = File(folder.root, "sync-unsent.json")
        sync = newSync(books)
    }

    /** A ProgressSync as the app makes one when it starts. */
    private fun newSync(
        books: File = File(folder.root, "books"),
        client: KoSyncClient = KoSyncClient(connectTimeoutMs = 2_000, readTimeoutMs = 2_000),
        pullTimeoutMs: Long = 4_000,
    ) = ProgressSync(
        settings = settings,
        client = client,
        pullTimeoutMs = pullTimeoutMs,
        books = database.bookDao(),
        bookFile = { File(books, it.fileName) },
        scope = scope,
        device = "Honmani (Test)",
        unsent = UnsentPositions(unsentFile),
        pushDelayMs = 50,
        now = { clock },
    )

    @After
    fun tearDown() {
        scope.cancel()
        server.stop()
        database.close()
    }

    private suspend fun pull(local: Long?, savedAtMs: Long, ask: suspend (SyncQuestion) -> Boolean = { error("Not asked: $it") }) =
        sync.pull(book, 5, bookmark = { local to savedAtMs }, setBookmark = { characters, progress -> written = characters to progress }, ask = ask)

    @Test
    fun aNewerPositionFromAnotherDeviceIsTaken_toTheCharacter() = runBlocking {
        server.save(document, "/body/DocFragment[2]/body/p/text().3", 0.58, "Kindle", "K1", timestamp = 2_000)

        val pulled = pull(local = 7, savedAtMs = 1_000_000)

        assertEquals(PulledPosition(58, "Kindle"), pulled)
        assertEquals(18L, written!!.first)
        assertEquals(18.0 / 31, written!!.second, 1e-9)
    }

    @Test
    fun aPositionKoreaderCannotPlaceFallsBackToThePercentage() = runBlocking {
        server.save(document, "/body/DocFragment[9]/body/p/text().3", 0.5, "Kindle", "K1", timestamp = 2_000)
        pull(local = null, savedAtMs = 0)
        assertEquals(16L, written!!.first)
    }

    @Test
    fun olderPositions_andThisPhonesOwn_areLeftAlone() = runBlocking {
        server.save(document, "/body/DocFragment[2]/body/p/text().3", 0.58, "Kindle", "K1", timestamp = 2_000)
        assertNull(pull(local = 7, savedAtMs = 3_000_000))

        server.save(document, "/body/DocFragment[2]/body/p/text().3", 0.58, "Honmani (Test)", settings.deviceId, timestamp = 9_000)
        assertNull(pull(local = 7, savedAtMs = 1_000))
        assertNull(written)
    }

    @Test
    fun aServerThatNeverAnswersDoesNotHoldBackTheBook() {
        java.net.ServerSocket(0).use { silent ->
            val held = java.util.concurrent.CopyOnWriteArrayList<java.net.Socket>()
            val acceptor = Thread { try { while (true) held += silent.accept() } catch (_: java.io.IOException) {} }
            acceptor.start()
            settings.signIn(KoSyncAccount("http://127.0.0.1:${silent.localPort}", "reader", KoSyncAccount.userKey("secret")))
            // The client itself would wait 30 s; the book must not.
            sync = newSync(client = KoSyncClient(connectTimeoutMs = 30_000, readTimeoutMs = 30_000), pullTimeoutMs = 300)

            val started = System.nanoTime()
            val pulled = runBlocking { pull(local = 7, savedAtMs = 0) }
            val tookMs = (System.nanoTime() - started) / 1_000_000

            assertNull(pulled)
            assertTrue("Waited $tookMs ms", tookMs < 3_000)
            held.forEach { it.close() }
        }
    }

    @Test
    fun withoutAnAccountNothingIsAsked() = runBlocking {
        settings.signOut()
        assertNull(pull(local = 7, savedAtMs = 0))
        assertTrue(server.requests.isEmpty())
    }

    @Test
    fun pageTurnsAreSentAsKoreaderPositions() = runBlocking {
        sync.onPosition(5, 3)
        sync.onPosition(5, 12)
        val saved = waitForProgress()

        assertEquals("/body/DocFragment[1]/body/div[1]/p[2]/text()[2].1", saved["progress"]!!.jsonPrimitive.content)
        assertEquals(12.0 / 31, saved["percentage"]!!.jsonPrimitive.double, 1e-9)
        assertEquals(settings.deviceId, saved["device_id"]!!.jsonPrimitive.content)
        assertEquals("Honmani (Test)", saved["device"]!!.jsonPrimitive.content)
        // The two page turns went out together (over http the first try goes without the login).
        assertEquals(1, server.requests.count { it.method == "PUT" && it.headers["x-auth-key"] != null })
    }

    @Test
    fun aPageTurnWhileAPositionIsBeingSentIsSentToo() = runBlocking {
        server.delayMs = 300
        sync.onPosition(5, 3)
        // The first position is on its way (the server is slow) when the reader turns the page.
        waitFor { server.requests.isNotEmpty() }
        sync.onPosition(5, 12)

        waitFor { server.progress[document]?.get("percentage")?.jsonPrimitive?.double == 12.0 / 31 }
    }

    @Test
    fun thePageStartAfterAJumpIsNotSentBack_butTurningBackIs() = runBlocking {
        server.save(document, "/body/DocFragment[2]/body/p/text().3", 0.58, "Kindle", "K1", timestamp = 2_000)
        pull(local = 0, savedAtMs = 0)
        // ttu opens on the page holding character 18 and saves that page's start.
        sync.onPosition(5, 14)
        sync.flush()
        Thread.sleep(300)
        assertEquals(0, server.requests.count { it.method == "PUT" })

        // Turning back a page afterwards is reading, and is sent.
        sync.onPosition(5, 12)
        sync.flush()
        assertEquals("/body/DocFragment[1]/body/div[1]/p[2]/text()[2].1", waitForProgress(device = settings.deviceId)["progress"]!!.jsonPrimitive.content)
    }

    @Test
    fun anUnchangedPositionIsNotSent() = runBlocking {
        pull(local = 7, savedAtMs = 0)
        sync.onPosition(5, 7)
        sync.flush()
        Thread.sleep(300)
        assertEquals(0, server.requests.count { it.method == "PUT" })
    }

    @Test
    fun aPositionTheServerDidNotTakeIsSentWhenItIsBack_evenAfterARestart() = runBlocking {
        server.down = true
        sync.onPosition(5, 12)
        sync.flush()
        waitFor { unsentFile.isFile }
        assertNull(server.progress[document])

        server.down = false
        val restarted = newSync()
        restarted.resend()

        assertEquals("/body/DocFragment[1]/body/div[1]/p[2]/text()[2].1", waitForProgress()["progress"]!!.jsonPrimitive.content)
        waitFor { !unsentFile.exists() }
    }

    @Test
    fun anUnsentPositionGivesWayToOneReadFurtherOnElsewhere() = runBlocking {
        server.down = true
        sync.onPosition(5, 12)
        sync.flush()
        waitFor { unsentFile.isFile }
        // The Kindle read on (character 18) after the phone's last page (5 000 s > 5 000 000 ms).
        server.save(document, "/body/DocFragment[2]/body/p/text().3", 0.58, "Kindle", "K1", timestamp = 6_000)

        server.down = false
        sync.resend()
        waitFor { !unsentFile.exists() }
        assertEquals("K1", server.progress[document]!!["device_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun anEarlierPlaceSavedElsewhereLater_isLeftToTheReader_whoStays() = runBlocking {
        holdBehindKindle()

        var asked: SyncQuestion? = null
        val pulled = pull(local = 12, savedAtMs = 5_000_000) { asked = it; false }

        assertEquals(SyncQuestion("Kindle", theirs = 0, ours = 39), asked)
        assertNull(pulled)
        assertNull(written)
        // The phone's place goes to the server, for the Kindle to take.
        assertEquals("/body/DocFragment[1]/body/div[1]/p[2]/text()[2].1", waitForProgress(device = settings.deviceId)["progress"]!!.jsonPrimitive.content)
        waitFor { !unsentFile.exists() }
    }

    @Test
    fun anEarlierPlaceSavedElsewhereLater_isLeftToTheReader_whoGoesThere() = runBlocking {
        holdBehindKindle()

        val pulled = pull(local = 12, savedAtMs = 5_000_000) { true }

        assertEquals(PulledPosition(0, "Kindle"), pulled)
        assertEquals(0L, written!!.first)
        assertTrue(!unsentFile.exists())
        assertEquals("K1", server.progress[document]!!["device_id"]!!.jsonPrimitive.content)
    }

    /**
     * The phone reads to character 12 offline; the Kindle then opens the book at the server's old
     * place (its start) and saves it: the phone's position is held, not sent.
     */
    private fun holdBehindKindle() {
        server.down = true
        sync.onPosition(5, 12)
        sync.flush()
        waitFor { unsentFile.isFile }
        server.save(document, "/body/DocFragment[9]/body/p/text().3", 0.0, "Kindle", "K1", timestamp = 6_000)

        server.down = false
        sync.resend()
        waitFor { unsentFile.readText().contains("\"held\":true") }
        assertEquals("K1", server.progress[document]!!["device_id"]!!.jsonPrimitive.content)
    }

    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertTrue(condition())
    }

    private fun waitForProgress(device: String? = null) = run {
        fun ready() = server.progress[document]?.let { saved ->
            device == null || saved["device_id"]?.jsonPrimitive?.content == device
        } == true
        val deadline = System.currentTimeMillis() + 5_000
        while (!ready() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        server.progress[document]!!
    }

    @Test
    fun aPasswordLoginIsKeptAndForgotten() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = context.getSharedPreferences("sync-password-test", Context.MODE_PRIVATE)
        val cwa = KoSyncAccount("http://nas:8083/kosync", "reader", KoSyncAccount.userKey("secret"), password = "secret")
        KoSyncSettings(preferences, PlainCipher).signIn(cwa)
        assertEquals(cwa, KoSyncSettings(preferences, PlainCipher).account.value)

        KoSyncSettings(preferences, PlainCipher).signOut()
        assertEquals(emptyMap<String, Any?>(), preferences.all.filterKeys { it != "kosync_device_id" })
    }

    private object PlainCipher : CredentialCipher {
        override fun encrypt(plain: String) = plain

        override fun decrypt(encrypted: String) = encrypted
    }
}
