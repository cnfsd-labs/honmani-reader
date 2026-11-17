package app.honmani.reader.ui.reader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import app.honmani.reader.AppContainer
import app.honmani.reader.data.library.BookEntity
import app.honmani.reader.data.library.BookmarkEntity
import app.honmani.reader.data.library.HighlightEntity
import app.honmani.reader.data.library.PronunciationEntity
import kotlinx.coroutines.flow.flowOf

/** Which of the reader's sheets, dialogs and sub-screens are open; kept over a rotation. */
@Stable
internal class ReaderOverlays(bookmarkDraft: MutableState<BookmarkDraft?>) {
    /** Honmani's reader settings, from the Settings tab or the reader's top bar. */
    var readerSettings by mutableStateOf(false)

    /** The open sub-screen came from the reader menu: Back returns there (a tap outside or a swipe down closes to the book). */
    var openedFromMenu by mutableStateOf(false)

    /** ttu's table of contents also opens from its own bar, so the menu's Contents keeps its own mark. */
    var contentsFromMenu by mutableStateOf(false)

    /** The words of this reading session, from the reader's bottom menu. */
    var sessionShown by mutableStateOf(false)

    /** The search in the open book, from the reader's bottom menu. */
    var searchShown by mutableStateOf(false)

    /** The open book's bookmark list, and a bookmark being named. */
    var bookmarksShown by mutableStateOf(false)
    var bookmarkDraft by bookmarkDraft

    /**
     * A place to jump to once the book being opened from Words or a review card ("Open in book") is
     * on screen: library book id to ttu's character count. Not kept over a rotation (the jump would
     * come too late to matter).
     */
    var openAt by mutableStateOf<Pair<Long, Long>?>(null)

    /** The open book's highlight list (reader menu). */
    var highlightsShown by mutableStateOf(false)
    var pronunciationsShown by mutableStateOf(false)

    /** The audiobook's dialog (its files are chosen with Android's own picker), and the one that makes one with a Google voice. */
    var audiobookStep by mutableStateOf<AudiobookStep?>(null)
    var makeShown by mutableStateOf(false)

    /** The Dictionaries screen, under Settings; lookups without a dictionary open it directly. */
    var dictionariesShown by mutableStateOf(false)

    /** The review screen, from Words or the launcher's "Review" shortcut. */
    var reviewShown by mutableStateOf(false)

    companion object {
        fun saver(bookmarkDraft: MutableState<BookmarkDraft?>) = listSaver<ReaderOverlays, Any?>(
            save = {
                listOf(
                    it.readerSettings, it.openedFromMenu, it.contentsFromMenu, it.sessionShown, it.searchShown,
                    it.bookmarksShown, it.highlightsShown, it.pronunciationsShown, it.audiobookStep, it.makeShown,
                    it.dictionariesShown, it.reviewShown,
                )
            },
            restore = { saved ->
                ReaderOverlays(bookmarkDraft).apply {
                    readerSettings = saved[0] as Boolean
                    openedFromMenu = saved[1] as Boolean
                    contentsFromMenu = saved[2] as Boolean
                    sessionShown = saved[3] as Boolean
                    searchShown = saved[4] as Boolean
                    bookmarksShown = saved[5] as Boolean
                    highlightsShown = saved[6] as Boolean
                    pronunciationsShown = saved[7] as Boolean
                    audiobookStep = saved[8] as AudiobookStep?
                    makeShown = saved[9] as Boolean
                    dictionariesShown = saved[10] as Boolean
                    reviewShown = saved[11] as Boolean
                }
            },
        )
    }
}

@Composable
internal fun rememberReaderOverlays(): ReaderOverlays {
    val bookmarkDraft = rememberSaveable(stateSaver = BookmarkDraft.Saver) { mutableStateOf<BookmarkDraft?>(null) }
    return rememberSaveable(saver = ReaderOverlays.saver(bookmarkDraft)) { ReaderOverlays(bookmarkDraft) }
}

/** The book ttu has open, and what the library keeps for it. */
@Stable
internal class CurrentBook(
    ttuId: MutableState<Long?>,
    hash: State<String?>,
    book: State<BookEntity?>,
    bookmarks: State<List<BookmarkEntity>>,
    highlights: State<List<HighlightEntity>>,
    pronunciations: State<List<PronunciationEntity>>,
    sectionsTotal: State<Int?>,
) {
    /** ttu's id of the book; null at ttu's book list. */
    var ttuId by ttuId
    val hash by hash
    val book by book
    val bookmarks by bookmarks
    val highlights by highlights

    /** The book's "read this as that" list. */
    val pronunciations by pronunciations

    /** The length of the book's section table, which the page needs to draw highlights and the lookup
     * panel to save one (null for a book that is not an EPUB: no highlighting there). */
    val sectionsTotal by sectionsTotal
}

@Composable
internal fun rememberCurrentBook(container: AppContainer): CurrentBook {
    val ttuId = rememberSaveable { mutableStateOf<Long?>(null) }
    val openTtuId = ttuId.value
    val hash = produceState<String?>(null, openTtuId) {
        value = openTtuId?.let { container.bookDao.findByTtuId(it)?.contentHash }
    }
    val bookHash = hash.value
    val bookmarks = remember(bookHash) {
        bookHash?.let { container.bookmarkDao.observe(it) } ?: flowOf(emptyList())
    }.collectAsState(emptyList())
    val highlights = remember(bookHash) {
        bookHash?.let { container.highlightDao.observe(it) } ?: flowOf(emptyList())
    }.collectAsState(emptyList())
    val pronunciations = remember(bookHash) {
        bookHash?.let { container.pronunciationDao.observe(it) } ?: flowOf(emptyList())
    }.collectAsState(emptyList())
    val sectionsTotal = produceState<Int?>(null, openTtuId) {
        value = openTtuId?.let { id -> container.bookDao.findByTtuId(id)?.let { container.sectionStarts(it)?.total } }
    }
    val book = produceState<BookEntity?>(null, openTtuId) { value = openTtuId?.let { container.bookDao.findByTtuId(it) } }
    return remember { CurrentBook(ttuId, hash, book, bookmarks, highlights, pronunciations, sectionsTotal) }
}
