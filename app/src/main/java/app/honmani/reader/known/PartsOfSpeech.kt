package app.honmani.reader.known

/**
 * Which words the quick quiz may ask, by the word classes the installed dictionaries give them
 * (JMdict codes: `n`, `v1`, `prt`). Particles, auxiliaries and fragments such as んです are known
 * to everyone who got this far; asking them says nothing about the reader's vocabulary.
 */
object PartsOfSpeech {
    /** A glossary object that marks a word class, as Jitendex writes it: `{"class":"tag","code":"n","content":"part-of-speech-info"}`. */
    private val CLASS_OBJECT = Regex("""\{[^\{\}]*"content"\s*:\s*"part-of-speech-info"[^\{\}]*\}""")
    private val CODE = Regex(""""code"\s*:\s*"([^"]+)"""")

    private val CONTENT = Regex("""n|n-adv|n-t|vs(-.+)?|v(1|2|4|5|z|k|r|n|i|t)[^ ]*|v-unspec|adj-(i|ix|na|no|t|ku|shiku|nari|f)|adv|adv-to""")
    private val FUNCTION = setOf(
        "prt", "aux", "aux-v", "aux-adj", "conj", "cop", "exp", "suf", "pref", "int", "pn", "ctr", "n-suf", "n-pref",
    )

    /**
     * The word class codes of one dictionary entry: its definition tags (JMdict) and the classes
     * marked in its glossary (Jitendex). Empty for a dictionary without word classes.
     */
    fun codes(definitionTags: String?, glossaryJson: String): Set<String> {
        val codes = LinkedHashSet<String>()
        definitionTags?.split(' ')?.filterTo(codes) { it.isNotBlank() && it.toIntOrNull() == null }
        if (glossaryJson.contains("part-of-speech-info")) {
            for (marked in CLASS_OBJECT.findAll(glossaryJson)) CODE.find(marked.value)?.let { codes += it.groupValues[1] }
        }
        return codes
    }

    /**
     * Whether a word with these [entries] (one set of codes per dictionary entry) may be asked: some
     * entry gives it a content class (noun, verb, adjective, adverb) and none gives it a function
     * class (particle, auxiliary, expression, suffix, pronoun...). Without any codes: no.
     */
    fun isAskable(entries: List<Set<String>>): Boolean {
        val all = entries.flatten()
        return all.any { CONTENT.matches(it) } && all.none { it in FUNCTION }
    }
}
