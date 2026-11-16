package app.honmani.reader.ui.reader

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.honmani.reader.AppContainer
import app.honmani.reader.R
import app.honmani.reader.data.library.BookText
import app.honmani.reader.data.library.BookmarkEntity
import app.honmani.reader.data.library.HighlightEntity
import app.honmani.reader.data.library.PronunciationEntity
import app.honmani.reader.data.words.SessionWords
import app.honmani.reader.readaloud.PronunciationRules
import app.honmani.reader.ui.words.SessionSheet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The sheets of the open book that the reader menu leads to: a bookmark being named, bookmarks,
 * highlights, pronunciations, search and the words of this session. [backToMenu] gives a sheet's
 * Back when it came from the menu.
 */
@Composable
internal fun ReaderBookSheets(
    overlays: ReaderOverlays,
    current: CurrentBook,
    container: AppContainer,
    scope: CoroutineScope,
    jumpSnackbar: SnackbarHostState,
    jumper: PlaceJumper,
    bookText: BookText?,
    atLibrary: Boolean,
    backToMenu: (() -> Unit) -> (() -> Unit)?,
    openMenu: () -> Unit,
) {
    val resources = LocalResources.current
    val jumpMessage = { percent: Int -> resources.getString(R.string.jump_back_message, percent) }
    val backLabel = stringResource(R.string.jump_back_action)
    overlays.bookmarkDraft?.let { draft ->
        val added = stringResource(R.string.bookmark_added)
        BookmarkNameDialog(
            title = stringResource(R.string.bookmark_dialog_title),
            initial = draft.name,
            onSave = { name ->
                overlays.bookmarkDraft = null
                val hash = current.hash
                if (hash != null) {
                    scope.launch {
                        container.bookmarkDao.put(
                            BookmarkEntity(contentHash = hash, name = name, characters = draft.characters, total = draft.total, createdAt = System.currentTimeMillis()),
                        )
                        jumpSnackbar.currentSnackbarData?.dismiss()
                        jumpSnackbar.showSnackbar(added)
                    }
                }
            },
            onDismiss = { overlays.bookmarkDraft = null },
        )
    }
    if (overlays.bookmarksShown && !atLibrary) {
        BookmarksSheet(
            bookmarks = current.bookmarks,
            onOpen = { bookmark ->
                overlays.bookmarksShown = false
                val id = current.ttuId
                if (id != null) {
                    val text = bookText
                    val spot = text?.aroundPageStart(bookmark.characters.toInt())
                    jumper.jump(
                        id, bookmark.characters, bookmark.total, spot?.hint.orEmpty(), spot?.match.orEmpty(), text,
                        jumpMessage(bookmark.percent), backLabel,
                    )
                }
            },
            onRename = { bookmark, name -> scope.launch { container.bookmarkDao.rename(bookmark.id, name) } },
            onDelete = { bookmark -> scope.launch { container.bookmarkDao.delete(bookmark.id) } },
            onUndoDelete = { bookmark -> scope.launch { container.bookmarkDao.put(bookmark) } },
            onDismiss = { overlays.bookmarksShown = false },
            onBack = backToMenu { overlays.bookmarksShown = false },
        )
    }
    if (overlays.highlightsShown && !atLibrary) {
        HighlightsSheet(
            highlights = current.highlights,
            onOpen = { highlight ->
                overlays.highlightsShown = false
                val id = current.ttuId
                if (id != null) {
                    val text = bookText
                    val spot = text?.aroundPageStart(highlight.start.toInt())
                    jumper.jump(
                        id, highlight.start, highlight.total, spot?.hint.orEmpty(), spot?.match.orEmpty(), text,
                        jumpMessage(highlight.percent), backLabel,
                    )
                }
            },
            onDelete = { highlight -> scope.launch { container.highlightDao.delete(highlight.id) } },
            onUndoDelete = { highlight -> scope.launch { container.highlightDao.put(highlight) } },
            onDismiss = { overlays.highlightsShown = false },
            onBack = backToMenu { overlays.highlightsShown = false },
        )
    }
    if (overlays.pronunciationsShown && !atLibrary) {
        val hash = current.hash
        val corrected by produceState(emptyList<Pair<String, String>>(), overlays.pronunciationsShown) {
            value = container.wordRepository.withCorrectedFurigana().mapNotNull { PronunciationRules.fromFurigana(it.expression, it.furigana) }
        }
        PronunciationsSheet(
            items = current.pronunciations,
            suggestions = corrected.filter { (text, _) -> current.pronunciations.none { it.text == text } },
            onSave = { text, reading ->
                if (hash != null) scope.launch { container.pronunciationDao.put(PronunciationEntity(contentHash = hash, text = text, reading = reading, createdAt = System.currentTimeMillis())) }
            },
            onDelete = { item -> scope.launch { container.pronunciationDao.delete(item.id) } },
            onUndoDelete = { item -> scope.launch { container.pronunciationDao.put(item) } },
            onDismiss = { overlays.pronunciationsShown = false },
            onBack = backToMenu { overlays.pronunciationsShown = false },
        )
    }
    val searchedBook = current.ttuId
    if (overlays.searchShown && searchedBook != null && !atLibrary) {
        BookSearchScreen(
            loadText = { container.searchableBook(searchedBook) },
            onOpenHit = { hit, total ->
                overlays.searchShown = false
                jumper.jump(
                    searchedBook, hit.characters.toLong(), total.toLong(), hit.hint, hit.match, null,
                    resources.getString(R.string.jump_back_message, hit.percent), backLabel,
                )
            },
            onClose = { overlays.searchShown = false; if (overlays.openedFromMenu) openMenu() },
        )
    }
    if (overlays.sessionShown) {
        val session by container.wordRepository.session.collectAsStateWithLifecycle(SessionWords(null, emptyList()))
        SessionSheet(session, onDismiss = { overlays.sessionShown = false }, onBack = backToMenu { overlays.sessionShown = false })
    }
}

