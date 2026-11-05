package app.honmani.reader.data.backup

import app.honmani.reader.data.TooLargeException
import app.honmani.reader.data.readBytesAtMost
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.serialization.SerialName
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * Everything a reader wants back after installing Honmani again, except the books themselves:
 * settings, reading progress and statistics, saved words, word progress, review cards and logs, and milestones. Books are
 * named by their content hash, so their progress and statistics find them again once the same
 * file is back in the library. Nothing tied to this phone is in it: no passwords, keys, sync
 * account or AnkiDroid note types.
 *
 * Stored as one JSON file in a zip ([BackupCodec]); [version] goes up when a reader of the old
 * format would misread the new one.
 */
@Serializable
data class BackupFile(
    val format: String = FORMAT,
    val version: Int = VERSION,
    /** When it was made, epoch milliseconds. */
    val exportedAt: Long,
    /** The app's choices (the `settings` preferences): key to a typed value. */
    val settings: Map<String, BackupValue> = emptyMap(),
    /** ッツ Ebook Reader's reader settings (its localStorage keys); null if the reader didn't answer. */
    val readerSettings: Map<String, String?>? = null,
    /** Milestones reached, with the day each was first seen (`yyyy-MM-dd`). */
    val milestones: Map<String, String> = emptyMap(),
    val books: List<BackupBook> = emptyList(),
    /** The library folders by name (a book's folder is [BackupBookDetails.folder]); also the empty ones. */
    val folders: List<String> = emptyList(),
    /**
     * The parts of each book really read (`ReadStretches`), by content hash: ascending steps
     * `from, to`, also for books no longer in the library (they count again if added again).
     */
    val stretches: Map<String, List<Int>> = emptyMap(),
    /** Catalogue books the reader removed, so automatic imports don't bring them back. */
    val removedBooks: List<BackupRemovedBook> = emptyList(),
    val readingDays: List<BackupReadingDay> = emptyList(),
    /** Finished books removed from the library, so the finished counts keep them. */
    val finishedBooks: List<BackupFinishedBook> = emptyList(),
    /** Named places in books, by content hash. */
    val bookmarks: List<BackupBookmark> = emptyList(),
    /** Highlighted passages in books, by content hash. */
    val highlights: List<BackupHighlight> = emptyList(),
    /** Read aloud "read this as that" replacements, by content hash. */
    val pronunciations: List<BackupPronunciation> = emptyList(),
    val lookupEvents: List<BackupLookupEvent> = emptyList(),
    val words: List<BackupWord> = emptyList(),
    /** The batches of Known words the reader can still undo ("Tell Honmani what you already know"). */
    val knownBatches: List<BackupKnownBatch> = emptyList(),
    /** Book servers, without their passwords. */
    val catalogs: List<BackupCatalog> = emptyList(),
    /** Sync files only (see [SyncFolder]): 1 for the first format; null in a backup. A newer number is skipped. */
    val syncFormat: Int? = null,
    /** Sync files only: the installation id of the phone that wrote the file. */
    val deviceId: String? = null,
    /** Sync files only: the phone's model, for the status line. */
    val deviceName: String? = null,
    /** Sync files only: rows deleted on the writing phone, so other phones delete them too. */
    val tombstones: List<BackupTombstone> = emptyList(),
    /** Sync files only: when each folder in [folders] was made or renamed to that name, by name. */
    val folderTimes: Map<String, Long> = emptyMap(),
    /** Sync files only: the few settings that follow the reader across phones (see [SyncSettings]). */
    val syncSettings: Map<String, BackupSyncSetting> = emptyMap(),
) {
    companion object {
        const val FORMAT = "honmani-backup"
        const val VERSION = 1
    }
}

/** A deletion marker (`TombstoneEntity`) in a sync file. */
@Serializable
data class BackupTombstone(val kind: String, val key: String, val deletedAt: Long)

/** A setting's value and when it was changed. */
@Serializable
data class BackupSyncSetting(val value: Int, val at: Long)

/** A shared preference's value with its type, so it is written back as the same type. */
@Serializable
data class BackupValue(val type: String, val value: JsonElement) {
    companion object {
        const val STRING = "string"
        const val BOOLEAN = "boolean"
        const val INT = "int"
        const val LONG = "long"
        const val FLOAT = "float"
        const val STRING_SET = "stringSet"
    }
}

