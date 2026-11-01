package app.honmani.reader.lookup

/**
 * Romaji for searching: kana written in Hepburn romaji, and a loose form in which the usual ways
 * of typing the same word meet (Hepburn or kunrei, long vowels written or not, は as wa, ん as
 * n or nn), so "toukyou", "tokyo" and "tôkyô" all find とうきょう.
 */
object Romaji {
    /** [text] with kana in romaji; other characters are kept. */
    fun fromKana(text: String): String {
        val kana = JapaneseText.toHiragana(text)
        val out = StringBuilder()
        var doubleNext = false
        var i = 0
        while (i < kana.length) {
            val c = kana[i]
            val next = kana.getOrNull(i + 1)
            var romaji = BASE[c]
            when {
                c == 'っ' -> {
                    doubleNext = true
                    i++
                    continue
                }
                c == 'ー' -> romaji = "-"
                romaji != null && next != null && next in SMALL_Y && romaji.endsWith("i") && romaji.length > 1 -> {
                    // きゃ kya, しゃ sha, ちゃ cha, じゃ ja
                    val stem = romaji.dropLast(1)
                    val glide = if (stem.endsWith("sh") || stem.endsWith("ch") || stem == "j") "" else "y"
                    romaji = stem + glide + SMALL_Y.getValue(next)
                    i++
                }
                romaji != null && next != null && next in SMALL_VOWEL && romaji.length > 1 -> {
                    // ふぁ fa, てぃ ti, ちぇ che, ゔぁ va
                    romaji = romaji.dropLast(1) + SMALL_VOWEL.getValue(next)
                    i++
                }
                romaji == "u" && next != null && next in SMALL_VOWEL -> {
                    romaji = "w" + SMALL_VOWEL.getValue(next) // うぃ wi
                    i++
                }
                c == 'ん' && next != null && (BASE[next]?.first()?.let { it in "aiueoy" } == true) -> romaji = "n'"
            }
            val piece = romaji ?: c.lowercaseChar().toString()
            if (doubleNext && piece.first().isLetter() && piece.first() !in "aiueon") {
                out.append(if (piece.startsWith("ch")) 't' else piece.first())
            }
            doubleNext = false
            out.append(piece)
            i++
        }
        return out.toString()
    }

    /** True if [query] is typed in Latin letters, so a romaji search makes sense. */
    fun isRomaji(query: String): Boolean =
        query.any { it in 'a'..'z' || it in 'A'..'Z' } && query.all { it.code < 0x250 || it.isWhitespace() }

    /** The loose form of romaji [text], for comparing: apply it to both sides. */
    fun loose(text: String): String {
        var s = buildString {
            for (c in text.lowercase()) {
                val plain = MACRONS[c] ?: c
                if (plain in 'a'..'z' || plain in '0'..'9') append(plain)
            }
        }
        for ((from, to) in LOOSE_RULES) s = s.replace(from, to)
        return s
    }