/**
 * What the lookup panel offers for highlighting a tap: the colours of a new selection (when
 * selection lookup is on), or of the highlight the tap landed on, with "Remove highlight".
 */
@Composable
internal fun rememberHighlightControls(
    current: CurrentBook,
    container: AppContainer,
    scope: CoroutineScope,
    jumpSnackbar: SnackbarHostState,
    selectionLookup: Boolean,
    dismiss: () -> Unit,
): (TapLookup) -> HighlightControls? {
    val savedMessage = stringResource(R.string.highlight_saved)
    val removedMessage = stringResource(R.string.highlight_removed)
    val undoLabel = stringResource(R.string.action_undo)
    fun highlightControls(tap: TapLookup): HighlightControls? {
        val existing = tap.highlightId?.let { id -> current.highlights.firstOrNull { it.id == id } }
        if (existing != null) {
            return HighlightControls(
                current = existing.color,
                onPick = { color, _ -> scope.launch { container.highlightDao.setColor(existing.id, color) } },
                onRemove = {
                    dismiss()
                    scope.launch {
                        container.highlightDao.delete(existing.id)
                        jumpSnackbar.currentSnackbarData?.dismiss()
                        val result = jumpSnackbar.showSnackbar(removedMessage, undoLabel, duration = SnackbarDuration.Long)
                        if (result == SnackbarResult.ActionPerformed) container.highlightDao.put(existing)
                    }
                },
            )
        }
        val extents = tap.selected
        val hash = current.hash
        val total = current.sectionsTotal
        if (!tap.bySelection || extents == null || hash == null || total == null || !selectionLookup) return null
        return HighlightControls(
            current = null,
            extents = extents.available.mapValues { it.value.text },
            rememberedExtent = container.highlightSettings.extent.value,
            onExtentChosen = { container.highlightSettings.setExtent(it.name) },
            onPick = { color, extent ->
                val passage = extents[extent]
                dismiss()
                scope.launch {
                    val same = current.highlights.firstOrNull { it.start == passage.start && it.end == passage.end }
                    jumpSnackbar.currentSnackbarData?.dismiss()
                    if (same != null) {
                        container.highlightDao.setColor(same.id, color)
                        jumpSnackbar.showSnackbar(savedMessage)
                    } else {
                        val saved = container.highlightDao.saveReplacingOverlaps(
                            HighlightEntity(
                                contentHash = hash, start = passage.start, end = passage.end, color = color,
                                text = passage.text, total = total.toLong(), createdAt = System.currentTimeMillis(),
                            ),
                        )
                        val result = jumpSnackbar.showSnackbar(savedMessage, undoLabel, duration = SnackbarDuration.Long)
                        if (result == SnackbarResult.ActionPerformed) container.highlightDao.undoSave(saved)
                    }
                }
            },
            onRemove = null,
        )
    }
    return ::highlightControls
}
