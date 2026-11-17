package app.honmani.reader.data.opds

import java.net.URI

/**
 * The "Japanese books" shortcut of a catalogue's start page: the server's own feed of Japanese
 * books if it has one, else its newest books with everything not Japanese left out.
 *
 * Calibre-Web (and Calibre-Web Automated) lists its languages at `/opds/language` (start feed entry
 * "Languages", localised); that feed has one entry per language, titled with the language's name in
 * the server's language ("Japanese", "Japanisch", "日本語", …) and linking `/opds/language/<id>`,
 * where the id is a number from Calibre's database, not a language code (janeczku/calibre-web,
 * `cps/opds.py` `feed_languagesindex` / `feed_languages`, `cps/templates/index.xml` and
 * `feed.xml`, commit 6e840ac). So the languages feed is found by its address and Japanese by its
 * title, or by a language code where a server uses one.
 */
object JapaneseBooks {
    /** Where the shortcut leads. */
    sealed interface Source {
        /** The server's feed of Japanese books, shown as it is. */
        data class LanguageFeed(val url: String) : Source

        /** A feed of all books (newest first), shown with only its Japanese books. */
        data class Filtered(val url: String) : Source
    }

    /** Books of a filtered feed read in one go, and the page to read next. */
    data class FilteredPage(
        val entries: List<OpdsEntry>,
        val nextUrl: String?,
        /** [MAX_PAGES_PER_LOAD] pages were read without a single Japanese book. */
        val foundNone: Boolean,
    )

    /** One load of a filtered feed stops once it has this many books… */
    const val MIN_ENTRIES = 20

    /** …or read this many pages (Calibre-Web: 60 books each). */
    const val MAX_PAGES_PER_LOAD = 10

    /** A languages feed is read up to this many pages to find Japanese. */
    private const val MAX_LANGUAGE_PAGES = 3

    /**
     * Whether the shortcut can lead anywhere: the start feed links a languages feed or a feed of
     * books. Decided from the start feed alone, without a request.
     */
    fun available(root: OpdsFeed, rootUrl: String): Boolean =
        languagesUrl(root) != null || OpdsAutoImport.newestBooksUrl(root, rootUrl) != null

    /**
     * Finds where the shortcut leads; null if nowhere. [load] reads a feed. A languages feed the
     * server refuses or that has no Japanese falls back to the filtered newest books; a connection
     * or login problem is thrown.
     */
    suspend fun find(root: OpdsFeed, rootUrl: String, load: suspend (String) -> OpdsFeed): Source? {
        languagesUrl(root)?.let { url ->
            try {
                japaneseFeedUrl(url, load)?.let { return Source.LanguageFeed(it) }
            } catch (e: OpdsException) {
                // Calibre-Web answers 404 when the languages list is hidden for this user.
                if (e.reason != OpdsException.Reason.SERVER && e.reason != OpdsException.Reason.NOT_A_CATALOGUE) throw e
            }
        }
        return OpdsAutoImport.newestBooksUrl(root, rootUrl)?.let { Source.Filtered(it) }
    }

    /** The start feed's entry for the list of languages, by its address (titles are localised). */
    internal fun languagesUrl(root: OpdsFeed): String? = root.entries.firstNotNullOfOrNull { entry ->
        entry.feedUrl?.takeIf { url ->
            // `/opds/language`, not `/opds/language/3` (one language).
            pathSegments(url).lastOrNull()?.lowercase()?.startsWith("language") == true
        }
    }

    /** Reads the languages feed at [url] (and its next pages) for Japanese's feed. */
    private suspend fun japaneseFeedUrl(url: String, load: suspend (String) -> OpdsFeed): String? {
        var next: String? = url
        var pages = 0
        while (next != null && pages < MAX_LANGUAGE_PAGES) {
            val feed = load(next)
            pages++
            japaneseEntry(feed.entries)?.let { return it.feedUrl }
            next = feed.nextUrl?.takeIf { it != next }
        }
        return null
    }

    /** The entry for Japanese among a languages feed's entries: an exact name or code first, then a near one. */
    internal fun japaneseEntry(entries: List<OpdsEntry>): OpdsEntry? {
        val languages = entries.filter { it.feedUrl != null }
        return languages.firstOrNull { name(it.title) in JAPANESE_NAMES || hasJapaneseCode(it) }
            ?: languages.firstOrNull { entry -> name(entry.title).let { title -> JAPANESE_STEMS.any { it in title } } }
    }

    private fun name(title: String) = title.trim().lowercase()

    /** An address or id ending in a language code (`…/jpn`, `…=ja`), as some servers use. */
    private fun hasJapaneseCode(entry: OpdsEntry): Boolean {
        val fromUrl = entry.feedUrl?.let { pathSegments(it).lastOrNull() }
        val fromQuery = entry.feedUrl?.substringAfterLast('=', "")
        val fromId = entry.id.substringAfterLast('/').substringAfterLast(':').substringAfterLast('=')
        return listOfNotNull(fromUrl, fromQuery, fromId).any { it.lowercase() in JAPANESE_CODES }
    }

    /**
     * Reads the feed at [url] and its next pages, keeping the Japanese books, until it has
     * [MIN_ENTRIES] of them or read [MAX_PAGES_PER_LOAD] pages.
     */
    suspend fun loadFiltered(url: String, load: suspend (String) -> OpdsFeed): FilteredPage {
        val entries = LinkedHashMap<String, OpdsEntry>()
        var next: String? = url
        var pages = 0
        while (next != null && pages < MAX_PAGES_PER_LOAD && entries.size < MIN_ENTRIES) {
            val current = next
            val feed = load(current)
            pages++
            for (entry in feed.entries) {
                if (entry.acquisitions.isNotEmpty() && entry.isJapanese) entries.putIfAbsent(entry.id, entry)
            }
            next = feed.nextUrl?.takeIf { it != current }
        }
        return FilteredPage(entries.values.toList(), next, foundNone = entries.isEmpty() && next != null)
    }

    private fun pathSegments(url: String): List<String> = try {
        URI(url).path.orEmpty().split('/').filter { it.isNotEmpty() }
    } catch (_: Exception) {
        emptyList()
    }

    private val JAPANESE_CODES = setOf("ja", "jpn", "ja-jp", "ja_jp")

    /** "Japanese" in the languages Calibre-Web is translated into, and Japanese's own name. */
    private val JAPANESE_NAMES = setOf(
        "japanese", "日本語", "japanisch", "japonais", "japonés", "japonês", "giapponese", "japans",
        "japansk", "japanska", "japanin", "japán", "japoński", "japonština", "japončina", "japonca",
        "японский", "японська", "日语", "日語", "日文", "일본어", "tiếng nhật", "bahasa jepang",
        "ja", "jpn",
    )

    /** Stems of those names, for forms with a suffix or case ("Japanese (Japan)", "japonesa"). */
    private val JAPANESE_STEMS = listOf("japan", "japon", "japón", "giappon", "japán", "日本", "япон", "일본")
}
