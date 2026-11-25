package app.honmani.reader.data

import app.honmani.reader.data.dictionary.DictionaryEntity
import app.honmani.reader.data.dictionary.TermMetaEntity
import app.honmani.reader.data.library.BookFormat
import app.honmani.reader.data.library.BookSource
import app.honmani.reader.data.merge.SyncKeys
import app.honmani.reader.data.review.ReviewState
import app.honmani.reader.data.words.KnownSource
import app.honmani.reader.data.words.LookupOrigin
import app.honmani.reader.data.words.WordScheduler
import app.honmani.reader.data.words.WordStatus
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Names kept as text in the database, in backups and in sync files. Renaming or removing one breaks
 * readers' data (Room crashes on an unknown name), so it needs a migration; a new one may be added
 * to the list here.
 */
class StoredNamesTest {
    private inline fun <reified E : Enum<E>> names() = enumValues<E>().joinToString(" ") { it.name }

    @Test
    fun theNamesOfStoredEnumsStay() {
        assertEquals("LEARNING LEARNED", names<WordStatus>())
        assertEquals("HONMANI ANKI", names<WordScheduler>())
        assertEquals("READER SEARCH REVIEW PREPARE", names<LookupOrigin>())
        assertEquals("ANKI_DECK WORD_LIST TOP_N WANIKANI QUIZ", names<KnownSource>())
        assertEquals("NEW REVIEW SUSPENDED", names<ReviewState>())
        assertEquals("EPUB TXT", names<BookFormat>())
        assertEquals("FILE OPDS FOLDER", names<BookSource>())
        assertEquals("FREQUENCY PITCH", names<TermMetaEntity.Kind>())
        assertEquals("IMPORTING READY", names<DictionaryEntity.Status>())
    }

    @Test
    fun theKindsOfDeletionMarkersStay() {
        assertEquals(
            "word word_lookup lookup_event review_card review_log bookmark highlight pronunciation folder known_batch finished_book",
            listOf(
                SyncKeys.WORD, SyncKeys.WORD_LOOKUP, SyncKeys.LOOKUP_EVENT, SyncKeys.REVIEW_CARD, SyncKeys.REVIEW_LOG, SyncKeys.BOOKMARK,
                SyncKeys.HIGHLIGHT, SyncKeys.PRONUNCIATION, SyncKeys.FOLDER, SyncKeys.KNOWN_BATCH, SyncKeys.FINISHED_BOOK,
            ).joinToString(" "),
        )
    }
}
