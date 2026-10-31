package app.honmani.reader.lookup

/**
 * Word classes and intermediate grammatical forms, as bit flags.
 *
 * The first six match the `rules` field of Yomitan term banks. The rest describe forms that only
 * occur mid-deinflection: a て-form, a past form, a polite ます form, a 連用形 stem, or a form
 * that can only end a word.
 */
object WordClass {
    const val V1 = 1 shl 0
    const val V5 = 1 shl 1
    const val VK = 1 shl 2
    const val VS = 1 shl 3
    const val VZ = 1 shl 4
    const val ADJ_I = 1 shl 5
    const val TE = 1 shl 6
    const val PAST = 1 shl 7
    const val MASU = 1 shl 8
    const val STEM = 1 shl 9
    const val FINAL = 1 shl 10

    /** The text as tapped: it may be any form. */
    const val ANY = (1 shl 11) - 1

    /** Classes a dictionary entry can have. */
    const val DICTIONARY_FORMS = V1 or V5 or VK or VS or VZ or ADJ_I

    private val byRuleName = mapOf(
        "v1" to V1,
        "v5" to V5,
        "vk" to VK,
        "vs" to VS,
        "vz" to VZ,
        "adj-i" to ADJ_I,
    )

    /** Flags for a Yomitan `rules` string such as "v5" or "v1 vs". */
    fun fromRules(rules: String): Int =
        rules.split(' ').fold(0) { flags, rule -> flags or (byRuleName[rule] ?: 0) }
}

/** One step of grammar that was undone, e.g. [PAST]. Shown to the user, base form first. */
enum class Inflection {
    NEGATIVE, PAST, TE, POLITE, POTENTIAL, PASSIVE, POTENTIAL_OR_PASSIVE, CAUSATIVE, CAUSATIVE_SHORT, VOLITIONAL,
    IMPERATIVE, CONDITIONAL_BA, CONDITIONAL_TARA, TARI, WANT, STEM, PROGRESSIVE, COMPLETION,
    PREPARATION, RESULTING_STATE, ATTEMPT, GIVING, RECEIVING, REQUEST, DESIRE_OF_OTHERS,
    DIRECTION, WHILE, COMMAND_POLITE, TOO_MUCH, SEEMINGLY, EASY, HARD, WITHOUT, ADVERB, NOUN,
    SURU_VERB, NEGATIVE_VOLITIONAL, TE_WA, CLASSICAL,
}

/**
 * A possible dictionary form of the input. [conditions] says which word classes it may have;
 * [inflections] lists what was undone, from the dictionary form outwards.
 */
data class Deinflection(
    val text: String,
    val conditions: Int,
    val inflections: List<Inflection>,
)

private data class Rule(
    val inflected: String,
    val base: String,
    /** The rule applies to forms with any of these classes. */
    val conditionsIn: Int,
    /** Classes of the result. */
    val conditionsOut: Int,
    /** What the rule undoes, from the base outwards. */
    val inflections: List<Inflection>,
)

/**
 * Undoes Japanese verb and i-adjective inflection by suffix rules applied repeatedly, so
 * 食べさせられなかった yields 食べる (V1) via past, negative, passive and causative.
 *
 * Results are candidates: the dictionary decides which exist, and [WordClass] conditions decide
 * which entries they may match (食べ+る must be ichidan).
 */
object Deinflector {
    private const val MAX_STEPS = 12

    fun deinflect(text: String): List<Deinflection> {
        val results = mutableListOf(Deinflection(text, WordClass.ANY, emptyList()))
        val seen = hashSetOf(text to WordClass.ANY)
        var i = 0
        while (i < results.size) {
            val current = results[i++]
            if (current.inflections.size >= MAX_STEPS) continue
            for (rule in rules) {
                if (current.conditions and rule.conditionsIn == 0) continue
                if (!current.text.endsWith(rule.inflected)) continue
                val base = current.text.dropLast(rule.inflected.length) + rule.base
                if (base.isEmpty() || !seen.add(base to rule.conditionsOut)) continue
                results += Deinflection(base, rule.conditionsOut, rule.inflections + current.inflections)
            }
        }
        return results
    }