/**
 * A book of the library, or one whose statistics are kept without it. [key] is how the other
 * entries name it: its content hash, or for a book no longer in the library (whose file isn't
 * known any more) a key of its own. [book] is null for those.
 */
@Serializable
data class BackupBook(
    val key: String,
    val book: BackupBookDetails? = null,
)

@Serializable
data class BackupBookDetails(
    val title: String,
    val author: String? = null,
    val format: String,
    val source: String,
    val sourceId: String? = null,
    val addedAt: Long,
    val lastOpenedAt: Long? = null,
    val progress: Float = 0f,
    /** Characters read up to the reading position. */
    val characters: Long = 0,
    val finishedAt: Long? = null,
    val hidden: Boolean = false,
    /** The name of its library folder, if it was in one; a folder of that name on the phone is reused. */
    val folder: String? = null,
    /** Its difficulty when it was graded, for the skill until the book is back; 0 if not graded. */
    val score: Int = 0,
    /** When a synced field last changed (`BookEntity.updatedAt`); 0 in older backups. */
    val updatedAt: Long = 0,
)

@Serializable
data class BackupRemovedBook(val source: String, val sourceId: String, val removedAt: Long)

/** A finished book no longer in the library, by content hash. */
@Serializable
data class BackupFinishedBook(val hash: String, val title: String, val finishedAt: Long? = null)

/** A named place in a book, by content hash. */
@Serializable
data class BackupBookmark(val hash: String, val name: String, val characters: Long, val total: Long, val createdAt: Long = 0, val updatedAt: Long = 0)

/** A highlighted passage in a book, by content hash; the range is in ttu's character counting. */
@Serializable
data class BackupHighlight(val hash: String, val start: Long, val end: Long, val color: Int, val text: String, val total: Long, val createdAt: Long = 0, val updatedAt: Long = 0)

/** A Read aloud replacement in a book, by content hash. */
@Serializable
data class BackupPronunciation(val hash: String, val text: String, val reading: String, val createdAt: Long = 0, val updatedAt: Long = 0)

@Serializable
data class BackupReadingDay(
    /** A [BackupBook.key]. */
    val book: String,
    val day: String,
    val seconds: Long,
    val characters: Long,
    /** Seconds already handed out by the Refold export. */
    val refoldSeconds: Long = 0,
    /** The phone that read (a sync file's installation id); empty = the phone that wrote the file. */
    val device: String = "",
)

@Serializable
data class BackupLookupEvent(@SerialName("at") val lookedUpAt: Long, val book: String? = null)

@Serializable
data class BackupKnownBatch(
    val source: String,
    val detail: String,
    val createdAt: Long,
    val count: Int,
    val words: List<BackupKnownBatchWord> = emptyList(),
)

/** A word a batch changed, by written form and reading; see `KnownBatchWordEntity`. */
@Serializable
data class BackupKnownBatchWord(
    val expression: String,
    val reading: String,
    val created: Boolean,
    val previousStatus: String? = null,
    val lookupCount: Int = 0,
)

@Serializable
data class BackupWord(
    val expression: String,
    val reading: String,
    val gloss: String,
    val status: String,
    val starred: Boolean = false,
    val ignored: Boolean = false,
    val lookupCount: Int,
    val firstLookedUpAt: Long,
    val lastLookedUpAt: Long,
    val rankKey: Double,
    val lastSentence: String,
    val lastSurfaceStart: Int,
    val lastSurfaceEnd: Int,
    val lastBookTitle: String? = null,
    /** The reader's note on the word; older backups have none. */
    val note: String = "",
    /** The reader's correction of the furigana (`WordEntity.furigana`); older backups have none. */
    val furigana: String = "",
    /** When a field the reader sets last changed (`WordEntity.updatedAt`); 0 in older backups. */
    val updatedAt: Long = 0,
    /**
     * Older backups carry the AnkiDroid note id; it is neither written nor restored now: it is
     * only valid in the AnkiDroid it was made in, and on another phone it would show "in Anki"
     * without a card. Adding the word again finds an existing note and marks it.
     */
    val ankiNoteId: Long? = null,
    /** `WordProgress` steps, reads and lookups it was worked out from, and the learned mark. */
    val progress: BackupWordStep? = null,
    val lookups: List<BackupWordLookup> = emptyList(),
    /** The word's review memory (`ReviewCardEntity`); null if it isn't in review. Older backups have none. */
    val card: BackupCard? = null,
    /** Real reviews of the word (`ReviewLogEntity`), oldest first; they outlive the card but not the word. */
    val reviews: List<BackupReview> = emptyList(),
    /** Days the word was read in a book without a lookup (`ReadDayEntity`). */
    val reads: List<BackupReadDay> = emptyList(),
)

