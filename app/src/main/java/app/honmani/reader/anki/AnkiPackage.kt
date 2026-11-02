package app.honmani.reader.anki

import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Cards as an Anki package (.apkg) for Anki on a computer or AnkiMobile: Honmani's note type
 * ([AnkiCard]), the deck and the audio in one file, opened with File → Import. Written in Anki's
 * collection format of schema 11 (`collection.anki2`), which every Anki since 2.1 imports:
 * - `collection.anki2`: an SQLite database; the note type, the deck and its options are JSON in
 *   the `col` row, and each word is a note with one card;
 * - `media`: JSON from the numbered files in the zip to the names the fields use (`[sound:…]`);
 * - `0`, `1`, …: the audio files.
 *
 * The note type and deck ids are fixed and a note's guid comes from its word, so importing a
 * newer package updates the cards already imported instead of adding them again.
 */
object AnkiPackage {
    /** One note: its fields in the [NoteModel]'s order and the audio files they name. */
    class Note(val fields: List<String>, val media: List<File> = emptyList())

    /** Fixed, so every package from Honmani uses the same note type in Anki (the "Honmani" one; see [NoteModel.id]). */
    const val NOTE_TYPE_ID = 1_726_000_000_001L

    /**
     * Writes [notes], made for [model], for [deck] to [out]; [workDir] holds the database while it
     * is written.
     */
    fun write(
        notes: List<Note>,
        deck: String,
        workDir: File,
        out: OutputStream,
        now: Long = System.currentTimeMillis(),
        model: NoteModel = AnkiCard.MODEL,
    ) {
        workDir.mkdirs()
        val database = File(workDir, "collection.anki2")
        database.delete()
        File(workDir, "collection.anki2-journal").delete()
        try {
            writeCollection(database, notes, deck, now, model)
            ZipOutputStream(out).apply {
                putNextEntry(ZipEntry("collection.anki2"))
                database.inputStream().use { it.copyTo(this) }
                closeEntry()
                val media = notes.flatMap { it.media }.distinctBy { it.name }
                putNextEntry(ZipEntry("media"))
                write(
                    buildJsonObject { media.forEachIndexed { i, file -> put(i.toString(), file.name) } }
                        .toString().toByteArray(Charsets.UTF_8),
                )
                closeEntry()
                media.forEachIndexed { i, file ->
                    putNextEntry(ZipEntry(i.toString()))
                    file.inputStream().use { it.copyTo(this) }
                    closeEntry()
                }
                finish()
            }
        } finally {
            database.delete()
        }
    }

    /** The deck's id: from its name, so a package for the same deck finds it again. */
    fun deckId(deck: String): Long =
        1_000_000_000L + (positiveHash("deck\u0000$deck") % 1_000_000_000_000L)

    /**
     * A note's guid in Anki: from the word, so it stays the same between packages. Each note type
     * has its own prefix, so a word in both is two notes.
     */
    fun guid(fields: List<String>, model: NoteModel = AnkiCard.MODEL): String =
        model.guidPrefix + hex(sha1(fields[0] + "\u0000" + fields[1])).take(16)

