package app.honmani.reader.data.dictionary

/** A dictionary Honmani offers to download, from the URL the dictionary itself publishes. */
data class CatalogDictionary(
    val id: String,
    val name: String,
    /** Installed dictionaries whose title starts with this are this one (titles carry dates). */
    val titlePrefix: String,
    /** The `downloadUrl` from the dictionary's own index.json; it always points to the latest. */
    val url: String,
    /** Approximate download size, for display. */
    val sizeMb: Int,
    val license: String,
    val recommended: Boolean = false,
    val kind: Kind = Kind.TERMS,
    val format: DictionaryFileFormat = DictionaryFileFormat.YOMITAN,
) {
    /** What the dictionary adds to a lookup, for grouping in the list. */
    enum class Kind {
        TERMS,

        /** Definitions in Japanese (国語), for advanced learners. */
        JAPANESE,
        KANJI,
        FREQUENCY,
        PITCH,

        /** Names of people, places and companies (JMnedict): looked up after ordinary words. */
        NAMES,
    }
}

/**
 * Dictionaries offered for one-tap download. The URLs are GitHub "latest release" links, which
 * stay the same across releases. If one breaks, the download fails with a message; importing a
 * file still works.
 */
object DictionaryCatalog {
    /** Title prefix of JMnedict, whose entries rank below ordinary words of the same match. */
    const val NAMES_TITLE = "JMnedict"

    /** Whether a dictionary of this title holds names (JMnedict). */
    fun isNames(title: String): Boolean =
        title.startsWith(NAMES_TITLE) && title.getOrNull(NAMES_TITLE.length)?.isLetter() != true

    val dictionaries = listOf(
        CatalogDictionary(
            id = "jitendex",
            name = "Jitendex",
            titlePrefix = "Jitendex",
            url = "https://github.com/stephenmk/stephenmk.github.io/releases/latest/download/jitendex-yomitan.zip",
            sizeMb = 39,
            license = "CC BY-SA 4.0",
            recommended = true,
        ),
        CatalogDictionary(
            id = "jmdict-english",
            name = "JMdict (English)",
            titlePrefix = "JMdict",
            url = "https://github.com/yomidevs/jmdict-yomitan/releases/latest/download/JMdict_english.zip",
            sizeMb = 16,
            license = "CC BY-SA 4.0 (EDRDG)",
        ),
        CatalogDictionary(
            id = "jmnedict",
            name = "JMnedict (names)",
            titlePrefix = NAMES_TITLE,
            // Same publisher and release as JMdict; its index.json title is "JMnedict [date]".
            url = "https://github.com/yomidevs/jmdict-yomitan/releases/latest/download/JMnedict.zip",
            sizeMb = 11,
            license = "CC BY-SA 4.0 (EDRDG)",
            kind = CatalogDictionary.Kind.NAMES,
        ),
        CatalogDictionary(
            id = "wiktionary-japanese",
            name = "Wiktionary (Japanese)",
            titlePrefix = "wty-ja-ja",
            // Japanese Wiktionary converted by wty (yomidevs); the downloadUrl of its index.json.
            url = "https://huggingface.co/datasets/daxida/wty-release/resolve/main/latest/dict/ja/ja/wty-ja-ja.zip",
            sizeMb = 14,
            license = "CC BY-SA 4.0 (Wiktionary)",
            kind = CatalogDictionary.Kind.JAPANESE,
        ),
        CatalogDictionary(
            id = "kanjidic-english",
            name = "KANJIDIC (English)",
            titlePrefix = "KANJIDIC",
            url = "https://github.com/yomidevs/jmdict-yomitan/releases/latest/download/KANJIDIC_english.zip",
            sizeMb = 1,
            license = "CC BY-SA 4.0 (EDRDG)",
            recommended = true,
            kind = CatalogDictionary.Kind.KANJI,
        ),
        CatalogDictionary(
            id = "jiten-frequency",
            name = "Jiten frequency",
            titlePrefix = "Jiten",
            // Jiten's own download link (also Yomitan's recommended one); always the latest list.
            url = "https://api.jiten.moe/api/frequency-list/download?downloadType=yomitan",
            sizeMb = 8,
            license = "CC BY-SA 4.0 (jiten.moe)",
            recommended = true,
            kind = CatalogDictionary.Kind.FREQUENCY,
        ),
        CatalogDictionary(
            id = "kanjium-pitch",
            name = "Kanjium pitch accents",
            titlePrefix = KanjiumAccents.TITLE,
            // The source list itself; Honmani converts it on import (see KanjiumAccents).
            url = "https://raw.githubusercontent.com/mifunetoshiro/kanjium/master/data/source_files/raw/accents.txt",
            sizeMb = 3,
            license = "CC BY-SA 4.0 (Kanjium, Uros O.)",
            recommended = true,
            kind = CatalogDictionary.Kind.PITCH,
            format = DictionaryFileFormat.KANJIUM_ACCENTS,
        ),
    )

    /**
     * What the first-start screen downloads with one tap, in this order (the first one's
     * definitions show first): definitions in English, or in Japanese with English after them,
     * then kanji, word frequency and pitch accent.
     */
    fun starterPack(japanese: Boolean): List<CatalogDictionary> {
        val ids = if (japanese) {
            listOf("wiktionary-japanese", "jitendex", "kanjidic-english", "jiten-frequency", "kanjium-pitch")
        } else {
            listOf("jitendex", "kanjidic-english", "jiten-frequency", "kanjium-pitch")
        }
        return ids.map { id -> dictionaries.first { it.id == id } }
    }

    /** "Jitendex.org [date]" is Jitendex, but not Jiten: the prefix must end at a non-letter. */
    fun isInstalled(dictionary: CatalogDictionary, installedTitles: Collection<String>): Boolean =
        installedTitles.any { title ->
            title.startsWith(dictionary.titlePrefix) &&
                title.getOrNull(dictionary.titlePrefix.length)?.isLetter() != true
        }
}
