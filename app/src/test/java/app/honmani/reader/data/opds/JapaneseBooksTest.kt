package app.honmani.reader.data.opds

import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Japanese books" against a small Calibre-Web-like server. */
class JapaneseBooksTest {
    private val client = OpdsClient(connectTimeoutMs = 2_000, readTimeoutMs = 2_000)
    private val requests = mutableListOf<String>()

    /** The start feed; `{languages}` is the languages entry, left out for a server without one. */
    private var root = rootFeed(withLanguages = true)

    /** The languages feed per page (Calibre-Web titles them in the server's language). */
    private var languagePages = listOf(listOf("English" to 1, "Japanese" to 2))

    /** The "Recently added" pages: book names with their language. */
    private var newPages = listOf<List<Pair<String, String>>>()

    private val server = HeaderServer { path, out ->
        synchronized(requests) { requests += path }
        when {
            path == "/opds" -> out.respond("200 OK", root, "Content-Type: application/atom+xml")
            path.startsWith("/opds/language/") -> out.respond("200 OK", feed("Books", emptyList(), null))
            path == "/opds/language" || path.startsWith("/opds/language?") -> {
                val page = path.substringAfter("page=", "1").toInt()
                val entries = languagePages.getOrElse(page - 1) { emptyList() }.map { (name, id) ->
                    // As in Calibre-Web's feed.xml: id and link are the language's numeric address.
                    """<entry><title>$name</title><id>/opds/language/$id</id>""" +
                        """<link rel="subsection" type="$NAV" href="/opds/language/$id"/></entry>"""
                }
                val next = if (page < languagePages.size) "/opds/language?page=${page + 1}" else null
                out.respond("200 OK", feed("Languages", entries, next))
            }
            path.startsWith("/opds/new") -> {
                val page = path.substringAfter("page=", "1").toInt()
                val entries = newPages.getOrElse(page - 1) { emptyList() }.map { (name, language) -> book(name, language) }
                val next = if (page < newPages.size) "/opds/new?page=${page + 1}" else null
                out.respond("200 OK", feed("Recently added", entries, next))
            }
            else -> out.respond("404 Not Found", "")
        }
    }
    private val opds = OpdsServer("http://127.0.0.1:${server.port}/opds")
    private val base = "http://127.0.0.1:${server.port}"

    @After
    fun tearDown() = server.stop()

    private suspend fun find(): JapaneseBooks.Source? {
        val feed = client.feed(opds, opds.url)
        return JapaneseBooks.find(feed, opds.url) { client.feed(opds, it) }
    }

    @Test
    fun opensTheServersJapaneseFeed() = runTest {
        assertEquals(JapaneseBooks.Source.LanguageFeed("$base/opds/language/2"), find())
    }

    @Test
    fun findsJapaneseByItsNameInOtherLanguages() = runTest {
        for (name in listOf("日本語", "Japanisch", "japonais", "Japonés", "giapponese", "Японский", "日语")) {
            languagePages = listOf(listOf("English" to 1, name to 7, "Deutsch" to 3))
            assertEquals(name, JapaneseBooks.Source.LanguageFeed("$base/opds/language/7"), find())
        }
    }

    @Test
    fun readsTheNextPagesOfALongLanguageList() = runTest {
        languagePages = listOf(listOf("English" to 1), listOf("Japanese" to 9))
        assertEquals(JapaneseBooks.Source.LanguageFeed("$base/opds/language/9"), find())
    }

    @Test
    fun languageCodesInAddressesCount_andAnExactNameBeatsANearOne() {
        val entries = listOf(
            OpdsEntry("x", "Japanese Sign Language", feedUrl = "https://h/opds/language/4"),
            OpdsEntry("y", "Nihongo", feedUrl = "https://h/opds/language/jpn"),
        )
        assertEquals("y", JapaneseBooks.japaneseEntry(entries)?.id)
        assertEquals("x", JapaneseBooks.japaneseEntry(entries.take(1))?.id)
        assertNull(JapaneseBooks.japaneseEntry(listOf(OpdsEntry("e", "English", feedUrl = "https://h/opds/language/1"))))
    }

    @Test
    fun withoutJapaneseInTheList_showsTheNewestBooksFiltered() = runTest {
        languagePages = listOf(listOf("English" to 1, "Deutsch" to 3))
        assertEquals(JapaneseBooks.Source.Filtered("$base/opds/new"), find())
    }