    private fun writeCollection(file: File, notes: List<Note>, deck: String, now: Long, model: NoteModel) {
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        try {
            db.beginTransaction()
            for (statement in SCHEMA) db.execSQL(statement)
            val deckId = deckId(deck)
            val seconds = now / 1000
            db.execSQL(
                "INSERT INTO col VALUES (1, ?, ?, ?, 11, 0, 0, 0, ?, ?, ?, ?, '{}')",
                arrayOf<Any>(
                    seconds,
                    now,
                    now,
                    configuration(deckId, notes.size, model).toString(),
                    buildJsonObject { put(model.id.toString(), noteType(deckId, seconds, model)) }.toString(),
                    decks(deckId, deck, seconds).toString(),
                    DECK_OPTIONS,
                ),
            )
            notes.forEachIndexed { i, note ->
                // Ids are creation times in milliseconds in Anki; one apart keeps them unique.
                val id = now + i
                db.execSQL(
                    "INSERT INTO notes VALUES (?, ?, ?, ?, -1, ?, ?, ?, ?, 0, '')",
                    arrayOf<Any>(
                        id,
                        guid(note.fields, model),
                        model.id,
                        seconds,
                        " ${AnkiCard.TAG} ",
                        note.fields.joinToString(FIELD_SEPARATOR),
                        note.fields[0].replace(TAG, ""),
                        AnkiCard.checksum(note.fields[0]),
                    ),
                )
                // A new card (type and queue 0), due in the order of the list.
                db.execSQL(
                    "INSERT INTO cards VALUES (?, ?, ?, 0, ?, -1, 0, 0, ?, 0, 0, 0, 0, 0, 0, 0, 0, '')",
                    arrayOf<Any>(id, id, deckId, seconds, i + 1),
                )
            }
            db.setTransactionSuccessful()
        } finally {
            if (db.inTransaction()) db.endTransaction()
            db.close()
        }
    }

    private fun configuration(deckId: Long, notes: Int, model: NoteModel) = buildJsonObject {
        put("nextPos", notes + 1)
        put("estTimes", true)
        putJsonArray("activeDecks") { add(deckId) }
        put("sortType", "noteFld")
        put("timeLim", 0)
        put("sortBackwards", false)
        put("addToCur", true)
        put("curDeck", deckId)
        put("newSpread", 0)
        put("dueCounts", true)
        put("curModel", model.id)
        put("collapseTime", 1200)
    }

    private fun noteType(deckId: Long, seconds: Long, model: NoteModel) = buildJsonObject {
        put("id", model.id)
        put("name", model.name)
        put("type", 0)
        put("mod", seconds)
        put("usn", -1)
        put("sortf", 0)
        put("did", deckId)
        putJsonArray("tmpls") {
            add(
                buildJsonObject {
                    put("name", model.cardName)
                    put("ord", 0)
                    put("qfmt", model.front)
                    put("afmt", model.back)
                    put("bqfmt", "")
                    put("bafmt", "")
                    put("did", null as String?)
                },
            )
        }
        putJsonArray("flds") {
            model.fields.forEachIndexed { i, name ->
                add(
                    buildJsonObject {
                        put("name", name)
                        put("ord", i)
                        put("sticky", false)
                        put("rtl", false)
                        put("font", "Arial")
                        put("size", 20)
                        putJsonArray("media") {}
                    },
                )
            }
        }
        put("css", model.css)
        put("latexPre", LATEX_PRE)
        put("latexPost", "\\end{document}")
        put("latexsvg", false)
        // The card needs one of the fields its front shows.
        putJsonArray("req") {
            add(buildJsonArray { add(0); add("any"); addJsonArray { model.required.forEach { add(it) } } })
        }
        putJsonArray("tags") {}
        putJsonArray("vers") {}
    }

    private fun decks(deckId: Long, deck: String, seconds: Long) = buildJsonObject {
        put("1", deck(1, "Default", seconds))
        put(deckId.toString(), deck(deckId, deck, seconds))
    }

    private fun deck(id: Long, name: String, seconds: Long): JsonObject = buildJsonObject {
        put("id", id)
        put("name", name)
        put("mod", seconds)
        put("usn", -1)
        for (counter in listOf("lrnToday", "revToday", "newToday", "timeToday")) {
            putJsonArray(counter) { add(0); add(0) }
        }
        put("collapsed", false)
        put("browserCollapsed", false)
        put("desc", "")
        put("dyn", 0)
        put("conf", 1)
        put("extendNew", 0)
        put("extendRev", 0)
    }

    private fun positiveHash(text: String): Long =
        sha1(text).take(8).fold(0L) { value, byte -> (value shl 8) or (byte.toLong() and 0xFF) } and Long.MAX_VALUE

    private fun sha1(text: String) = MessageDigest.getInstance("SHA-1").digest(text.toByteArray(Charsets.UTF_8))

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    /** Anki separates a note's fields with the unit separator. */
    private const val FIELD_SEPARATOR = "\u001f"
    private val TAG = Regex("<[^>]*>")

