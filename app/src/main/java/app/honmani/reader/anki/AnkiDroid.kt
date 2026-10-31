package app.honmani.reader.anki

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Process
import androidx.core.content.FileProvider
import java.io.File

/** A note as AnkiDroid stores it: its [fields] are in the note type's order, as raw HTML or text. */
data class AnkiNote(val id: Long, val noteType: Long, val fields: List<String>)

/** What Honmani needs from AnkiDroid; see [AnkiDroid]. */
interface AnkiApi {
    fun packageName(): String?

    fun hasPermission(): Boolean

    fun noteTypeExists(id: Long): Boolean

    fun findNoteType(name: String, fields: List<String>): Long?

    fun addNoteType(
        name: String,
        fields: List<String>,
        cardName: String,
        front: String,
        back: String,
        css: String,
    ): Long?

    /** The front template of the note type's first card type, if it can be read. */
    fun cardFront(noteType: Long): String?

    /** Sets the note type's first card type. */
    fun setCard(noteType: Long, cardName: String, front: String, back: String): Boolean

    fun deck(name: String): Long?

    /** The names of AnkiDroid's decks, for choosing one. */
    fun deckNames(): List<String>

    fun findNote(noteType: Long, first: String, second: String): Long?

    fun addNote(noteType: Long, deck: Long, fields: List<String>, tags: Set<String>): Long?

    fun addSound(file: File, preferredName: String): String?

    /** The ids among [ids] that are still notes in AnkiDroid; null if it gave no answer. */
    fun existingNotes(ids: Collection<Long>): Set<Long>?

    /** Every note of [noteType]; null if AnkiDroid gave no answer. */
    fun notes(noteType: Long): List<AnkiNote>?
}

/**
 * Talks to AnkiDroid through its content provider (AnkiDroid's documented "FlashCardsContract").
 * Honmani uses the provider directly rather than AnkiDroid's client library: only the URIs and
 * column names below are needed. Every call needs AnkiDroid installed and [PERMISSION] granted,
 * and runs off the main thread (they are database calls in another app).
 */
class AnkiDroid(context: Context) : AnkiApi {
    private val context = context.applicationContext
    private val resolver: ContentResolver = this.context.contentResolver

    /** The installed AnkiDroid's package, or null if there is none. */
    override fun packageName(): String? =
        context.packageManager.resolveContentProvider(AUTHORITY, 0)?.packageName

    override fun hasPermission(): Boolean =
        context.checkPermission(PERMISSION, Process.myPid(), Process.myUid()) == PackageManager.PERMISSION_GRANTED

    /** Whether the note type [id] still exists (it can be deleted in Anki). */
    override fun noteTypeExists(id: Long): Boolean =
        resolver.query(Uri.withAppendedPath(MODELS, id.toString()), arrayOf(ID), null, null, null)
            ?.use { it.moveToFirst() } == true

