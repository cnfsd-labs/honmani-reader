package app.honmani.reader.data.words

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A word the reader has looked up: a dictionary form and reading, with how often and how
 * recently it was looked up. Words are recorded automatically; see [WordRepository.record].
 */
@Entity(
    tableName = "word",
    indices = [
        Index(value = ["expression", "reading"], unique = true),
        Index(value = ["rank_key"]),
    ],
)
data class WordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val expression: String,
    val reading: String,
    /** The first sense from the dictionary, kept for the word list (dictionaries may change). */
    val gloss: String,
    val status: WordStatus = WordStatus.LEARNING,
    val starred: Boolean = false,
    /**
     * "Ignore word": a name or a wrong split of the book's text. It stays saved (with its status,
     * so un-ignoring restores it) but is left out of marks, lists, review and every count.
     */
    val ignored: Boolean = false,
    @ColumnInfo(name = "lookup_count") val lookupCount: Int,
    @ColumnInfo(name = "first_looked_up_at") val firstLookedUpAt: Long,
    @ColumnInfo(name = "last_looked_up_at") val lastLookedUpAt: Long,
    /** Orders words by their decayed lookup score; see [WordScore]. */
    @ColumnInfo(name = "rank_key") val rankKey: Double,
    /** The latest lookup's sentence; the looked-up text is [lastSurfaceStart] until [lastSurfaceEnd]. */
    @ColumnInfo(name = "last_sentence") val lastSentence: String,
    @ColumnInfo(name = "last_surface_start") val lastSurfaceStart: Int,
    @ColumnInfo(name = "last_surface_end") val lastSurfaceEnd: Int,
    @ColumnInfo(name = "last_book_title") val lastBookTitle: String? = null,
    /** The AnkiDroid note made from this word, if one was added. */
    @ColumnInfo(name = "anki_note_id") val ankiNoteId: Long? = null,
    /** Who schedules reviews of this word; Anki words never enter Honmani's review queue. */
    @ColumnInfo(defaultValue = "'HONMANI'") val scheduler: WordScheduler = WordScheduler.HONMANI,
)

enum class WordScheduler {
    HONMANI,

    /** Set when the word is sent to Anki ([WordEntity.ankiNoteId]); reviews, reads and lookups leave its card alone. */
    ANKI,
}

/** Where a lookup came from; only [READER] lookups count as evidence against a review card. */
enum class LookupOrigin {
    READER,
    SEARCH,
    REVIEW,

    /** Not a lookup: a word added with its sentence to learn before reading a book. */
    PREPARE,
}

enum class WordStatus {
    LEARNING,

    /**
     * Marked as learned by the reader; kept, but out of the main list. Only words the reader
     * has looked up can be learned, so this is not the reader's whole vocabulary.
     */
    LEARNED,
}

/** One lookup of a [WordEntity]: where it was read. The latest few per word are kept. */
@Entity(
    tableName = "word_lookup",
    foreignKeys = [
        ForeignKey(
            entity = WordEntity::class,
            parentColumns = ["id"],
            childColumns = ["word_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["word_id", "looked_up_at"])],
)
data class WordLookupEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "word_id") val wordId: Long,
    /** The text as written in the book, such as an inflected form. */
    val surface: String,
    val sentence: String,
    @ColumnInfo(name = "surface_start") val surfaceStart: Int,
    @ColumnInfo(name = "surface_end") val surfaceEnd: Int,
    /** The library book, if known; no foreign key, so lookups outlive a removed book. */
    @ColumnInfo(name = "book_id") val bookId: Long? = null,
    @ColumnInfo(name = "book_title") val bookTitle: String? = null,
    @ColumnInfo(name = "looked_up_at") val lookedUpAt: Long,
    @ColumnInfo(defaultValue = "'READER'") val origin: LookupOrigin = LookupOrigin.READER,
)

/**
 * That a lookup was counted, for statistics. Unlike [WordLookupEntity] these are never pruned
 * and stay when the word is deleted, so every lookup counts.
 */
@Entity(
    tableName = "lookup_event",
    indices = [Index(value = ["looked_up_at"]), Index(value = ["book_id"])],
)
data class LookupEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "looked_up_at") val lookedUpAt: Long,
    /** The library book, if known; no foreign key, like [WordLookupEntity.bookId]. */
    @ColumnInfo(name = "book_id") val bookId: Long? = null,
)
