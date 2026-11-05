package app.honmani.reader.data.backup

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import app.honmani.reader.data.library.BookEntity
import app.honmani.reader.data.library.RemovedBookEntity
import app.honmani.reader.data.merge.SyncKeys
import app.honmani.reader.data.merge.TombstoneWriter
import app.honmani.reader.data.merge.UpdateClock
import app.honmani.reader.data.opds.OpdsCatalogEntity
import app.honmani.reader.data.review.ReadDayEntity
import app.honmani.reader.data.review.ReviewCardEntity
import app.honmani.reader.data.review.ReviewLogEntity
import app.honmani.reader.data.library.BookmarkEntity
import app.honmani.reader.data.library.HighlightEntity
import app.honmani.reader.data.library.PronunciationEntity
import app.honmani.reader.data.stats.FinishedBookEntity
import app.honmani.reader.data.stats.ReadingDayEntity
import app.honmani.reader.data.words.KnownBatchEntity
import app.honmani.reader.data.words.KnownBatchWordEntity
import app.honmani.reader.data.words.LookupEventEntity
import app.honmani.reader.data.words.WordEntity
import app.honmani.reader.data.words.WordLookupEntity

/** Reads and merges whole tables for backups; no tables of its own. */
@Dao
interface BackupDao : TombstoneWriter {
    @Query("SELECT * FROM book ORDER BY id")
    suspend fun books(): List<BookEntity>

    @Update
    suspend fun updateBookRow(book: BookEntity)

    /** Stamped now: the restore changed the book's folder, hidden or finished state. */
    @Transaction
    suspend fun updateBook(book: BookEntity) = updateBookRow(book.copy(updatedAt = UpdateClock.now()))

    @Query("SELECT * FROM reading_day ORDER BY book_id, day, device_id")
    suspend fun readingDays(): List<ReadingDayEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putReadingDays(days: List<ReadingDayEntity>)

    @Query("SELECT * FROM lookup_event ORDER BY id")
    suspend fun lookupEvents(): List<LookupEventEntity>

    @Insert
    suspend fun insertLookupEvents(events: List<LookupEventEntity>)

    @Query("SELECT * FROM word ORDER BY id")
    suspend fun words(): List<WordEntity>

    @Query("SELECT * FROM word_lookup ORDER BY word_id, looked_up_at, id")
    suspend fun wordLookups(): List<WordLookupEntity>

    @Query("SELECT * FROM known_batch ORDER BY created_at, id")
    suspend fun knownBatches(): List<KnownBatchEntity>

    @Query("SELECT * FROM known_batch_word ORDER BY batch_id, word_id")
    suspend fun knownBatchWords(): List<KnownBatchWordEntity>

    @Insert
    suspend fun insertKnownBatchRow(batch: KnownBatchEntity): Long

    /** A restored row removes the marker of a deleted one with the same key (as every local insert does). */
    @Transaction
    suspend fun insertKnownBatch(batch: KnownBatchEntity): Long {
        clearTombstone(SyncKeys.KNOWN_BATCH, SyncKeys.knownBatch(batch.source.name, batch.detail, batch.createdAt))
        return insertKnownBatchRow(batch)
    }

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertKnownBatchWords(words: List<KnownBatchWordEntity>)

    @Query("SELECT * FROM review_card ORDER BY word_id")
    suspend fun reviewCards(): List<ReviewCardEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putReviewCardRows(cards: List<ReviewCardEntity>)

    @Query("SELECT * FROM word WHERE id = :id")
    suspend fun wordById(id: Long): WordEntity?

    /** Stamped now, and the marker of a deleted card goes. */
    @Transaction
    suspend fun putReviewCards(cards: List<ReviewCardEntity>) {
        val now = UpdateClock.now()
        for (card in cards) wordById(card.wordId)?.let { clearTombstone(SyncKeys.REVIEW_CARD, SyncKeys.reviewCard(it.expression, it.reading)) }
        putReviewCardRows(cards.map { it.copy(updatedAt = now) })
    }

    @Query("SELECT * FROM review_log ORDER BY word_id, at, id")
    suspend fun reviewLogs(): List<ReviewLogEntity>

