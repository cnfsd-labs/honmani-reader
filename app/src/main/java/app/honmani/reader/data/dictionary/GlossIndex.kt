package app.honmani.reader.data.dictionary

import android.util.Log
import androidx.sqlite.db.SupportSQLiteDatabase
import app.honmani.reader.data.HonmaniDatabase
import app.honmani.reader.lookup.GlossaryFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Full-text index of the English (or other) glosses of each dictionary, for searching
 * dictionaries by meaning. One contentless FTS4 table per dictionary (`gloss_index_<id>`, rowid =
 * term id), kept outside Room's schema: it holds only the index (about 10 MB for Jitendex), and a
 * dictionary's table is dropped with it. A table is built under another name and renamed when
 * complete, so an interrupted build leaves nothing half done.
 */
class GlossIndex(private val database: HonmaniDatabase) {
    private val mutex = Mutex()
    private val _building = MutableStateFlow(false)

    /** Whether an index is being built (English search finds less until it is done). */
    val building: StateFlow<Boolean> = _building.asStateFlow()

    private val db: SupportSQLiteDatabase get() = database.openHelper.writableDatabase

    /**
     * Builds the indexes that ready dictionaries with terms lack and drops those of dictionaries
     * that are gone. Cheap when nothing is missing; called at start and after imports.
     */
    suspend fun sync() = withContext(Dispatchers.IO) {
        mutex.withLock {
            val ready = database.dictionaryDao().allReady().filter { it.termCount > 0 }.map { it.id }.toSet()
            val tables = tables()
            // Also a build cut short, and indexes of an older format (another prefix).
            for (name in tables) {
                if (name.removePrefix(PREFIX).toLongOrNull() !in ready) db.execSQL("DROP TABLE IF EXISTS $name")
            }
            val missing = ready - built(tables)
            if (missing.isEmpty()) return@withLock
            _building.value = true
            try {
                for (id in missing) build(id)
            } finally {
                _building.value = false
            }
        }
    }

    /** Ids of the dictionaries in [dictionaryIds] that have an index. */
    fun indexed(dictionaryIds: Collection<Long>): List<Long> {
        val built = built(tables())
        return dictionaryIds.filter { it in built }
    }

    /** Term ids of [dictionaryIds] whose glosses match the FTS4 [match] expression, at most [limit]. */
    fun search(match: String, dictionaryIds: Collection<Long>, limit: Int): List<Long> {
        val ids = ArrayList<Long>()
        for (id in indexed(dictionaryIds)) {
            db.query("SELECT docid FROM $PREFIX$id WHERE $PREFIX$id MATCH ? LIMIT $limit", arrayOf(match)).use {
                while (it.moveToNext()) ids += it.getLong(0)
            }
        }
        return ids
    }

    /** Names of the gloss index tables, complete or not (not their FTS4 shadow tables). */
    private fun tables(): List<String> =
        db.query("SELECT name FROM sqlite_master WHERE type = 'table' AND name GLOB 'gloss_*' AND sql GLOB 'CREATE VIRTUAL TABLE*'").use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }

    private fun built(tables: List<String>): Set<Long> = tables.mapNotNullTo(HashSet()) { it.removePrefix(PREFIX).toLongOrNull() }

    private suspend fun build(dictionaryId: Long) {
        val started = System.nanoTime()
        val building = "$BUILD_PREFIX$dictionaryId"
        db.execSQL("DROP TABLE IF EXISTS $building")
        db.execSQL("CREATE VIRTUAL TABLE $building USING fts4(content=\"\", gloss, tokenize=unicode61)")
        val preset = db.query("SELECT preset FROM glossary_preset WHERE dictionary_id = ?", arrayOf(dictionaryId)).use {
            if (it.moveToFirst()) it.getBlob(0) else null
        }
        var lastId = 0L
        var count = 0
        var unpackNanos = 0L
        while (true) {
            val rows = db.query(
                "SELECT id, glossary FROM term WHERE dictionary_id = ? AND id > ? ORDER BY id LIMIT $CHUNK",
                arrayOf(dictionaryId, lastId),
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(cursor.getLong(0) to Glossary(cursor.getBlob(1)))
                }
            }
            if (rows.isEmpty()) break
            val t = System.nanoTime()
            // Unpacking and reading the glossaries is most of the work: spread over the cores.
            val texts = coroutineScope {
                rows.chunked(PARALLEL_CHUNK).map { part ->
                    async(Dispatchers.Default) { part.map { (id, glossary) -> id to text(GlossaryPacker.unpack(glossary, preset)) } }
                }.awaitAll().flatten()
            }
            unpackNanos += System.nanoTime() - t
            db.beginTransaction()
            try {
                val insert = db.compileStatement("INSERT INTO $building(docid, gloss) VALUES (?, ?)")
                for ((id, text) in texts) {
                    if (text.isEmpty()) continue
                    insert.bindLong(1, id)
                    insert.bindString(2, text)
                    insert.executeInsert()
                    count++
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            lastId = rows.last().first
        }
        db.execSQL("INSERT INTO $building($building) VALUES ('optimize')")
        db.execSQL("ALTER TABLE $building RENAME TO $PREFIX$dictionaryId")
        Log.i(
            TAG,
            "Indexed $count glosses of dictionary $dictionaryId in ${(System.nanoTime() - started) / 1_000_000} ms " +
                "(unpacking ${unpackNanos / 1_000_000} ms)",
        )
    }

    companion object {
        private const val TAG = "GlossIndex"
        /** Complete indexes; the version changes when [text] does, so older indexes are rebuilt. */
        private const val PREFIX = "gloss_index_v1_"
        private const val BUILD_PREFIX = "gloss_build_v1_"
        private const val CHUNK = 2_000
        private const val PARALLEL_CHUNK = 250

        /** The text indexed for a glossary: its glosses, without pointers to other entries. */
        fun text(glossary: String): String =
            GlossaryFormatter.format(glossary).asSequence()
                .flatMap { it.glosses }
                .filterNot { it.startsWith("→") }
                .joinToString(" ; ")
    }
}
