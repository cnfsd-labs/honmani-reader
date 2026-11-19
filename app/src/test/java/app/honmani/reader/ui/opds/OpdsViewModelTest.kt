package app.honmani.reader.ui.opds

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.honmani.reader.data.HonmaniDatabase
import app.honmani.reader.data.library.BookEntity
import app.honmani.reader.data.library.BookFormat
import app.honmani.reader.data.library.BookImporter
import app.honmani.reader.data.library.BookSource
import app.honmani.reader.data.opds.CredentialCipher
import app.honmani.reader.data.opds.EPUB_TYPE
import app.honmani.reader.data.opds.OpdsClient
import app.honmani.reader.data.opds.OpdsRepository
import app.honmani.reader.data.opds.TEXT_TYPE
import app.honmani.reader.ui.library.BookAdder
import app.honmani.reader.ui.reader.TtuLibrary
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OpdsViewModelTest {
    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var database: HonmaniDatabase
    private lateinit var repository: OpdsRepository
    private lateinit var viewModel: OpdsViewModel
    private val server = FeedServer()
    private val stopped = Collections.synchronizedList(mutableListOf<Long>())

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), HonmaniDatabase::class.java)
            .allowMainThreadQueries().build()
        val client = OpdsClient(connectTimeoutMs = 2_000, readTimeoutMs = 5_000)
        val plain = object : CredentialCipher {
            override fun encrypt(plain: String) = plain

            override fun decrypt(encrypted: String) = encrypted
        }
        repository = OpdsRepository(database.opdsCatalogDao(), plain, client)
        viewModel = OpdsViewModel(
            repository = repository,
            client = client,
            adder = BookAdder(database.bookDao(), BookImporter(database.bookDao(), File(folder.root, "books"), File(folder.root, "covers")), TtuLibrary()),
            bookDao = database.bookDao(),
            downloadsDir = File(folder.root, "downloads"),
            stopAutoImport = { stopped += it },
        )
    }

    @After
    fun tearDown() {
        server.stop()
        database.close()
        Dispatchers.resetMain()
    }

    private val address get() = "http://127.0.0.1:${server.port}/opds"

    private suspend fun eventually(condition: () -> Boolean) = withTimeout(10_000) {
        while (!condition()) delay(10)
    }

    @Test
    fun anEntryInTheLibrary_opensItsBook() = runBlocking {
        val dao = database.bookDao()
        val book = BookEntity(contentHash = "a", title = "猫", format = BookFormat.EPUB, fileName = "a.epub", source = BookSource.OPDS, sourceId = "urn:cat", addedAt = 0)
        val id = dao.insertRow(book)
        dao.insertRow(book.copy(contentHash = "b", fileName = "b.epub", source = BookSource.FILE, sourceId = "urn:dog"))
        assertEquals(id, viewModel.libraryBookId("urn:cat"))
        assertNull(viewModel.libraryBookId("urn:dog"))
    }

    @Test
    fun downloadsAreNamedAfterTheEntry() {
        assertEquals("吾輩は猫である.epub", OpdsViewModel.fileName("吾輩は猫である", EPUB_TYPE))
        assertEquals("銀河鉄道の夜.txt", OpdsViewModel.fileName(" 銀河鉄道の夜 ", TEXT_TYPE))
        assertEquals("a_b_c.epub", OpdsViewModel.fileName("a/b:c", EPUB_TYPE))
        assertEquals("book.txt", OpdsViewModel.fileName("  ", TEXT_TYPE))
    }

    @Test
    fun aLateNextPage_landsInItsOwnPage_notInJapaneseBooksOfTheSameAddress() = runBlocking {
        val id = repository.save(null, "Calibre", address, null, null, autoImport = false)
        val catalog = repository.catalogs.first().single { it.id == id }
        val collecting = launch(Dispatchers.Unconfined) { viewModel.uiState.collect {} }
        val browsing = { viewModel.uiState.value.browsing }

        viewModel.openCatalog(catalog)
        eventually { browsing()?.page != null && browsing()?.japaneseShortcut == true }
        server.holdNextPage = CountDownLatch(1)
        viewModel.loadMore()
        eventually { server.held }
        // The start feed lists books itself: "Japanese books" filters the same address.
        viewModel.openJapanese("Japanese books")
        eventually { browsing()?.pages?.size == 2 && browsing()?.loading == false }
        val japanese = browsing()!!.pages[1]
        assertEquals(address, japanese.url)
        assertEquals(listOf("urn:a", "urn:d"), japanese.entries.map { it.id })

        server.holdNextPage!!.countDown()
        eventually { browsing()!!.pages[0].loadingMore == false }

        val (root, after) = browsing()!!.pages
        assertEquals(listOf("urn:a", "urn:b", "urn:c", "urn:d"), root.entries.map { it.id })
        assertEquals(japanese, after)
        assertFalse(after.loadingMore)
        collecting.cancel()
    }

    @Test
    fun editingTheServerFromABrowsingError_browsesTheNewAddress() = runBlocking {
        val id = repository.save(null, "Calibre", "http://127.0.0.1:${server.port}/wrong", null, null, autoImport = false)
        val catalog = repository.catalogs.first().single { it.id == id }
        val collecting = launch(Dispatchers.Unconfined) { viewModel.uiState.collect {} }
        val browsing = { viewModel.uiState.value.browsing }

        viewModel.openCatalog(catalog)
        eventually { browsing()?.error != null }
        viewModel.startEditing(catalog)
        viewModel.saveCatalog(address, "", "", autoImport = false)
        eventually { viewModel.uiState.value.editor == null && browsing()?.page != null }

        assertEquals(null, browsing()!!.error)
        assertEquals(address, browsing()!!.page!!.url)
        collecting.cancel()
    }

    @Test
    fun addingAListedServerAgain_keepsItsPassword_andStopsItsImportWhenTurnedOff() = runBlocking {
        val id = repository.save(null, "Calibre", address, "reader", "pw", autoImport = true)

        viewModel.startAdding()
        viewModel.saveCatalog(address, "reader", "", autoImport = false)
        eventually { viewModel.uiState.value.editor == null && stopped.isNotEmpty() }

        assertEquals("pw", repository.server(id)!!.password)
        assertEquals(listOf(id), stopped.toList())
        assertFalse(database.opdsCatalogDao().find(id)!!.autoImport)
    }
}

