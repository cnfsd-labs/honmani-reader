package app.honmani.reader.data.sync

import android.content.Context
import androidx.compose.material3.SnackbarHostState
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.honmani.reader.data.HonmaniDatabase
import app.honmani.reader.data.library.BookEntity
import app.honmani.reader.data.library.BookFormat
import app.honmani.reader.data.opds.CredentialCipher
import app.honmani.reader.ui.reader.LibraryMessage
import app.honmani.reader.ui.reader.PlaceJumper
import app.honmani.reader.ui.reader.TtuLibrary
import app.honmani.reader.ui.reader.TtuPage
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.long
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
    private lateinit var status: KoSyncStatus
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
        status = KoSyncStatus(context.getSharedPreferences("sync-status-test", Context.MODE_PRIVATE)) { clock }
        unsentFile = File(folder.root, "sync-unsent.json")
        sync = newSync(books)
    }

    /** A ProgressSync as the app makes one when it starts. */
    private fun newSync(
        books: File = File(folder.root, "books"),
        client: KoSyncClient = KoSyncClient(connectTimeoutMs = 2_000, readTimeoutMs = 2_000),
        pullTimeoutMs: Long = 4_000,
        parsing: kotlin.coroutines.CoroutineContext = Dispatchers.Default,
        pushDelayMs: Long = 50,
    ) = ProgressSync(
        settings = settings,
        client = client,
        pullTimeoutMs = pullTimeoutMs,
        books = database.bookDao(),
        bookFile = { File(books, it.fileName) },
        scope = scope,
        device = "Honmani (Test)",
        unsent = UnsentPositions(unsentFile),
        pushDelayMs = pushDelayMs,
        now = { clock },
        status = status,
        parsing = parsing,
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
    fun aTakenPosition_keepsThePlaceBefore_onceForTheReader() = runBlocking {
        server.save(document, "/body/DocFragment[2]/body/p/text().3", 0.58, "Kindle", "K1", timestamp = 2_000)

        pull(local = 7, savedAtMs = 1_000_000)

        assertEquals(PlaceBefore(7, 31, 23, PulledPosition(58, "Kindle")), sync.placeBefore(5))
        assertEquals(PlaceBefore(7, 31, 23, PulledPosition(58, "Kindle")), sync.takePlaceBefore(5))
        assertNull(sync.takePlaceBefore(5))
    }

    @Test
    fun withoutAPlaceOfItsOwn_thereIsNoWayBack() = runBlocking {
        server.save(document, "/body/DocFragment[2]/body/p/text().3", 0.58, "Kindle", "K1", timestamp = 2_000)

        pull(local = null, savedAtMs = 0)

        assertNull(sync.takePlaceBefore(5))
    }

    @Test
    fun theWayBack_opensThePlaceBeforeThePull() = runBlocking {
        server.save(document, "/body/DocFragment[2]/body/p/text().3", 0.58, "Kindle", "K1", timestamp = 2_000)
        pull(local = 7, savedAtMs = 1_000_000)
        val before = sync.takePlaceBefore(5)!!
        val scripts = java.util.concurrent.CopyOnWriteArrayList<String>()
        val ttu = TtuLibrary(pageTimeoutMs = 5_000)
        ttu.attach(object : TtuPage {
            override fun evaluate(script: String) {
                scripts += script
            }

            override fun load(path: String) = Unit
        })
        val snackbar = SnackbarHostState()
        val jumper = PlaceJumper(ttu, sync, scope, snackbar)

        jumper.offerBack(5, before.characters, before.total, null, "Continued at 58% from Kindle", "Back to 23%")
        withTimeout(5_000) { while (snackbar.currentSnackbarData == null) delay(10) }
        assertEquals("Back to 23%", snackbar.currentSnackbarData!!.visuals.actionLabel)
        snackbar.currentSnackbarData!!.performAction()
        withTimeout(5_000) { while (scripts.none { "setBookmark" in it }) delay(10) }

        val arguments = kotlinx.serialization.json.Json.parseToJsonElement(
            scripts.last().substringAfter("(").substringBeforeLast(")"),
        ).jsonObject
        assertEquals(5L, arguments["ttuId"]!!.jsonPrimitive.long)
        assertEquals(7L, arguments["characters"]!!.jsonPrimitive.long)
        ttu.onMessage(LibraryMessage.BookmarkSet(arguments["requestId"]!!.jsonPrimitive.long))
        withTimeout(5_000) { while (scripts.none { "honmaniSearch" in it }) delay(10) }
    }

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
    fun aSlowChapterParseDoesNotHoldBackTheBook() {
        server.save(document, "/body/DocFragment[2]/body/p/text().3", 0.58, "Kindle", "K1", timestamp = 2_000)
        // The book's first read is quick; placing the server's position in a chapter then takes 2 s.
        val dispatches = java.util.concurrent.atomic.AtomicInteger()
        val slow = object : kotlinx.coroutines.CoroutineDispatcher() {
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
                val wait = dispatches.incrementAndGet() > 1
                Thread { if (wait) Thread.sleep(2_000); block.run() }.start()
            }
        }
        sync = newSync(pullTimeoutMs = 300, parsing = slow)

        val started = System.nanoTime()
        val pulled = runBlocking { pull(local = 7, savedAtMs = 1_000_000) }
        val tookMs = (System.nanoTime() - started) / 1_000_000

        assertNull(pulled)
        assertTrue("Waited $tookMs ms", tookMs < 1_500)
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
        sync.idle()
        assertEquals(0, server.requests.count { it.method == "PUT" })

        // Turning back a page afterwards is reading, and is sent.
        sync.onPosition(5, 12)
        sync.flush()
        assertEquals("/body/DocFragment[1]/body/div[1]/p[2]/text()[2].1", waitForProgress(device = settings.deviceId)["progress"]!!.jsonPrimitive.content)
    }

    @Test
    fun theFirstSaveAfterAJumpIsAGlanceAndNotSent_butReadingOnFromThereIs() = runBlocking {
        pull(local = 7, savedAtMs = 0)
        // The reader jumped to character 20; ttu saves the start of the page that holds it.
        sync.onJump(5, 20)
        sync.onPosition(5, 18)
        sync.flush()
        sync.idle()
        assertEquals(0, server.requests.count { it.method == "PUT" })

        sync.onPosition(5, 24)
        sync.flush()
        assertEquals(24.0 / 31, waitForProgress(device = settings.deviceId)["percentage"]!!.jsonPrimitive.double, 1e-9)
    }

    @Test
    fun aPositionSavedJustAfterLeavingIsSentAtOnce() = runBlocking {
        sync = newSync(pushDelayMs = 60_000)
        pull(local = 7, savedAtMs = 0)
        // ttu answers the progress request of leaving after the flush.
        sync.flush()
        sync.onPosition(5, 24)
        assertEquals(24.0 / 31, waitForProgress(device = settings.deviceId)["percentage"]!!.jsonPrimitive.double, 1e-9)
    }

    @Test
    fun anUnchangedPositionIsNotSent() = runBlocking {
        pull(local = 7, savedAtMs = 0)
        sync.onPosition(5, 7)
        sync.flush()
        sync.idle()
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
    fun theStatusKeepsTheLastFailure_untilTheNextSuccess() = runBlocking {
        assertNull(status.state.value.failure)

        server.down = true
        assertNull(pull(local = 7, savedAtMs = 1_000_000))
        val failure = status.state.value.failure!!
        assertEquals(SyncFailure.Kind.SERVER, failure.reason)
        assertEquals(5L, failure.ttuId)
        assertEquals(clock, failure.at)
        assertNull(status.state.value.lastSuccessAt)

        clock += 60_000
        server.down = false
        pull(local = 7, savedAtMs = 1_000_000)
        assertNull(status.state.value.failure)
        assertEquals(clock, status.state.value.lastSuccessAt)
    }

    @Test
    fun aFailedPushIsRecordedAndASentPositionClearsIt() = runBlocking {
        server.down = true
        sync.onPosition(5, 12)
        sync.flush()
        waitFor { status.state.value.failure != null }
        assertEquals(5L, status.state.value.failure!!.ttuId)

        server.down = false
        sync.resend()
        waitForProgress()
        waitFor { status.state.value.failure == null && status.state.value.lastSuccessAt != null }
    }

    @Test
    fun syncNowAfterAFailure_sendsWhatWasNotSentAndClearsTheFailure() = runBlocking {
        server.down = true
        sync.onPosition(5, 12)
        sync.flush()
        waitFor { status.state.value.failure != null }

        server.down = false
        assertTrue(sync.syncNow())
        assertEquals(12.0 / 31, waitForProgress()["percentage"]!!.jsonPrimitive.double, 1e-9)
        waitFor { status.state.value.failure == null }
    }

    @Test
    fun syncNow_sendsTheCurrentPositionWithoutWaiting() = runBlocking {
        sync = newSync(pushDelayMs = 60_000)
        sync.onPosition(5, 24)
        assertTrue(sync.syncNow())
        assertEquals(24.0 / 31, waitForProgress()["percentage"]!!.jsonPrimitive.double, 1e-9)
    }

    @Test
    fun syncNowAfterAFailedPull_saysNothingWaited_andThePullAgainClearsTheFailure() = runBlocking {
        server.down = true
        assertNull(pull(local = 7, savedAtMs = 0))
        assertNotNull(status.state.value.failure)

        server.down = false
        server.save(document, "/body/DocFragment[2]/body/p/text().3", 0.58, "Kindle", "K1", timestamp = 2_000)
        assertFalse(sync.syncNow())
        val pulls = server.requests.count { it.method == "GET" && it.path.startsWith("/syncs/progress/") }
        assertNotNull(pull(local = 7, savedAtMs = 0))
        assertTrue(server.requests.count { it.method == "GET" && it.path.startsWith("/syncs/progress/") } > pulls)
        assertNull(status.state.value.failure)
    }

    @Test
    fun checkingTheServer_clearsTheFailureOnlyWhenItAnswers() = runBlocking {
        server.down = true
        assertNull(pull(local = 7, savedAtMs = 0))
        sync.checkServer()
        assertEquals(5L, status.state.value.failure!!.ttuId)

        server.down = false
        sync.checkServer()
        assertNull(status.state.value.failure)
        assertNotNull(status.state.value.lastSuccessAt)
    }

    @Test
    fun signingOutForgetsTheStatus() {
        status.succeeded()
        assertNotNull(status.state.value.lastSuccessAt)
        // Let the sync start watching the account.
        Thread.sleep(300)
        settings.signOut()
        waitFor { status.state.value.lastSuccessAt == null }
    }

    @Test
    fun aPositionSavedBeforeTheBookWasParsed_isSentAfterAProcessKill() = runBlocking {
        // The app died after the raw position was written and before the book was parsed.
        UnsentPositions(unsentFile).putRaw(RawPosition(ttuId = 5, characters = 12, readAt = 5_000_000))
        val rawFile = File(folder.root, "sync-unsent.json.raw")
        assertTrue(rawFile.isFile)

        newSync().resend()

        assertEquals("/body/DocFragment[1]/body/div[1]/p[2]/text()[2].1", waitForProgress()["progress"]!!.jsonPrimitive.content)
        waitFor { !rawFile.exists() && !unsentFile.exists() }
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
    fun withoutServerTimes_anUnsentPositionIsNeverSentBackwards() = runBlocking {
        server.down = true
        sync.onPosition(5, 12)
        sync.flush()
        waitFor { unsentFile.isFile }
        // An old server (no times): the Kindle is further on (character 18).
        server.save(document, "/body/DocFragment[2]/body/p/text().3", 0.58, "Kindle", "K1", timestamp = null)

        server.down = false
        sync.resend()
        waitFor { !unsentFile.exists() }
        assertEquals("K1", server.progress[document]!!["device_id"]!!.jsonPrimitive.content)
        assertTrue(server.requests.none { it.method == "PUT" })
    }

    @Test
    fun withoutServerTimes_anUnsentPositionFurtherOnIsSent() = runBlocking {
        server.down = true
        sync.onPosition(5, 12)
        sync.flush()
        waitFor { unsentFile.isFile }
        server.save(document, "/body/DocFragment[9]/body/p/text().3", 0.0, "Kindle", "K1", timestamp = null)

        server.down = false
        sync.resend()
        assertEquals(12.0 / 31, waitForProgress(device = settings.deviceId)["percentage"]!!.jsonPrimitive.double, 1e-9)
        waitFor { !unsentFile.exists() }
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

    @Test
    fun aLoginThePhoneCanNoLongerRead_keepsUnsentPositionsUntilSignedInAgain() = runBlocking {
        server.down = true
        sync.onPosition(5, 12)
        sync.flush()
        waitFor { unsentFile.isFile }

        // The next start: the Keystore key is gone, so the login can't be decrypted.
        val unreadable = object : CredentialCipher {
            override fun encrypt(plain: String) = plain

            override fun decrypt(encrypted: String): String? = null
        }
        val context = ApplicationProvider.getApplicationContext<Context>()
        settings = KoSyncSettings(context.getSharedPreferences("sync-test", Context.MODE_PRIVATE), unreadable)
        assertNull(settings.account.value)
        assertEquals(server.url to "reader", settings.signedInAs.value)
        val restarted = newSync()
        Thread.sleep(300)
        assertTrue(unsentFile.isFile)

        // The reader enters the password again: the kept position goes out.
        server.down = false
        settings.signIn(KoSyncAccount(server.url, "reader", KoSyncAccount.userKey("secret")))
        restarted.resend()
        assertEquals("/body/DocFragment[1]/body/div[1]/p[2]/text()[2].1", waitForProgress()["progress"]!!.jsonPrimitive.content)
    }

    @Test
    fun aKeystoreFailureAtSignInIsASyncError_andNothingIsKept() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = context.getSharedPreferences("sync-keystore-test", Context.MODE_PRIVATE)
        val broken = object : CredentialCipher {
            override fun encrypt(plain: String): String = throw app.honmani.reader.data.opds.CredentialException(java.security.KeyStoreException("Keystore gone"))

            override fun decrypt(encrypted: String): String? = null
        }
        val brokenSettings = KoSyncSettings(preferences, broken)
        val error = try {
            brokenSettings.signIn(KoSyncAccount(server.url, "reader", KoSyncAccount.userKey("secret")))
            null
        } catch (e: KoSyncException) {
            e
        }
        assertEquals(KoSyncException.Reason.PASSWORD_STORAGE, error!!.reason)
        assertNull(brokenSettings.account.value)
        assertEquals(emptyMap<String, Any?>(), preferences.all.filterKeys { it != "kosync_device_id" })
    }

    private suspend fun pullOnImport(of: BookEntity = book) =
        sync.pullOnImport(of, 5) { characters, progress -> written = characters to progress }

    @Test
    fun onImport_anotherDevicesPositionIsWrittenAndShownInTheLibrary() = runBlocking {
        server.save(document, "/body/DocFragment[2]/body/p/text().3", 0.58, "Kindle", "K1", timestamp = 2_000)

        assertEquals(PulledPosition(58, "Kindle"), pullOnImport())

        assertEquals(18L, written!!.first)
        val row = database.bookDao().find(book.id)!!
        assertEquals(18f / 31, row.progress, 1e-6f)
        assertNull(row.finishedAt)
        // Taken from the server: ttu's first save there doesn't go straight back.
        sync.onPosition(5, 18)
        sync.idle()
        assertTrue(server.requests.none { it.method == "PUT" })
    }

    @Test
    fun onImport_aPositionAtTheEndMarksTheBookFinished() = runBlocking {
        server.save(document, "/body/DocFragment[2]/body/p/text().3", 0.998, "Kindle", "K1", timestamp = 2_000)

        assertNotNull(pullOnImport())

        assertEquals(clock, database.bookDao().find(book.id)!!.finishedAt)
    }

    @Test
    fun onImport_noPosition_orThisPhonesOwn_changesNothing() = runBlocking {
        assertNull(pullOnImport())
        server.save(document, "/body/DocFragment[2]/body/p/text().3", 0.998, "Honmani (Test)", settings.deviceId, timestamp = 2_000)
        assertNull(pullOnImport())

        assertNull(written)
        val row = database.bookDao().find(book.id)!!
        assertEquals(0f, row.progress)
        assertNull(row.finishedAt)
    }

    @Test
    fun onImport_otherFormats_andSignedOut_askNothing() = runBlocking {
        server.save(document, "/body/DocFragment[2]/body/p/text().3", 0.58, "Kindle", "K1", timestamp = 2_000)
        assertNull(pullOnImport(book.copy(format = BookFormat.TXT)))
        settings.signOut()
        assertNull(pullOnImport())

        assertNull(written)
        assertTrue(server.requests.isEmpty())
    }

    @Test
    fun onImport_aServerErrorIsRecorded_notThrown() = runBlocking {
        server.down = true

        assertNull(pullOnImport())

        assertNull(written)
        assertEquals(5L, status.state.value.failure!!.ttuId)
    }

    @Test
    fun onAutoImport_theLibraryShowsTheProgress_andTheFirstOpenTakesThePosition() = runBlocking {
        val id = database.bookDao().insert(
            BookEntity(contentHash = "n", title = "犬", format = BookFormat.EPUB, fileName = "h.epub", addedAt = 0),
        )
        val imported = database.bookDao().find(id)!!
        server.save(document, "/body/DocFragment[2]/body/p/text().3", 0.58, "Kindle", "K1", timestamp = 2_000)

        sync.progressOnImport(imported)

        assertEquals(18f / 31, database.bookDao().find(id)!!.progress, 1e-6f)
        assertNull(database.bookDao().find(id)!!.finishedAt)
        assertNull(written)
        // Opened for the first time: ttu has no bookmark of its own, so the position is taken.
        database.bookDao().setTtuBook(id, 9, "犬")
        val pulled = sync.pull(database.bookDao().find(id)!!, 9, bookmark = { null to 0L }, setBookmark = { c, p -> written = c to p })
        assertEquals(PulledPosition(58, "Kindle"), pulled)
        assertEquals(18L, written!!.first)
        assertEquals(18f / 31, database.bookDao().find(id)!!.progress, 1e-6f)
    }

    @Test
    fun onAutoImport_aPositionAtTheEndMarksTheBookFinished_andErrorsAreRecorded() = runBlocking {
        val id = database.bookDao().insert(
            BookEntity(contentHash = "n", title = "犬", format = BookFormat.EPUB, fileName = "h.epub", addedAt = 0),
        )
        server.down = true
        sync.progressOnImport(database.bookDao().find(id)!!)
        assertNotNull(status.state.value.failure)
        assertEquals(0f, database.bookDao().find(id)!!.progress)

        server.down = false
        server.save(document, "/body/DocFragment[2]/body/p/text().3", 0.998, "Kindle", "K1", timestamp = 2_000)
        sync.progressOnImport(database.bookDao().find(id)!!)
        assertEquals(clock, database.bookDao().find(id)!!.finishedAt)
    }

    @Test
    fun onAutoImport_thisPhonesOwnPosition_orSignedOut_changesNothing() = runBlocking {
        server.save(document, "/body/DocFragment[2]/body/p/text().3", 0.998, "Honmani (Test)", settings.deviceId, timestamp = 2_000)
        sync.progressOnImport(book.copy(ttuId = null))
        settings.signOut()
        server.save(document, "/body/DocFragment[2]/body/p/text().3", 0.5, "Kindle", "K1", timestamp = 3_000)
        sync.progressOnImport(book.copy(ttuId = null))

        val row = database.bookDao().find(book.id)!!
        assertEquals(0f, row.progress)
        assertNull(row.finishedAt)
    }

    private object PlainCipher : CredentialCipher {
        override fun encrypt(plain: String) = plain

        override fun decrypt(encrypted: String) = encrypted
    }
}