    @Test
    fun aServerWithoutALanguageList_showsTheNewestBooksFiltered() = runTest {
        root = rootFeed(withLanguages = false)
        val feed = client.feed(opds, opds.url)
        assertNull(JapaneseBooks.languagesUrl(feed))
        assertTrue(JapaneseBooks.available(feed, opds.url))
        assertEquals(JapaneseBooks.Source.Filtered("$base/opds/new"), find())
        assertTrue(requests.none { it.startsWith("/opds/language") })
    }

    @Test
    fun aHiddenLanguageList_showsTheNewestBooksFiltered() = runTest {
        // Calibre-Web answers 404 when the languages list is hidden for the user.
        root = root.replace("/opds/language\"", "/opds/languages-hidden\"")
        assertEquals(JapaneseBooks.Source.Filtered("$base/opds/new"), find())
    }

    @Test
    fun aCatalogueWithNeither_offersNothing() = runTest {
        val empty = OpdsFeed("", listOf(OpdsEntry("a", "Authors", feedUrl = "https://h/opds/author")))
        assertFalse(JapaneseBooks.available(empty, "https://h/opds"))
        assertNull(JapaneseBooks.find(empty, "https://h/opds") { error("No request expected") })
    }

    @Test
    fun theFilterKeepsJapaneseBooksOnly_andReadsOnUntilThereAreEnough() = runTest {
        newPages = listOf(
            listOf("a" to "eng", "b" to "jpn", "c" to "ger"),
            List(5) { "en$it" to "eng" },
            List(JapaneseBooks.MIN_ENTRIES) { "ja$it" to "ja" },
            listOf("late" to "jpn"),
        )
        val page = JapaneseBooks.loadFiltered("$base/opds/new") { client.feed(opds, it) }
        assertEquals("本b", page.entries.first().title)
        assertTrue(page.entries.all { it.isJapanese })
        assertEquals(1 + JapaneseBooks.MIN_ENTRIES, page.entries.size)
        // Enough after page 3: page 4 waits until the list's end is reached.
        assertEquals("$base/opds/new?page=4", page.nextUrl)
        assertFalse(page.foundNone)
    }

    @Test
    fun theFilterStopsAfterItsPageLimit_whenNothingIsJapanese() = runTest {
        newPages = List(JapaneseBooks.MAX_PAGES_PER_LOAD + 2) { page -> listOf("en$page" to "eng") }
        val page = JapaneseBooks.loadFiltered("$base/opds/new") { client.feed(opds, it) }
        assertTrue(page.entries.isEmpty())
        assertTrue(page.foundNone)
        assertEquals("$base/opds/new?page=${JapaneseBooks.MAX_PAGES_PER_LOAD + 1}", page.nextUrl)
        assertEquals(JapaneseBooks.MAX_PAGES_PER_LOAD, requests.count { it.startsWith("/opds/new") })
    }

    @Test
    fun theFilterEndsWithTheFeed() = runTest {
        newPages = listOf(listOf("a" to "eng"), listOf("b" to "jpn"))
        val page = JapaneseBooks.loadFiltered("$base/opds/new") { client.feed(opds, it) }
        assertEquals(listOf("本b"), page.entries.map { it.title })
        assertNull(page.nextUrl)
        assertFalse(page.foundNone)
    }

    private fun rootFeed(withLanguages: Boolean): String {
        val languages = if (withLanguages) {
            // Calibre-Web's index.xml, here translated to German: the title is no help.
            """<entry><title>Sprachen</title><id>/opds/language</id><link href="/opds/language" type="$NAV"/></entry>"""
        } else {
            ""
        }
        return """<feed xmlns="http://www.w3.org/2005/Atom"><title>Calibre</title>""" +
            """<entry><title>Autoren</title><id>/opds/author</id><link href="/opds/author" type="$NAV"/></entry>""" +
            """<entry><title>Neueste Bücher</title><id>/opds/new</id><link href="/opds/new" type="$NAV"/></entry>""" +
            languages + "</feed>"
    }

    private fun feed(title: String, entries: List<String>, next: String?): String {
        val link = next?.let { """<link rel="next" href="$it" type="$NAV"/>""" }.orEmpty()
        return """<feed xmlns="http://www.w3.org/2005/Atom"><title>$title</title>$link${entries.joinToString("")}</feed>"""
    }

    private fun book(name: String, language: String): String =
        """<entry><title>本$name</title><id>urn:$name</id>""" +
            """<dcterms:language xmlns:dcterms="http://purl.org/dc/terms/">$language</dcterms:language>""" +
            """<link rel="http://opds-spec.org/acquisition" href="/download/$name.epub" type="$EPUB_TYPE"/></entry>"""

    private companion object {
        const val NAV = "application/atom+xml;profile=opds-catalog"
    }
}