    /**
     * Romaji typed for a search, turned into kana the way a Japanese keyboard does it (Hepburn,
     * kunrei or wapuro; nn, n' and n before a consonant are ん, m before b/p/m too, a doubled
     * consonant or tch is っ, - is ー, a macron doubles its vowel). Kana and other characters
     * are kept, so "たb" works too. The letters of a syllable still being typed stay open:
     * [Typed.pending] lists the kana they can become ("tab" is た plus ば, び, ぶ, べ or ぼ).
     */
    fun toKana(text: String): Typed {
        val input = buildString {
            for (c in text.lowercase()) {
                val vowel = MACRONS[c]
                if (vowel == null) append(c) else append(vowel).append(if (vowel == 'o') 'u' else vowel)
            }
        }
        val out = StringBuilder()
        var i = 0
        while (i < input.length) {
            val c = input[i]
            val next = input.getOrNull(i + 1)
            val afterNext = input.getOrNull(i + 2)
            when {
                c !in 'a'..'z' -> {
                    when {
                        c == '-' -> out.append('ー')
                        c == '\'' && out.endsWith("ん") -> Unit
                        else -> out.append(c)
                    }
                    i++
                }
                // nn: ん, or ん before a syllable of the n row as Hepburn writes it (konnichi).
                c == 'n' && next == 'n' -> {
                    out.append('ん')
                    i += if (afterNext != null && (afterNext in VOWEL_LETTERS || afterNext == 'y')) 1 else 2
                }
                c == 'n' && next != null && next !in VOWEL_LETTERS && next != 'y' -> {
                    out.append('ん')
                    i++
                }
                c == 'm' && next != null && next in "bpm" -> {
                    out.append('ん')
                    i++
                }
                c == 't' && next == 'c' && afterNext == 'h' -> {
                    out.append('っ')
                    i++
                }
                c == next && c !in VOWEL_LETTERS && c != 'n' -> {
                    out.append('っ')
                    i++
                }
                else -> {
                    val length = (minOf(MAX_INPUT, input.length - i) downTo 1)
                        .firstOrNull { INPUT.containsKey(input.substring(i, i + it)) }
                    if (length != null) {
                        out.append(INPUT.getValue(input.substring(i, i + length)))
                        i += length
                    } else {
                        val rest = input.substring(i)
                        val pending = completions(rest)
                        if (pending.isNotEmpty()) return Typed(out.toString(), pending)
                        out.append(c)
                        i++
                    }
                }
            }
        }
        return Typed(out.toString(), emptyList())
    }

    /** What [toKana] made of a text: [kana], then one of [pending] if not empty. */
    data class Typed(val kana: String, val pending: List<String>) {
        /** True if the text ends on a finished syllable. */
        val complete: Boolean get() = pending.isEmpty()
    }

    /** Kana the unfinished letters [rest] can still become, none the prefix of another. */
    private fun completions(rest: String): List<String> {
        val kana = INPUT.filterKeys { it.length > rest.length && it.startsWith(rest) }.values.distinct().sorted()
        return kana.filter { candidate -> kana.none { it != candidate && candidate.startsWith(it) } }
    }

    private const val VOWEL_LETTERS = "aiueo"
    private const val MAX_INPUT = 4

    /** Romaji as typed, to kana. */
    private val INPUT: Map<String, String> = buildMap {
        val vowels = listOf("a", "i", "u", "e", "o")
        fun row(consonant: String, kana: String) =
            vowels.forEachIndexed { i, v -> if (kana[i] != '・') put(consonant + v, kana[i].toString()) }
        fun glide(consonant: String, kana: String) {
            put(consonant + "a", kana + "ゃ")
            put(consonant + "u", kana + "ゅ")
            put(consonant + "o", kana + "ょ")
        }
        row("", "あいうえお")
        row("k", "かきくけこ")
        row("g", "がぎぐげご")
        row("s", "さしすせそ")
        row("z", "ざじずぜぞ")
        row("t", "たちつてと")
        row("d", "だぢづでど")
        row("n", "なにぬねの")
        row("h", "はひふへほ")
        row("b", "ばびぶべぼ")
        row("p", "ぱぴぷぺぽ")
        row("m", "まみむめも")
        row("y", "や・ゆ・よ")
        row("r", "らりるれろ")
        row("w", "わ・う・を")
        row("x", "ぁぃぅぇぉ")
        row("l", "ぁぃぅぇぉ")
        for ((consonant, kana) in listOf(
            "k" to "き", "g" to "ぎ", "s" to "し", "z" to "じ", "j" to "じ", "t" to "ち", "c" to "ち",
            "d" to "ぢ", "n" to "に", "h" to "ひ", "b" to "び", "p" to "ぴ", "m" to "み", "r" to "り",
        )) glide(consonant + "y", kana)
        glide("sh", "し")
        glide("ch", "ち")
        glide("j", "じ")
        listOf("x", "l").forEach { put(it + "ya", "ゃ"); put(it + "yu", "ゅ"); put(it + "yo", "ょ"); put(it + "tu", "っ"); put(it + "tsu", "っ") }
        put("shi", "し"); put("chi", "ち"); put("tsu", "つ"); put("ji", "じ"); put("fu", "ふ")
        put("she", "しぇ"); put("che", "ちぇ"); put("je", "じぇ")
        put("fa", "ふぁ"); put("fi", "ふぃ"); put("fe", "ふぇ"); put("fo", "ふぉ")
        put("va", "ゔぁ"); put("vi", "ゔぃ"); put("vu", "ゔ"); put("ve", "ゔぇ"); put("vo", "ゔぉ")
        put("thi", "てぃ"); put("dhi", "でぃ"); put("twu", "とぅ"); put("dwu", "どぅ")
        put("tsa", "つぁ"); put("tse", "つぇ"); put("tso", "つぉ")
        put("wi", "うぃ"); put("we", "うぇ"); put("ye", "いぇ")
        put("nn", "ん"); put("n'", "ん")
    }

