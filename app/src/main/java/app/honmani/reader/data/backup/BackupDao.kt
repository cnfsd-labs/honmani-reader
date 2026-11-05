package app.honmani.reader.data.backup

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import app.honmani.reader.data.library.BookEntity
import app.honmani.reader.data.library.RemovedBookEntity
import app.honmani.reader.data.opds.OpdsCatalogEntity
import app.honmani.reader.data.review.ReadDayEntity
import app.honmani.reader.data.review.ReviewCardEntity
import app.honmani.reader.data.review.ReviewLogEntity
import app.honmani.reader.data.library.BookmarkEntity
import app.honmani.reader.data.library.HighlightEntity
import app.honmani.reader.data.stats.FinishedBookEntity
import app.honmani.reader.data.stats.ReadingDayEntity
import app.honmani.reader.data.words.KnownBatchEntity
import app.honmani.reader.data.words.KnownBatchWordEntity
import app.honmani.reader.data.words.LookupEventEntity
import app.honmani.reader.data.words.WordEntity
import app.honmani.reader.data.words.WordLookupEntity

/** Reads and merges whole tables for backups; no tables of its own. */
@Dao
interface BackupDao {
    @Query("SELECT * FROM book ORDER BY id")
    suspend fun books(): List<BookEntity>

    @Update
    suspend fun updateBook(book: BookEntity)

    @Query("SELECT * FROM reading_day ORDER BY book_id, day")
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
    suspend fun insertKnownBatch(batch: KnownBatchEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertKnownBatchWords(words: List<KnownBatchWordEntity>)

    @Query("SELECT * FROM review_card ORDER BY word_id")
    suspend fun reviewCards(): List<ReviewCardEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putReviewCards(cards: List<ReviewCardEntity>)

    @Query("SELECT * FROM review_log ORDER BY word_id, at, id")
    suspend fun reviewLogs(): List<ReviewLogEntity>

    @Insert
    suspend fun insertReviewLogs(logs: List<ReviewLogEntity>)

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
    suspend fun insertFinishedBooks(books: List<FinishedBookEntity>)

    @Query("UPDATE finished_book SET finished_at = :finishedAt WHERE content_hash = :contentHash AND finished_at IS NULL")
    suspend fun setFinishedDateIfUnknown(contentHash: String, finishedAt: Long)

    @Query("SELECT * FROM bookmark ORDER BY content_hash, characters, id")
    suspend fun bookmarks(): List<BookmarkEntity>

    @Insert
    suspend fun insertBookmarks(bookmarks: List<BookmarkEntity>)

    @Query("SELECT * FROM highlight ORDER BY content_hash, start, id")
    suspend fun highlights(): List<HighlightEntity>

    @Insert
    suspend fun insertHighlights(highlights: List<HighlightEntity>)

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