/** A review card as stored: enum names and epoch milliseconds, so a restore reads it back unchanged. */
@Serializable
data class BackupCard(
    val state: String,
    val due: Long,
    val stability: Double = 0.0,
    val difficulty: Double = 0.0,
    val reps: Int = 0,
    val lapses: Int = 0,
    val passedReviews: Int = 0,
    val lastGrade: Int? = null,
    val lastReviewAt: Long? = null,
    val reviewStability: Double = 0.0,
    val buriedUntil: Long? = null,
    val addedAt: Long,
    /** `ReviewCardEntity.updatedAt`; 0 in older backups. */
    val updatedAt: Long = 0,
)

@Serializable
data class BackupReview(val at: Long, val grade: Int, val elapsedDays: Double = 0.0)

@Serializable
data class BackupReadDay(val day: Long, val count: Int = 1)

@Serializable
data class BackupWordStep(val steps: Int, val reads: Int, val lookups: Int, val learned: Boolean, val kana: Boolean = false)

@Serializable
data class BackupWordLookup(
    val surface: String,
    val sentence: String,
    val surfaceStart: Int,
    val surfaceEnd: Int,
    val book: String? = null,
    val bookTitle: String? = null,
    @SerialName("at") val lookedUpAt: Long,
    /** `LookupOrigin` name: only reader lookups count against a review card; PREPARE is no lookup. */
    val origin: String = "READER",
)

@Serializable
data class BackupCatalog(
    val title: String,
    val url: String,
    val username: String? = null,
    val autoImport: Boolean = true,
)

/** The file isn't a Honmani backup, or one from a newer Honmani. */
open class BackupFormatException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** The file is from a newer Honmani; [deviceName] says which phone, if it is a sync file. */
class NewerBackupException(message: String, val deviceName: String? = null) : BackupFormatException(message)

/** Reads and writes [BackupFile]s: one JSON entry in a zip. */
object BackupCodec {
    const val ENTRY = "honmani-backup.json"
    const val MIME_TYPE = "application/zip"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** The JSON a zip holds; also what [SyncFolder] fingerprints to tell whether anything changed. */
    fun toJson(backup: BackupFile): String = json.encodeToString(BackupFile.serializer(), backup)

    fun write(backup: BackupFile, output: OutputStream) {
        val zip = ZipOutputStream(output)
        zip.putNextEntry(ZipEntry(ENTRY))
        zip.write(toJson(backup).toByteArray(Charsets.UTF_8))
        zip.closeEntry()
        zip.finish()
        zip.flush()
    }

    /** A backup's JSON is a few MB at most; a zip entry can claim to inflate to far more. */
    internal const val MAX_JSON_BYTES = 32 * 1024 * 1024

    fun read(input: InputStream, maxJsonBytes: Int = MAX_JSON_BYTES): BackupFile {
        val zip = ZipInputStream(input)
        while (true) {
            val entry = zip.nextEntry ?: throw BackupFormatException("No $ENTRY in the file")
            if (entry.name == ENTRY) break
        }
        val text = try {
            zip.readBytesAtMost(maxJsonBytes).toString(Charsets.UTF_8)
        } catch (e: TooLargeException) {
            throw BackupFormatException("Not a Honmani backup: the data is too large", e)
        }
        val backup = try {
            json.decodeFromString(BackupFile.serializer(), text)
        } catch (e: SerializationException) {
            throw BackupFormatException("Not a Honmani backup", e)
        } catch (e: IllegalArgumentException) {
            throw BackupFormatException("Not a Honmani backup", e)
        }
        if (backup.format != BackupFile.FORMAT) throw BackupFormatException("Not a Honmani backup")
        if (backup.version > BackupFile.VERSION) throw NewerBackupException("Made by a newer Honmani", backup.deviceName)
        return backup
    }
}