    /** The id of the note type called [name] with exactly [fields], if there is one. */
    override fun findNoteType(name: String, fields: List<String>): Long? =
        resolver.query(MODELS, null, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                // Anki makes a second note type of the same name unique ("Honmani-a1b2c"), as happens
                // when the fields change: the fields decide.
                val modelName = cursor.string(MODEL_NAME).orEmpty()
                val sameName = modelName == name || modelName.startsWith("$name-")
                if (sameName && cursor.string(MODEL_FIELD_NAMES)?.split(SEPARATOR) == fields) {
                    return cursor.long(ID)
                }
            }
            null
        }

    /** Creates a note type with one card type; returns its id, or null if AnkiDroid refused. */
    override fun addNoteType(
        name: String,
        fields: List<String>,
        cardName: String,
        front: String,
        back: String,
        css: String,
    ): Long? {
        val values = ContentValues().apply {
            put(MODEL_NAME, name)
            put(MODEL_FIELD_NAMES, fields.joinToString(SEPARATOR))
            put(MODEL_NUM_CARDS, 1)
            put(MODEL_CSS, css)
            put(MODEL_SORT_FIELD, 0)
        }
        val uri = resolver.insert(MODELS, values) ?: return null
        val id = uri.lastPathSegment?.toLongOrNull() ?: return null
        setCard(id, cardName, front, back)
        return id
    }

    override fun cardFront(noteType: Long): String? =
        resolver.query(firstCard(noteType), null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.string(TEMPLATE_FRONT) else null
        }

    override fun setCard(noteType: Long, cardName: String, front: String, back: String): Boolean {
        val template = ContentValues().apply {
            put(TEMPLATE_NAME, cardName)
            put(TEMPLATE_FRONT, front)
            put(TEMPLATE_BACK, back)
        }
        return resolver.update(firstCard(noteType), template, null, null) > 0
    }

    private fun firstCard(noteType: Long): Uri =
        Uri.withAppendedPath(Uri.withAppendedPath(Uri.withAppendedPath(MODELS, noteType.toString()), "templates"), "0")

    /** The id of the deck called [name], created if needed; null if AnkiDroid refused. */
    override fun deck(name: String): Long? {
        resolver.query(DECKS, null, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                if (cursor.string(DECK_NAME) == name) return cursor.long(DECK_ID)
            }
        }
        val uri = resolver.insert(DECKS, ContentValues().apply { put(DECK_NAME, name) }) ?: return null
        return uri.lastPathSegment?.toLongOrNull()
    }

    override fun deckNames(): List<String> =
        resolver.query(DECKS, null, null, null, null)?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) cursor.string(DECK_NAME)?.let(::add)
            }
        }.orEmpty()

    /** A note of [noteType] whose first two fields are [first] and [second], if one exists. */
    override fun findNote(noteType: Long, first: String, second: String): Long? {
        val selection = "$NOTE_TYPE=$noteType and $CHECKSUM=${AnkiCard.checksum(first)}"
        return resolver.query(NOTES_V2, arrayOf(ID, FIELDS), selection, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val fields = cursor.string(FIELDS)?.split(SEPARATOR).orEmpty()
                if (fields.getOrNull(0) == first && fields.getOrNull(1) == second) return cursor.long(ID)
            }
            null
        }
    }

    /** Adds a note and moves its cards to [deck]; returns the note id, or null if refused. */
    override fun addNote(noteType: Long, deck: Long, fields: List<String>, tags: Set<String>): Long? {
        val values = ContentValues().apply {
            put(NOTE_TYPE, noteType)
            put(FIELDS, fields.joinToString(SEPARATOR))
            put(TAGS, tags.joinToString(" "))
        }
        val note = resolver.insert(NOTES, values) ?: return null
        val cards = Uri.withAppendedPath(note, "cards")
        resolver.query(cards, null, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val ord = cursor.string(CARD_ORD) ?: continue
                val deckValues = ContentValues().apply { put(DECK_ID, deck) }
                resolver.update(Uri.withAppendedPath(cards, ord), deckValues, null, null)
            }
        }
        return note.lastPathSegment?.toLongOrNull()
    }

    /**
     * Copies a media file into AnkiDroid's collection. [file] must be in the cache directory
     * the file provider shares (`res/xml/file_paths.xml`); AnkiDroid is granted to read it.
     * Returns the `[sound:…]` reference for a field, or null.
     */
    override fun addSound(file: File, preferredName: String): String? {
        val ankiPackage = packageName() ?: return null
        val uri = FileProvider.getUriForFile(context, "${context.packageName}$FILE_PROVIDER_SUFFIX", file)
        context.grantUriPermission(ankiPackage, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val values = ContentValues().apply {
            put(MEDIA_FILE_URI, uri.toString())
            put(MEDIA_PREFERRED_NAME, preferredName)
        }
        val stored = resolver.insert(MEDIA, values)?.path?.trimStart('/') ?: return null
        return "[sound:$stored]"
    }

    override fun existingNotes(ids: Collection<Long>): Set<Long>? {
        val found = mutableSetOf<Long>()
        for (chunk in ids.distinct().chunked(NOTE_CHUNK)) {
            // Anki's own search syntax ("nid:1,2,3"), which the notes URI takes as its selection.
            val notes = readNotes(NOTES, "nid:" + chunk.joinToString(",")) ?: return null
            notes.forEach { found += it.id }
        }
        return found
    }

    override fun notes(noteType: Long): List<AnkiNote>? = readNotes(NOTES_V2, "$NOTE_TYPE=$noteType")

    private fun readNotes(uri: Uri, selection: String): List<AnkiNote>? =
        resolver.query(uri, null, selection, null, null)?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val id = cursor.long(ID) ?: continue
                    val fields = cursor.string(FIELDS)?.split(SEPARATOR).orEmpty()
                    add(AnkiNote(id, cursor.long(NOTE_TYPE) ?: 0L, fields))
                }
            }
        }

    private fun Cursor.string(column: String): String? =
        getColumnIndex(column).takeIf { it >= 0 }?.let { getString(it) }

    private fun Cursor.long(column: String): Long? =
        getColumnIndex(column).takeIf { it >= 0 }?.let { getLong(it) }

    companion object {
        const val AUTHORITY = "com.ichi2.anki.flashcards"
        const val PERMISSION = "com.ichi2.anki.permission.READ_WRITE_DATABASE"
        /** The app's file provider, `${applicationId}.files` in the manifest. */
        const val FILE_PROVIDER_SUFFIX = ".files"
        const val PLAY_STORE_URL = "https://play.google.com/store/apps/details?id=com.ichi2.anki"

        private val ROOT: Uri = Uri.parse("content://$AUTHORITY")
        private val NOTES: Uri = Uri.withAppendedPath(ROOT, "notes")
        private val NOTES_V2: Uri = Uri.withAppendedPath(ROOT, "notes_v2")
        private val MODELS: Uri = Uri.withAppendedPath(ROOT, "models")
        private val DECKS: Uri = Uri.withAppendedPath(ROOT, "decks")
        private val MEDIA: Uri = Uri.withAppendedPath(ROOT, "media")

        private const val NOTE_CHUNK = 100
        private const val SEPARATOR = "\u001f"
        private const val ID = "_id"
        private const val NOTE_TYPE = "mid"
        private const val FIELDS = "flds"
        private const val TAGS = "tags"
        private const val CHECKSUM = "csum"
        private const val MODEL_NAME = "name"
        private const val MODEL_FIELD_NAMES = "field_names"
        private const val MODEL_NUM_CARDS = "num_cards"
        private const val MODEL_CSS = "css"
        private const val MODEL_SORT_FIELD = "sort_field_index"
        private const val TEMPLATE_NAME = "card_template_name"
        private const val TEMPLATE_FRONT = "question_format"
        private const val TEMPLATE_BACK = "answer_format"
        private const val CARD_ORD = "ord"
        private const val DECK_ID = "deck_id"
        private const val DECK_NAME = "deck_name"
        private const val MEDIA_FILE_URI = "file_uri"
        private const val MEDIA_PREFERRED_NAME = "preferred_name"
    }
}