    private val rules: List<Rule> = buildList {
        val V1 = WordClass.V1
        val V5 = WordClass.V5
        val VK = WordClass.VK
        val VS = WordClass.VS
        val VZ = WordClass.VZ
        val ADJ = WordClass.ADJ_I
        val TE = WordClass.TE
        val PAST = WordClass.PAST
        val MASU = WordClass.MASU
        val STEM = WordClass.STEM
        val FINAL = WordClass.FINAL

        fun rule(
            inflected: String,
            base: String,
            conditionsIn: Int,
            conditionsOut: Int,
            vararg inflections: Inflection,
        ) = add(Rule(inflected, base, conditionsIn, conditionsOut, inflections.toList()))

        // The negatives of older and spoken Japanese on the negative stem: 知らぬ, 知らん,
        // 知らねば (must), 知らざる.
        fun literaryNegatives(stem: String, base: String, out: Int) {
            rule(stem + "ぬ", base, FINAL, out, Inflection.NEGATIVE)
            rule(stem + "ん", base, FINAL, out, Inflection.NEGATIVE)
            rule(stem + "ねば", base, FINAL, out, Inflection.NEGATIVE, Inflection.CONDITIONAL_BA)
            rule(stem + "ざる", base, FINAL, out, Inflection.NEGATIVE)
        }

        // Ichidan: drop る and add the ending.
        fun ichidan(stem: String, base: String, out: Int) {
            rule(stem + "ない", base, ADJ, out, Inflection.NEGATIVE)
            rule(stem + "ず", base, FINAL, out, Inflection.WITHOUT)
            literaryNegatives(stem, base, out)
            rule(stem + "まい", base, FINAL, out, Inflection.NEGATIVE_VOLITIONAL)
            rule(base + "まい", base, FINAL, out, Inflection.NEGATIVE_VOLITIONAL)
            rule(stem + "た", base, PAST, out, Inflection.PAST)
            rule(stem + "て", base, TE, out, Inflection.TE)
            rule(stem, base, STEM, out, Inflection.STEM)
            rule(stem + "ろ", base, FINAL, out, Inflection.IMPERATIVE)
            rule(stem + "よ", base, FINAL, out, Inflection.IMPERATIVE)
            rule(stem + "れば", base, FINAL, out, Inflection.CONDITIONAL_BA)
            rule(stem + "りゃ", base, FINAL, out, Inflection.CONDITIONAL_BA)
            rule(stem + "よう", base, FINAL, out, Inflection.VOLITIONAL)
            rule(stem + "られる", base, V1, out, Inflection.POTENTIAL_OR_PASSIVE)
            rule(stem + "れる", base, V1, out, Inflection.POTENTIAL)
            rule(stem + "させる", base, V1, out, Inflection.CAUSATIVE)
            rule(stem + "さす", base, V5, out, Inflection.CAUSATIVE_SHORT)
        }
        ichidan("", "る", V1)
        // 来る written with the kanji conjugates like an ichidan verb.
        ichidan("来", "来る", VK)

        // Godan: one row of the kana table per dictionary-form ending.
        data class GodanRow(
            val u: String,
            val a: String,
            val i: String,
            val e: String,
            val o: String,
            val te: String,
            val ta: String,
        )
        val godanRows = listOf(
            GodanRow("う", "わ", "い", "え", "お", "って", "った"),
            GodanRow("く", "か", "き", "け", "こ", "いて", "いた"),
            GodanRow("ぐ", "が", "ぎ", "げ", "ご", "いで", "いだ"),
            GodanRow("す", "さ", "し", "せ", "そ", "して", "した"),
            GodanRow("つ", "た", "ち", "て", "と", "って", "った"),
            GodanRow("ぬ", "な", "に", "ね", "の", "んで", "んだ"),
            GodanRow("ぶ", "ば", "び", "べ", "ぼ", "んで", "んだ"),
            GodanRow("む", "ま", "み", "め", "も", "んで", "んだ"),
            GodanRow("る", "ら", "り", "れ", "ろ", "って", "った"),
        )
        for (row in godanRows) {
            rule(row.a + "ない", row.u, ADJ, V5, Inflection.NEGATIVE)
            rule(row.a + "ず", row.u, FINAL, V5, Inflection.WITHOUT)
            literaryNegatives(row.a, row.u, V5)
            rule(row.u + "まい", row.u, FINAL, V5, Inflection.NEGATIVE_VOLITIONAL)
            rule(row.a + "れる", row.u, V1, V5, Inflection.PASSIVE)
            rule(row.a + "せる", row.u, V1, V5, Inflection.CAUSATIVE)
            rule(row.a + "す", row.u, V5, V5, Inflection.CAUSATIVE_SHORT)
            rule(row.i, row.u, STEM, V5, Inflection.STEM)
            rule(row.e + "る", row.u, V1, V5, Inflection.POTENTIAL)
            rule(row.e + "ば", row.u, FINAL, V5, Inflection.CONDITIONAL_BA)
            // Spoken ば: 行きゃ, 分かりゃ (not for う verbs, whose い takes no ゃ).
            if (row.u != "う") rule(row.i + "ゃ", row.u, FINAL, V5, Inflection.CONDITIONAL_BA)
            rule(row.e, row.u, FINAL, V5, Inflection.IMPERATIVE)
            rule(row.o + "う", row.u, FINAL, V5, Inflection.VOLITIONAL)
            rule(row.te, row.u, TE, V5, Inflection.TE)
            rule(row.ta, row.u, PAST, V5, Inflection.PAST)
        }
        // 行く is the one く verb with って/った.
        for (iku in listOf("行く", "いく", "逝く", "往く")) {
            val stem = iku.dropLast(1)
            rule(stem + "って", iku, TE, V5, Inflection.TE)
            rule(stem + "った", iku, PAST, V5, Inflection.PAST)
        }
        // Honorific verbs (なさる, くださる, いらっしゃる, おっしゃる, ござる) have い-stems.
        rule("い", "る", STEM, V5, Inflection.STEM)

        // 来る in kana.
        rule("こない", "くる", ADJ, VK, Inflection.NEGATIVE)
        rule("こず", "くる", FINAL, VK, Inflection.WITHOUT)
        rule("きた", "くる", PAST, VK, Inflection.PAST)
        rule("きて", "くる", TE, VK, Inflection.TE)
        rule("き", "くる", STEM, VK, Inflection.STEM)
        rule("こい", "くる", FINAL, VK, Inflection.IMPERATIVE)
        rule("来い", "来る", FINAL, VK, Inflection.IMPERATIVE)
        rule("くれば", "くる", FINAL, VK, Inflection.CONDITIONAL_BA)
        rule("くりゃ", "くる", FINAL, VK, Inflection.CONDITIONAL_BA)
        rule("こよう", "くる", FINAL, VK, Inflection.VOLITIONAL)
        rule("こられる", "くる", V1, VK, Inflection.POTENTIAL_OR_PASSIVE)
        rule("これる", "くる", V1, VK, Inflection.POTENTIAL)
        rule("こさせる", "くる", V1, VK, Inflection.CAUSATIVE)
        literaryNegatives("こ", "くる", VK)
        rule("くるまい", "くる", FINAL, VK, Inflection.NEGATIVE_VOLITIONAL)
        rule("こまい", "くる", FINAL, VK, Inflection.NEGATIVE_VOLITIONAL)

        // する, and nouns used as する verbs (勉強する -> 勉強 with class vs).
        rule("しない", "する", ADJ, VS, Inflection.NEGATIVE)
        rule("せず", "する", FINAL, VS, Inflection.WITHOUT)
        rule("した", "する", PAST, VS, Inflection.PAST)
        rule("して", "する", TE, VS, Inflection.TE)
        rule("し", "する", STEM, VS, Inflection.STEM)
        rule("しろ", "する", FINAL, VS, Inflection.IMPERATIVE)
        rule("せよ", "する", FINAL, VS, Inflection.IMPERATIVE)
        rule("すれば", "する", FINAL, VS, Inflection.CONDITIONAL_BA)
        rule("すりゃ", "する", FINAL, VS, Inflection.CONDITIONAL_BA)
        rule("しよう", "する", FINAL, VS, Inflection.VOLITIONAL)
        rule("される", "する", V1, VS, Inflection.PASSIVE)
        rule("させる", "する", V1, VS, Inflection.CAUSATIVE)
        rule("できる", "する", V1, VS, Inflection.POTENTIAL)
        literaryNegatives("せ", "する", VS)
        rule("するまい", "する", FINAL, VS, Inflection.NEGATIVE_VOLITIONAL)
        rule("すまい", "する", FINAL, VS, Inflection.NEGATIVE_VOLITIONAL)
        rule("する", "", VS, VS, Inflection.SURU_VERB)

        // ずる verbs (信ずる).
        rule("じない", "ずる", ADJ, VZ, Inflection.NEGATIVE)
        rule("じた", "ずる", PAST, VZ, Inflection.PAST)
        rule("じて", "ずる", TE, VZ, Inflection.TE)
        rule("じ", "ずる", STEM, VZ, Inflection.STEM)
        rule("ずれば", "ずる", FINAL, VZ, Inflection.CONDITIONAL_BA)
        rule("じよう", "ずる", FINAL, VZ, Inflection.VOLITIONAL)
        rule("じろ", "ずる", FINAL, VZ, Inflection.IMPERATIVE)
        rule("ぜよ", "ずる", FINAL, VZ, Inflection.IMPERATIVE)
        // Literary negatives: 信ぜず, 信ぜぬ (also 信じず, 信じぬ).
        for (stem in listOf("ぜ", "じ")) {
            rule(stem + "ず", "ずる", FINAL, VZ, Inflection.WITHOUT)
            literaryNegatives(stem, "ずる", VZ)
        }

        // i-adjectives, including ない and たい forms produced above.
        rule("くない", "い", ADJ, ADJ, Inflection.NEGATIVE)
        // 忘れないで (do not forget): ない with で, so 忘れない continues to 忘れる.
        rule("ないで", "ない", TE, ADJ, Inflection.TE)
        rule("かった", "い", PAST, ADJ, Inflection.PAST)
        rule("くて", "い", TE, ADJ, Inflection.TE)
        rule("ければ", "い", FINAL, ADJ, Inflection.CONDITIONAL_BA)
        rule("かろう", "い", FINAL, ADJ, Inflection.VOLITIONAL)
        rule("く", "い", FINAL, ADJ, Inflection.ADVERB)
        rule("さ", "い", FINAL, ADJ, Inflection.NOUN)
        rule("そう", "い", FINAL, ADJ, Inflection.SEEMINGLY)
        rule("すぎる", "い", V1, ADJ, Inflection.TOO_MUCH)
        // Spoken contractions: なきゃ / なけりゃ (なければ), なくちゃ (なくては).
        rule("きゃ", "い", FINAL, ADJ, Inflection.CONDITIONAL_BA)
        rule("けりゃ", "い", FINAL, ADJ, Inflection.CONDITIONAL_BA)
        rule("くちゃ", "い", FINAL, ADJ, Inflection.TE_WA)
        rule("くっちゃ", "い", FINAL, ADJ, Inflection.TE_WA)
        // The classical attributive of older books: 無き人, 美しき. (The final form 高し is left
        // out: after a kanji it also reads katakana シ, as in 赤シャツ.)
        rule("き", "い", FINAL, ADJ, Inflection.CLASSICAL)

        // Polite ます, which conjugates on its own.
        rule("ます", "", MASU, STEM, Inflection.POLITE)
        rule("ません", "ます", FINAL, MASU, Inflection.NEGATIVE)
        rule("ました", "ます", PAST, MASU, Inflection.PAST)
        rule("ませんでした", "ます", PAST, MASU, Inflection.NEGATIVE, Inflection.PAST)
        rule("ましょう", "ます", FINAL, MASU, Inflection.VOLITIONAL)
        rule("まして", "ます", TE, MASU, Inflection.TE)

        // Endings on the 連用形 stem.
        rule("たい", "", ADJ, STEM, Inflection.WANT)
        rule("たがる", "", V5, STEM, Inflection.DESIRE_OF_OTHERS)
        rule("ながら", "", FINAL, STEM, Inflection.WHILE)
        rule("なさい", "", FINAL, STEM, Inflection.COMMAND_POLITE)
        rule("すぎる", "", V1, STEM, Inflection.TOO_MUCH)
        rule("そう", "", FINAL, STEM, Inflection.SEEMINGLY)
        rule("やすい", "", ADJ, STEM, Inflection.EASY)
        rule("にくい", "", ADJ, STEM, Inflection.HARD)

        // Conditional and listing forms built on the past.
        rule("たら", "た", FINAL, PAST, Inflection.CONDITIONAL_TARA)
        rule("だら", "だ", FINAL, PAST, Inflection.CONDITIONAL_TARA)
        rule("たり", "た", FINAL, PAST, Inflection.TARI)
        rule("だり", "だ", FINAL, PAST, Inflection.TARI)

        // Auxiliaries after the て-form, and their contractions.
        for ((te, voiced) in listOf("て" to false, "で" to true)) {
            rule(te + "いる", te, V1, TE, Inflection.PROGRESSIVE)
            rule(te + "る", te, V1, TE, Inflection.PROGRESSIVE)
            rule(te + "しまう", te, V5, TE, Inflection.COMPLETION)
            rule(if (voiced) "じゃう" else "ちゃう", te, V5, TE, Inflection.COMPLETION)
            rule(te + "おく", te, V5, TE, Inflection.PREPARATION)
            rule(if (voiced) "どく" else "とく", te, V5, TE, Inflection.PREPARATION)
            rule(te + "ある", te, V5, TE, Inflection.RESULTING_STATE)
            rule(te + "みる", te, V1, TE, Inflection.ATTEMPT)
            rule(te + "くれる", te, V1, TE, Inflection.GIVING)
            rule(te + "あげる", te, V1, TE, Inflection.GIVING)
            rule(te + "もらう", te, V5, TE, Inflection.RECEIVING)
            rule(te + "ください", te, FINAL, TE, Inflection.REQUEST)
            rule(te + "ほしい", te, ADJ, TE, Inflection.DESIRE_OF_OTHERS)
            rule(te + "くる", te, VK, TE, Inflection.DIRECTION)
            rule(te + "いく", te, V5, TE, Inflection.DIRECTION)
            rule(te + "は", te, FINAL, TE, Inflection.TE_WA)
            rule(if (voiced) "じゃ" else "ちゃ", te, FINAL, TE, Inflection.TE_WA)
        }
    }
}