    @Insert
    suspend fun insertReviewLogRows(logs: List<ReviewLogEntity>)

    @Transaction
    suspend fun insertReviewLogs(logs: List<ReviewLogEntity>) {
        for (log in logs) wordById(log.wordId)?.let { clearTombstone(SyncKeys.REVIEW_LOG, SyncKeys.reviewLog(it.expression, it.reading, log.at, log.grade)) }
        insertReviewLogRows(logs)
    }

    @Query("SELECT * FROM read_day ORDER BY word_id, day")
    suspend fun readDays(): List<ReadDayEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putReadDays(days: List<ReadDayEntity>)

    @Query("SELECT * FROM removed_book")
    suspend fun removedBooks(): List<RemovedBookEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertRemovedBooks(removed: List<RemovedBookEntity>)

    @Query("SELECT * FROM finished_book ORDER BY content_hash")
    suspend fun finishedBooks(): List<FinishedBookEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertFinishedBookRows(books: List<FinishedBookEntity>)

    @Transaction
    suspend fun insertFinishedBooks(books: List<FinishedBookEntity>) {
        for (book in books) clearTombstone(SyncKeys.FINISHED_BOOK, SyncKeys.finishedBook(book.contentHash))
        insertFinishedBookRows(books)
    }

    @Query("UPDATE finished_book SET finished_at = :finishedAt WHERE content_hash = :contentHash AND finished_at IS NULL")
    suspend fun setFinishedDateIfUnknown(contentHash: String, finishedAt: Long)

    @Query("SELECT * FROM bookmark ORDER BY content_hash, characters, id")
    suspend fun bookmarks(): List<BookmarkEntity>

    @Insert
    suspend fun insertBookmarkRows(bookmarks: List<BookmarkEntity>)

    /** Stamped now, and the marker of a deleted bookmark there goes. */
    @Transaction
    suspend fun insertBookmarks(bookmarks: List<BookmarkEntity>) {
        val now = UpdateClock.now()
        for (b in bookmarks) clearTombstone(SyncKeys.BOOKMARK, SyncKeys.bookmark(b.contentHash, b.characters))
        insertBookmarkRows(bookmarks.map { it.copy(updatedAt = now) })
    }

    @Query("SELECT * FROM highlight ORDER BY content_hash, start, id")
    suspend fun highlights(): List<HighlightEntity>

    @Insert
    suspend fun insertHighlightRows(highlights: List<HighlightEntity>)

    @Transaction
    suspend fun insertHighlights(highlights: List<HighlightEntity>) {
        val now = UpdateClock.now()
        for (h in highlights) clearTombstone(SyncKeys.HIGHLIGHT, SyncKeys.highlight(h.contentHash, h.start, h.end))
        insertHighlightRows(highlights.map { it.copy(updatedAt = now) })
    }

    @Query("SELECT * FROM pronunciation ORDER BY content_hash, id")
    suspend fun pronunciations(): List<PronunciationEntity>

    @Insert
    suspend fun insertPronunciationRows(pronunciations: List<PronunciationEntity>)

    @Transaction
    suspend fun insertPronunciations(pronunciations: List<PronunciationEntity>) {
        val now = UpdateClock.now()
        for (p in pronunciations) clearTombstone(SyncKeys.PRONUNCIATION, SyncKeys.pronunciation(p.contentHash, p.text))
        insertPronunciationRows(pronunciations.map { it.copy(updatedAt = now) })
    }

    @Query("SELECT * FROM opds_catalog ORDER BY added_at, id")
    suspend fun catalogs(): List<OpdsCatalogEntity>

    @Insert
    suspend fun insertCatalog(catalog: OpdsCatalogEntity): Long

    // A restored book is back in the library: its statistics and lookups move to its new id.
    @Query("UPDATE OR REPLACE reading_day SET book_id = :to WHERE book_id = :from")
    suspend fun moveReadingDays(from: Long, to: Long)

    @Query("UPDATE lookup_event SET book_id = :to WHERE book_id = :from")
    suspend fun moveLookupEvents(from: Long, to: Long)

    @Query("UPDATE word_lookup SET book_id = :to WHERE book_id = :from")
    suspend fun moveWordLookups(from: Long, to: Long)
}
