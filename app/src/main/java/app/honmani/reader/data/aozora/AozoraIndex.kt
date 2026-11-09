package app.honmani.reader.data.aozora

import app.honmani.reader.data.stats.Skill
import app.honmani.reader.lookup.JapaneseText
import java.io.InputStream
import java.util.Locale
import kotlin.math.abs
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream

/** One work in the Aozora Bunko list. */
class AozoraWork(
    /** Aozora's work ID, six digits. */
    val id: String,
    val title: String,
    /** The title in kana. */
    val titleReading: String,
    val author: String,
    val authorReading: String,
    /** Two letters, kanji forms then kana forms (`n` modern, `o` old), or empty if unknown. */
    val script: String,
    /** The date Aozora published it, `YYYY-MM-DD`. */
    val published: String,
    /** The text file as `<card>/<file>`, below `https://www.aozora.gr.jp/cards/`. */
    val file: String,
    /**
     * Approximate difficulty on the app's 1–100 scale (the books' difficulty, graded ahead of time
     * with the app's own measure), or null if the work has not been graded.
     */
    val difficulty: Int? = null,
) {
    /** Whether the work is in old kana (旧仮名); a work with an unknown script is not counted as old. */
    val oldKana: Boolean get() = script.getOrNull(1) == 'o'

    /** Whether the work is in old kanji forms (旧字). */
    val oldKanji: Boolean get() = script.getOrNull(0) == 'o'

    /** What a search is compared with: title, author and both readings, in plain hiragana. */
    internal val searchKey: String = AozoraSearch.normalize(title) + "\n" + AozoraSearch.normalize(titleReading) + "\n" +
        AozoraSearch.normalize(author) + "\n" + AozoraSearch.normalize(authorReading)

    /** What alphabetical order compares: the title in kana (the title itself if there is no reading). */
    internal val sortKey: String = AozoraSearch.normalize(titleReading.ifEmpty { title })

    override fun toString(): String = "AozoraWork($id, $title, $author)"
}

/** The list of works, as the app reads it. */
class AozoraIndex(val generated: String, val works: List<AozoraWork>)

/** Reads the list `docs/aozora/index.json` (format version 1, see `scripts/aozora-index/README.md`). */
object AozoraIndexParser {
    const val SUPPORTED_VERSION = 1

    @Serializable
    private class IndexDto(
        val version: Int = 0,
        val generated: String = "",
        val authors: List<List<String>> = emptyList(),
        val works: List<WorkDto> = emptyList(),
    )

    @Serializable
    private class WorkDto(
        val i: String = "",
        val t: String = "",
        val r: String = "",
        val a: Int = -1,
        val s: String = "",
        val p: String = "",
        val u: String = "",
        /** Difficulty, optional (left out for works not graded). */
        val d: Int? = null,
    )

    private val json = Json { ignoreUnknownKeys = true }

    /** A text file the app downloads: `<card>/<file>`, letters, digits and `_.-` only, `.zip` or `.txt`. */
    private val FILE_PATH = Regex("[0-9]{1,8}/[A-Za-z0-9_.-]{1,120}\\.(zip|txt)", RegexOption.IGNORE_CASE)

    /**
     * The list in [input]. Works without a title, an ID or a usable file name are left out (the
     * list is data from the internet); an unreadable file or a newer format is an [AozoraException].
     */
    @OptIn(ExperimentalSerializationApi::class)
    fun parse(input: InputStream): AozoraIndex {
        val dto = try {
            json.decodeFromStream<IndexDto>(input)
        } catch (e: SerializationException) {
            throw AozoraException(AozoraException.Reason.BAD_DATA, "The work list is not readable", e)
        } catch (e: IllegalArgumentException) {
            throw AozoraException(AozoraException.Reason.BAD_DATA, "The work list is not readable", e)
        }
        if (dto.version > SUPPORTED_VERSION) {
            throw AozoraException(AozoraException.Reason.TOO_NEW, "The work list is version ${dto.version}")
        }
        if (dto.version < 1) throw AozoraException(AozoraException.Reason.BAD_DATA, "The work list has no version")
        val works = ArrayList<AozoraWork>(dto.works.size)
        for (w in dto.works) {
            if (w.i.isBlank() || w.t.isBlank() || !FILE_PATH.matches(w.u)) continue
            val person = dto.authors.getOrNull(w.a)
            works += AozoraWork(
                id = w.i,
                title = w.t,
                titleReading = w.r,
                author = person?.getOrNull(0).orEmpty(),
                authorReading = person?.getOrNull(1).orEmpty(),
                script = w.s,
                published = w.p,
                file = w.u,
                difficulty = w.d?.takeIf { it in 1..100 },
            )
        }
        return AozoraIndex(dto.generated, works)
    }
}

/** The order of the work list. Works without a difficulty come last in the difficulty orders. */
enum class AozoraSort {
    /** Nearest to a little above the reader's skill first; alphabetical until there is a skill. */
    BEST_FIT,
    EASIEST,
    ALPHABETICAL,
    HARDEST,
    ;

    /** [works] in this order. [skill] is the reader's skill on the 1–100 scale (`Skill`), or null. */
    fun apply(works: List<AozoraWork>, skill: Int?): List<AozoraWork> = when (this) {
        BEST_FIT -> if (skill == null) {
            ALPHABETICAL.apply(works, null)
        } else {
            val target = skill + Skill.STRETCH
            works.sortedWith(
                compareBy<AozoraWork, Int?>(nullsLast()) { w -> w.difficulty?.let { abs(it - target) } }.then(ALPHABETICAL_ORDER),
            )
        }
        EASIEST -> works.sortedWith(compareBy<AozoraWork, Int?>(nullsLast()) { it.difficulty }.then(ALPHABETICAL_ORDER))
        HARDEST -> works.sortedWith(compareBy<AozoraWork, Int?>(nullsLast(reverseOrder())) { it.difficulty }.then(ALPHABETICAL_ORDER))
        ALPHABETICAL -> works.sortedWith(ALPHABETICAL_ORDER)
    }

    private companion object {
        /** By title reading, then title, then ID: a total order, so the result never depends on the input order. */
        val ALPHABETICAL_ORDER: Comparator<AozoraWork> =
            compareBy<AozoraWork> { it.sortKey }.thenBy { it.title }.thenBy { it.id }
    }
}

/** Search in the work list. */
object AozoraSearch {
    /** Case, white space and the hiragana/katakana difference do not matter. */
    fun normalize(text: String): String =
        JapaneseText.toHiragana(text).lowercase(Locale.ROOT).filterNot { it.isWhitespace() }

    /**
     * The works whose title, author or their readings contain every word of [query] (words are
     * separated by white space; none: all works), in the list's order. Works in old kana (旧仮名)
     * only with [includeOld]. With a [sort] they come in that order ([skill] is for [AozoraSort.BEST_FIT]).
     */
    fun filter(
        works: List<AozoraWork>,
        query: String,
        includeOld: Boolean,
        sort: AozoraSort? = null,
        skill: Int? = null,
    ): List<AozoraWork> {
        val words = query.split(' ', '　', '\t', '\n').map(::normalize).filter { it.isNotEmpty() }
        val found = works.filter { work ->
            (includeOld || !work.oldKana) && words.all { work.searchKey.contains(it) }
        }
        return sort?.apply(found, skill) ?: found
    }
}
