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
 * settings, reading progress and statistics, saved words, word progress and milestones. Books are
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
    /**
     * The parts of each book really read (`ReadStretches`), by content hash: ascending steps
     * `from, to`, also for books no longer in the library (they count again if added again).
     */
    val stretches: Map<String, List<Int>> = emptyMap(),
    /** Catalogue books the reader removed, so automatic imports don't bring them back. */
    val removedBooks: List<BackupRemovedBook> = emptyList(),
    val readingDays: List<BackupReadingDay> = emptyList(),
    val lookupEvents: List<BackupLookupEvent> = emptyList(),
    val words: List<BackupWord> = emptyList(),
    /** Book servers, without their passwords. */
    val catalogs: List<BackupCatalog> = emptyList(),
) {
    companion object {
        const val FORMAT = "honmani-backup"
        const val VERSION = 1
    }
}

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
    /** Its difficulty when it was graded, for the skill until the book is back; 0 if not graded. */
    val score: Int = 0,
)

@Serializable
data class BackupRemovedBook(val source: String, val sourceId: String, val removedAt: Long)

@Serializable
data class BackupReadingDay(
    /** A [BackupBook.key]. */
    val book: String,
    val day: String,
    val seconds: Long,
    val characters: Long,
)

@Serializable
data class BackupLookupEvent(@SerialName("at") val lookedUpAt: Long, val book: String? = null)

@Serializable
data class BackupWord(
    val expression: String,
    val reading: String,
    val gloss: String,
    val status: String,
    val starred: Boolean = false,
    val lookupCount: Int,
    val firstLookedUpAt: Long,
    val lastLookedUpAt: Long,
    val rankKey: Double,
    val lastSentence: String,
    val lastSurfaceStart: Int,
    val lastSurfaceEnd: Int,
    val lastBookTitle: String? = null,
    /**
     * Older backups carry the AnkiDroid note id; it is neither written nor restored now: it is
     * only valid in the AnkiDroid it was made in, and on another phone it would show "in Anki"
     * without a card. Adding the word again finds an existing note and marks it.
     */
    val ankiNoteId: Long? = null,
    /** `WordProgress` steps, reads and lookups it was worked out from, and the learned mark. */
    val progress: BackupWordStep? = null,
    val lookups: List<BackupWordLookup> = emptyList(),
)

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
)

@Serializable
data class BackupCatalog(
    val title: String,
    val url: String,
    val username: String? = null,
    val autoImport: Boolean = true,
)

/** The file isn't a Honmani backup, or one from a newer Honmani. */
class BackupFormatException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** Reads and writes [BackupFile]s: one JSON entry in a zip. */
object BackupCodec {
    const val ENTRY = "honmani-backup.json"
    const val MIME_TYPE = "application/zip"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun write(backup: BackupFile, output: OutputStream) {
        val zip = ZipOutputStream(output)
        zip.putNextEntry(ZipEntry(ENTRY))
        zip.write(json.encodeToString(BackupFile.serializer(), backup).toByteArray(Charsets.UTF_8))
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
        if (backup.version > BackupFile.VERSION) throw BackupFormatException("Made by a newer Honmani")
        return backup
    }
}