    private val BASE: Map<Char, String> = buildMap {
        fun row(kana: String, vararg romaji: String) = kana.forEachIndexed { i, c -> put(c, romaji[i]) }
        row("あいうえお", "a", "i", "u", "e", "o")
        row("ぁぃぅぇぉ", "a", "i", "u", "e", "o")
        row("かきくけこ", "ka", "ki", "ku", "ke", "ko")
        row("がぎぐげご", "ga", "gi", "gu", "ge", "go")
        row("さしすせそ", "sa", "shi", "su", "se", "so")
        row("ざじずぜぞ", "za", "ji", "zu", "ze", "zo")
        row("たちつてと", "ta", "chi", "tsu", "te", "to")
        row("だぢづでど", "da", "ji", "zu", "de", "do")
        row("なにぬねの", "na", "ni", "nu", "ne", "no")
        row("はひふへほ", "ha", "hi", "fu", "he", "ho")
        row("ばびぶべぼ", "ba", "bi", "bu", "be", "bo")
        row("ぱぴぷぺぽ", "pa", "pi", "pu", "pe", "po")
        row("まみむめも", "ma", "mi", "mu", "me", "mo")
        row("やゆよ", "ya", "yu", "yo")
        row("ゃゅょ", "ya", "yu", "yo")
        row("らりるれろ", "ra", "ri", "ru", "re", "ro")
        row("わゐゑをんゎゔ", "wa", "i", "e", "o", "n", "wa", "vu")
    }

    private val SMALL_Y = mapOf('ゃ' to "a", 'ゅ' to "u", 'ょ' to "o")
    private val SMALL_VOWEL = mapOf('ぁ' to "a", 'ぃ' to "i", 'ぅ' to "u", 'ぇ' to "e", 'ぉ' to "o")

    private val MACRONS = mapOf(
        'ā' to 'a', 'ī' to 'i', 'ū' to 'u', 'ē' to 'e', 'ō' to 'o',
        'â' to 'a', 'î' to 'i', 'û' to 'u', 'ê' to 'e', 'ô' to 'o',
    )

    /** Applied in this order; the same order on both sides keeps them comparable. */
    private val LOOSE_RULES = listOf(
        Regex("tch") to "cch",
        Regex("sy") to "sh",
        Regex("ty") to "ch",
        Regex("[zj]y") to "j",
        Regex("si") to "shi",
        Regex("ti") to "chi",
        Regex("tu") to "tsu",
        Regex("(?<![cs])hu") to "fu",
        Regex("[zd]i") to "ji",
        Regex("du") to "zu",
        Regex("m(?=[bp])") to "n",
        Regex("nn") to "n",
        // Particles and long vowels, written in many ways.
        Regex("ha") to "wa",
        Regex("wo") to "o",
        Regex("he") to "e",
        Regex("ou") to "o",
        Regex("([aiueo])\\1") to "$1",
    )
}
