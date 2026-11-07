package app.honmani.reader.data.dictionary

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.honmani.reader.data.HonmaniDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DictionaryUpdateCheckerTest {
    private lateinit var database: HonmaniDatabase
    private val remote = mutableMapOf<String, String>()
    private val requested = mutableListOf<String>()
    private lateinit var checker: DictionaryUpdateChecker
    private val now = 100 * DictionaryUpdateChecker.MIN_AGE_MS

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            HonmaniDatabase::class.java,
        ).allowMainThreadQueries().build()
        checker = DictionaryUpdateChecker(
            dao = database.dictionaryDao(),
            fetchText = { url ->
                requested += url
                remote[url] ?: throw DownloadException("The server answered 404 for $url")
            },
            scope = CoroutineScope(Dispatchers.Unconfined),
            clock = { now },
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun aNewRevisionIsOffered_withTheDownloadUrlOfTheNewIndex() = runTest {
        val id = install("Jitendex.org [2026-10-03]", "2026.10.03.0", "https://jitendex.org/static/yomitan.json")
        remote["https://jitendex.org/static/yomitan.json"] =
            """{"title":"Jitendex.org [2026-10-17]","revision":"2026.10.17.0","downloadUrl":"https://jitendex.org/jitendex-new.zip"}"""

        val updates = checker.check()

        assertEquals(
            listOf(DictionaryUpdate(id, "Jitendex.org [2026-10-03]", "2026.10.17.0", "https://jitendex.org/jitendex-new.zip")),
            updates,
        )
    }

    @Test
    fun theSameRevisionIsNoUpdate() = runTest {
        install("Jiten", "Jiten 26-10-09", "https://api.jiten.moe/api/frequency-list/index")
        remote["https://api.jiten.moe/api/frequency-list/index"] = """{"title":"Jiten","revision":"Jiten 26-10-09"}"""

        assertTrue(checker.check().isEmpty())
    }

    @Test
    fun unreachableOrBrokenIndexesAreSkipped_andTheOthersStillChecked() = runTest {
        install("A", "1", "https://a.example/index.json")
        install("B", "1", "https://b.example/index.json")
        val c = install("C", "1", "https://c.example/index.json")
        remote["https://b.example/index.json"] = "not json"
        remote["https://c.example/index.json"] = """{"revision":"2","downloadUrl":"https://c.example/c.zip"}"""

        assertEquals(listOf(c), checker.check().map { it.dictionaryId })
    }

    @Test
    fun onlyUpdatableDictionariesWithHttpsAddressesAreAsked() = runTest {
        install("Plain", "1", "http://insecure.example/index.json")
        database.dictionaryDao().insert(
            DictionaryEntity(title = "Own", revision = "1", formatVersion = 3, importedAt = 0, indexUrl = "https://own.example/i.json"),
        )

        assertTrue(checker.check().isEmpty())
        assertTrue(requested.isEmpty())
    }

    @Test
    fun anUpdateWithoutAnHttpsDownloadIsNotOffered() {
        val installed = DictionaryEntity(
            id = 1, title = "A", revision = "1", formatVersion = 3, importedAt = 0, downloadUrl = "https://a/old.zip",
        )

        val withoutStored = installed.copy(downloadUrl = null)
        assertNull(DictionaryUpdateChecker.updateFor(withoutStored, """{"revision":"2"}"""))
        assertNull(DictionaryUpdateChecker.updateFor(withoutStored, """{"revision":"2","downloadUrl":"https://a/a.zip"}"""))
        assertEquals(
            "https://a/old.zip",
            DictionaryUpdateChecker.updateFor(installed, """{"revision":"2","downloadUrl":"http://a/a.zip"}""")?.downloadUrl,
        )
        assertEquals("https://a/old.zip", DictionaryUpdateChecker.updateFor(installed, """{"revision":"2"}""")?.downloadUrl)
    }

    @Test
    fun aNewDownloadUrlOnAnotherHostIsIgnored() {
        val installed = DictionaryEntity(
            id = 1, title = "A", revision = "1", formatVersion = 3, importedAt = 0,
            downloadUrl = "https://github.com/a/releases/latest/download/a.zip",
        )

        fun offered(url: String) =
            DictionaryUpdateChecker.updateFor(installed, """{"revision":"2","downloadUrl":"$url"}""")?.downloadUrl

        assertEquals("https://GitHub.com/a/b.zip", offered("https://GitHub.com/a/b.zip"))
        assertEquals("https://github.com/b/new.zip", offered("https://github.com/b/new.zip"))
        // Another host, a look-alike host, or credentials in front of the real one: the stored address stays.
        assertEquals(installed.downloadUrl, offered("https://evil.example/a.zip"))
        assertEquals(installed.downloadUrl, offered("https://github.com.evil.example/a.zip"))
        assertEquals(installed.downloadUrl, offered("https://github.com@evil.example/a.zip"))
        assertEquals(installed.downloadUrl, offered("https://objects.githubusercontent.com/a.zip"))
    }

    @Test
    fun updatesDisappearOnceTheNewVersionIsInstalled() = runTest {
        val id = install("A", "1", "https://a.example/index.json")
        remote["https://a.example/index.json"] = """{"revision":"2","downloadUrl":"https://a.example/a.zip"}"""

        // The check runs in the background (Room moves it to its own thread): wait for its end.
        checker.start(all = true)
        checker.checking.first { !it }
        assertEquals(listOf(id), checker.updates.first().map { it.dictionaryId })
        assertTrue(checker.checked.value)

        database.dictionaryDao().delete(id)
        install("A", "2", "https://a.example/index.json")
        assertTrue(checker.updates.first().isEmpty())
    }

    @Test
    fun theCheckAtStartLeavesOutDictionariesInstalledThisWeek() = runTest {
        install("Old", "1", "https://old.example/index.json")
        install("New", "1", "https://new.example/index.json", importedAt = now - 1_000)
        remote["https://old.example/index.json"] = """{"revision":"2","downloadUrl":"https://old.example/a.zip"}"""
        remote["https://new.example/index.json"] = """{"revision":"2","downloadUrl":"https://new.example/a.zip"}"""

        checker.start()
        checker.checking.first { !it }
        assertEquals(listOf("Old"), checker.updates.first().map { it.name })

        checker.start(all = true)
        checker.checking.first { !it }
        assertEquals(listOf("Old", "New"), checker.updates.first().map { it.name })
    }

    private suspend fun install(title: String, revision: String, indexUrl: String, importedAt: Long = 0): Long =
        database.dictionaryDao().insert(
            DictionaryEntity(
                title = title,
                revision = revision,
                formatVersion = 3,
                importedAt = importedAt,
                isUpdatable = true,
                indexUrl = indexUrl,
                downloadUrl = indexUrl.substringBeforeLast('/') + "/old.zip",
            ),
        )
}
