package app.honmani.reader.data.difficulty

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Query

/** A term's forms and word classes, for the [Lexicon]. */
data class LexiconTerm(val id: Long, val expression: String, val reading: String, val rules: String)

/** A term's written form and dictionary entry, for `SpellingVariants`. */
data class Spelling(
    val id: Long,
    @ColumnInfo(name = "dictionary_id") val dictionaryId: Long,
    val expression: String,
    val sequence: Long,
)

/** A frequency rank, for the [Lexicon]. */
data class LexiconFrequency(
    val id: Long,
    val expression: String,
    val reading: String?,
    @ColumnInfo(name = "value") val rank: Double,
    /** As the list shows it: Jiten marks a rank of the word written in kana with ㋕ ("615㋕"). */
    val display: String? = null,
)

/**
 * Reads the dictionaries page by page (ordered by id, after the last id read) to build a
 * [Lexicon], so the whole of Jitendex never sits in memory as rows.
 */
@Dao
interface DifficultyDao {
    @Query(
        "SELECT id, expression, reading, rules FROM term WHERE dictionary_id IN (:dictionaries) AND id > :after " +
            "ORDER BY id LIMIT :limit",
    )
    suspend fun terms(dictionaries: List<Long>, after: Long, limit: Int): List<LexiconTerm>

    @Query(
        "SELECT id, dictionary_id, expression, sequence FROM term WHERE dictionary_id IN (:dictionaries) " +
            "AND sequence > 0 AND id > :after ORDER BY id LIMIT :limit",
    )
    suspend fun spellings(dictionaries: List<Long>, after: Long, limit: Int): List<Spelling>

    @Query(
        "SELECT id, expression, reading, value, display FROM term_meta WHERE dictionary_id = :dictionary " +
            "AND kind = 'FREQUENCY' AND value IS NOT NULL AND id > :after ORDER BY id LIMIT :limit",
    )
    suspend fun frequencies(dictionary: Long, after: Long, limit: Int): List<LexiconFrequency>

    /** Whether the dictionary has frequency rows (pitch-accent dictionaries have meta rows too). */
    @Query("SELECT EXISTS(SELECT 1 FROM term_meta WHERE dictionary_id = :dictionary AND kind = 'FREQUENCY' AND value IS NOT NULL)")
    suspend fun hasFrequencies(dictionary: Long): Boolean
}