/** Serves a catalogue whose start feed lists books on two pages, each request on its own thread. */
private class FeedServer {
    private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    val port = socket.localPort

    /** Set: the next request for page 2 waits for it. */
    @Volatile var holdNextPage: CountDownLatch? = null

    @Volatile var held = false

    init {
        Thread {
            while (!socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (_: Exception) {
                    break
                }
                Thread { client.use { handle(it) } }.apply { isDaemon = true }.start()
            }
        }.apply { isDaemon = true }.start()
    }

    private fun handle(client: java.net.Socket) {
        val reader = client.getInputStream().bufferedReader(Charsets.ISO_8859_1)
        val path = reader.readLine()?.split(' ')?.getOrNull(1) ?: return
        while (!reader.readLine().isNullOrEmpty()) Unit
        val body = when (path) {
            "/opds" -> feed(book("a", "jpn") + book("b", "eng"), """<link rel="next" href="/opds?page=2" type="$NAV"/>""")
            "/opds?page=2" -> {
                val latch = holdNextPage
                if (latch != null && !held) {
                    held = true
                    latch.await(10, TimeUnit.SECONDS)
                }
                feed(book("c", "eng") + book("d", "jpn"), "")
            }
            else -> null
        }
        val out = client.getOutputStream()
        val bytes = body?.toByteArray() ?: ByteArray(0)
        val status = if (body == null) "404 Not Found" else "200 OK"
        out.write(
            "HTTP/1.1 $status\r\nContent-Type: application/atom+xml\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                .toByteArray(),
        )
        out.write(bytes)
        out.flush()
    }

    fun stop() = socket.close()

    private fun feed(entries: String, links: String) =
        """<feed xmlns="http://www.w3.org/2005/Atom"><title>Calibre</title>$links$entries</feed>"""

    private fun book(name: String, language: String) =
        """<entry><title>本$name</title><id>urn:$name</id>""" +
            """<dcterms:language xmlns:dcterms="http://purl.org/dc/terms/">$language</dcterms:language>""" +
            """<link rel="http://opds-spec.org/acquisition" href="/download/$name.epub" type="$EPUB_TYPE"/></entry>"""

    private companion object {
        const val NAV = "application/atom+xml;profile=opds-catalog"
    }
}