    private const val LATEX_PRE = "\\documentclass[12pt]{article}\n\\special{papersize=3in,5in}\n" +
        "\\usepackage[utf8]{inputenc}\n\\usepackage{amssymb,amsmath}\n\\pagestyle{empty}\n" +
        "\\setlength{\\parindent}{0in}\n\\begin{document}\n"

    /** Anki's default deck options, which the deck uses. */
    private val DECK_OPTIONS = buildJsonObject {
        putJsonObject("1") {
            put("id", 1)
            put("name", "Default")
            put("mod", 0)
            put("usn", 0)
            put("maxTaken", 60)
            put("autoplay", true)
            put("timer", 0)
            put("replayq", true)
            put("dyn", false)
            putJsonObject("new") {
                put("bury", false)
                putJsonArray("delays") { add(1.0); add(10.0) }
                put("initialFactor", 2500)
                putJsonArray("ints") { add(1); add(4); add(0) }
                put("order", 1)
                put("perDay", 20)
            }
            putJsonObject("lapse") {
                putJsonArray("delays") { add(10.0) }
                put("leechAction", 1)
                put("leechFails", 8)
                put("minInt", 1)
                put("mult", 0.0)
            }
            putJsonObject("rev") {
                put("bury", false)
                put("ease4", 1.3)
                put("ivlFct", 1.0)
                put("maxIvl", 36500)
                put("perDay", 200)
                put("hardFactor", 1.2)
            }
        }
    }.toString()

    /** Anki's tables of schema 11, without the ones a package doesn't fill. */
    private val SCHEMA = listOf(
        """CREATE TABLE col (id integer PRIMARY KEY, crt integer NOT NULL, mod integer NOT NULL,
            scm integer NOT NULL, ver integer NOT NULL, dty integer NOT NULL, usn integer NOT NULL,
            ls integer NOT NULL, conf text NOT NULL, models text NOT NULL, decks text NOT NULL,
            dconf text NOT NULL, tags text NOT NULL)""",
        """CREATE TABLE notes (id integer PRIMARY KEY, guid text NOT NULL, mid integer NOT NULL,
            mod integer NOT NULL, usn integer NOT NULL, tags text NOT NULL, flds text NOT NULL,
            sfld integer NOT NULL, csum integer NOT NULL, flags integer NOT NULL, data text NOT NULL)""",
        """CREATE TABLE cards (id integer PRIMARY KEY, nid integer NOT NULL, did integer NOT NULL,
            ord integer NOT NULL, mod integer NOT NULL, usn integer NOT NULL, type integer NOT NULL,
            queue integer NOT NULL, due integer NOT NULL, ivl integer NOT NULL, factor integer NOT NULL,
            reps integer NOT NULL, lapses integer NOT NULL, left integer NOT NULL, odue integer NOT NULL,
            odid integer NOT NULL, flags integer NOT NULL, data text NOT NULL)""",
        """CREATE TABLE revlog (id integer PRIMARY KEY, cid integer NOT NULL, usn integer NOT NULL,
            ease integer NOT NULL, ivl integer NOT NULL, lastIvl integer NOT NULL, factor integer NOT NULL,
            time integer NOT NULL, type integer NOT NULL)""",
        "CREATE TABLE graves (usn integer NOT NULL, oid integer NOT NULL, type integer NOT NULL)",
        "CREATE INDEX ix_notes_usn ON notes (usn)",
        "CREATE INDEX ix_cards_usn ON cards (usn)",
        "CREATE INDEX ix_revlog_usn ON revlog (usn)",
        "CREATE INDEX ix_cards_nid ON cards (nid)",
        "CREATE INDEX ix_cards_sched ON cards (did, queue, due)",
        "CREATE INDEX ix_revlog_cid ON revlog (cid)",
        "CREATE INDEX ix_notes_csum ON notes (csum)",
    )
}
