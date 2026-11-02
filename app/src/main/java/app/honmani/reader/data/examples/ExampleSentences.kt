package app.honmani.reader.data.examples

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import app.honmani.reader.lookup.JapaneseText
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/** One example sentence with its English translation (Tatoeba, CC BY 2.0 FR). */
data class Example(val japanese: String, val english: String)

/**
 * Example sentences for the lookup panel, from the downloaded file of [store]; nothing while the
 * file is missing or the reader has switched them off ([enabled]). The database is opened
 * read-only on the first query and again if the file was replaced.
 */
class ExampleSentences(
    private val store: ExampleStore,
    private val enabled: StateFlow<Boolean>,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private var db: SQLiteDatabase? = null
    private var openedFile: Pair<Long, Long>? = null

    /** The examples of the word [expression] read [reading], at most [limit]; see [ExampleQuery.find]. */
    suspend fun find(expression: String, reading: String, limit: Int = ExampleQuery.LIMIT): List<Example> {
        if (!enabled.value || store.state.value != ExampleState.Installed) return emptyList()
        return withContext(io) {
            try {
                database()?.let { ExampleQuery.find(it, expression, reading, limit) }.orEmpty()
            } catch (e: SQLiteException) {
                emptyList()
            }
        }
    }

    @Synchronized
    private fun database(): SQLiteDatabase? {
        val file = store.file
        val stamp = file.lastModified() to file.length()
        if (db == null || openedFile != stamp) {
            db?.close()
            db = null
            if (file.isFile) {
                db = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)
                openedFile = stamp
            }
        }
        return db
    }
}

/** The matching of words to sentences in the file made by `scripts/examples/build_examples.py`. */
object ExampleQuery {
    const val LIMIT = 5

    // Per sentence the best fit of the word's reading: 0 agrees, 1 the data has none, 2 differs.
    private const val BY_WORD = """
        SELECT s.ja, s.en,
               MIN(CASE WHEN w.reading = ? THEN 0 WHEN w.reading = '' THEN 1 ELSE 2 END) AS fit,
               MIN(w.rank) AS rank
        FROM word w JOIN sentence s ON s.id = w.sentence_id
        WHERE w.word = ?
        GROUP BY s.id ORDER BY fit, rank, s.id LIMIT ?"""

    private const val BY_READING = """
        SELECT s.ja, s.en
        FROM word w JOIN sentence s ON s.id = w.sentence_id
        WHERE w.reading = ?
        GROUP BY s.id ORDER BY MIN(w.rank), s.id LIMIT ?"""

    /**
     * The sentences with [expression] as a headword, those whose headword reading agrees with
     * [reading] (katakana counts as hiragana) first, then those without a reading, then those with
     * another one, each in the file's order (checked examples, then shorter). A kana-only entry
     * that has none is looked up by reading instead, which finds 為る(する) for する. At most [limit].
     */
    fun find(db: SQLiteDatabase, expression: String, reading: String, limit: Int = LIMIT): List<Example> {
        val kana = JapaneseText.toHiragana(reading.ifBlank { expression })
        val byWord = db.rawQuery(BY_WORD, arrayOf(kana, expression, limit.toString())).use { it.examples() }
        val kanaOnly = expression.none { JapaneseText.isKanji(it.code) }
        if (byWord.isNotEmpty() || !kanaOnly) return byWord
        return db.rawQuery(BY_READING, arrayOf(kana, limit.toString())).use { it.examples() }
    }

    private fun android.database.Cursor.examples(): List<Example> = buildList {
        while (moveToNext()) add(Example(getString(0), getString(1)))
    }
}
