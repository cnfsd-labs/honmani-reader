package app.honmani.reader.anki

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CardMappingTest {
    private val content = CardContent(
        expression = "取り扱う",
        reading = "とりあつかう",
        senses = listOf(listOf("to handle"), listOf("to treat")),
        sentence = "荷物を丁寧に取り扱った。",
        surfaceStart = 6,
        surfaceEnd = 11,
        bookTitle = "猫 & 犬",
        pitches = listOf(0),
        frequency = "Jiten #5000",
    )

    /** The fields of Lapis (donkuri). */
    private val lapis = listOf(
        "Expression", "ExpressionFurigana", "ExpressionReading", "ExpressionAudio", "SelectionText",
        "MainDefinition", "DefinitionPicture", "Sentence", "SentenceFurigana", "SentenceAudio", "Picture",
        "Glossary", "Hint", "IsWordAndSentenceCard", "IsClickCard", "IsSentenceCard", "IsAudioCard",
        "PitchPosition", "PitchCategories", "Frequency", "FreqSort", "MiscInfo",
    )

    private val jpmn = listOf(
        "Key", "Word", "WordReading", "WordReadingHiragana", "WordAudio", "PAOverride", "PAOverrideText",
        "AJTWordPitch", "PrimaryDefinition", "PrimaryDefinitionPicture", "SecondaryDefinition",
        "ExtraDefinitions", "Sentence", "SentenceReading", "SentenceAudio", "AltDisplay", "Picture",
        "Comment", "Hint", "FrequenciesStylized",
    )

    @Test
    fun lapisIsRecognised_andMapped() {
        val guess = MappingPresets.detect(lapis)

        assertEquals(PresetName.LAPIS, guess.preset)
        assertEquals(
            mapOf(
                "Expression" to CardSource.WORD,
                "ExpressionFurigana" to CardSource.FURIGANA_PLAIN,
                "ExpressionReading" to CardSource.READING,
                "ExpressionAudio" to CardSource.WORD_AUDIO,
                "MainDefinition" to CardSource.MEANING,
                "Sentence" to CardSource.SENTENCE_MARKED,
                "SentenceFurigana" to CardSource.SENTENCE_PLAIN,
                "SentenceAudio" to CardSource.SENTENCE_AUDIO,
                "Frequency" to CardSource.FREQUENCY,
                "MiscInfo" to CardSource.BOOK,
            ),
            guess.mapping.sources,
        )
        // The pitch position, the hint and the picture fields stay empty.
        assertNull(guess.mapping.sources["PitchPosition"])
        assertNull(guess.mapping.sources["Glossary"])
    }

    @Test
    fun lapisGlossaryTakesTheMeaningsOnlyWithoutAMainDefinition() {
        val without = lapis - "MainDefinition"

        val guess = MappingPresets.detect(without)

        assertEquals(PresetName.LAPIS, guess.preset)
        assertEquals(CardSource.MEANING, guess.mapping.sources["Glossary"])
    }

    @Test
    fun jpmnIsRecognised_andMapped() {
        val guess = MappingPresets.detect(jpmn)

        assertEquals(PresetName.JPMN, guess.preset)
        assertEquals(CardSource.WORD, guess.mapping.sources["Word"])
        assertEquals(CardSource.FURIGANA_PLAIN, guess.mapping.sources["WordReading"])
        assertEquals(CardSource.READING, guess.mapping.sources["WordReadingHiragana"])
        assertEquals(CardSource.WORD_AUDIO, guess.mapping.sources["WordAudio"])
        assertEquals(CardSource.SENTENCE_MARKED, guess.mapping.sources["Sentence"])
        assertEquals(CardSource.SENTENCE_PLAIN, guess.mapping.sources["SentenceReading"])
        assertEquals(CardSource.SENTENCE_AUDIO, guess.mapping.sources["SentenceAudio"])
        assertEquals(CardSource.MEANING, guess.mapping.sources["PrimaryDefinition"])
        assertNull(guess.mapping.sources["Picture"])
        assertNull(guess.mapping.sources["Key"])
    }

    @Test
    fun otherNoteTypesAreGuessedByFieldName_ignoringCaseAndSpaces() {
        val basic = MappingPresets.detect(listOf("Front", "Back"))
        assertNull(basic.preset)
        assertEquals(mapOf("Front" to CardSource.WORD, "Back" to CardSource.MEANING), basic.mapping.sources)

        val own = MappingPresets.guess(listOf(" 単語 ", "READING", "Definition", "Sentence", "Book", "Audio", "Pitch", "Frequency", "Notes"))
        assertEquals(
            mapOf(
                " 単語 " to CardSource.WORD,
                "READING" to CardSource.READING,
                "Definition" to CardSource.MEANING,
                "Sentence" to CardSource.SENTENCE_MARKED,
                "Book" to CardSource.BOOK,
                "Audio" to CardSource.WORD_AUDIO,
                "Pitch" to CardSource.PITCH,
                "Frequency" to CardSource.FREQUENCY,
            ),
            own.sources,
        )
        assertEquals(FieldMapping.EMPTY, MappingPresets.guess(listOf("Foo", "Bar")))
    }

    @Test
    fun everySourceRendersTheTextOfHonmanisOwnFields() {
        val rendered = CardSource.renderAll(content, wordAudio = "[sound:w.mp3]", sentenceAudio = "[sound:s.mp3]")
        val honmani = AnkiCard.FIELDS.zip(AnkiCard.fields(content, "[sound:w.mp3]", "[sound:s.mp3]")).toMap()

        assertEquals(CardSource.entries.toSet(), rendered.keys)
        assertEquals("取り扱う", CardSource.WORD.render(content))
        assertEquals("とりあつかう", CardSource.READING.render(content))
        assertEquals(honmani["Furigana"], rendered[CardSource.FURIGANA])
        assertEquals("取[と]り 扱[あつか]う", rendered[CardSource.FURIGANA_PLAIN])
        assertEquals("<ol><li>to handle</li><li>to treat</li></ol>", rendered[CardSource.MEANING])
        assertEquals("荷物を丁寧に<b>取り扱った</b>。", rendered[CardSource.SENTENCE_MARKED])
        assertEquals("荷物を丁寧に取り扱った。", rendered[CardSource.SENTENCE_PLAIN])
        assertEquals("猫 &amp; 犬", rendered[CardSource.BOOK])
        assertEquals("[sound:w.mp3]", rendered[CardSource.WORD_AUDIO])
        assertEquals("[sound:s.mp3]", rendered[CardSource.SENTENCE_AUDIO])
        assertEquals(honmani["Pitch"], rendered[CardSource.PITCH])
        assertEquals("Jiten #5000", rendered[CardSource.FREQUENCY])
    }

    @Test
    fun aPlainSentenceIsTheWordWhenNoSentenceWasStored() {
        val bare = content.copy(sentence = "", surfaceStart = 0, surfaceEnd = 0)

        assertEquals("取り扱う", CardSource.SENTENCE_PLAIN.render(bare))
        assertEquals("", CardSource.SENTENCE_MARKED.render(bare))
    }

    @Test
    fun fillPutsEachSourceIntoItsFields_andLeavesTheRestEmpty() {
        val names = listOf("Front", "Kana", "Also front", "Notes")
        val mapping = FieldMapping(mapOf("Front" to CardSource.WORD, "Kana" to CardSource.READING, "Also front" to CardSource.WORD))

        assertEquals(listOf("取り扱う", "とりあつかう", "取り扱う", ""), mapping.fill(names, content, "", ""))
        assertEquals("Front", mapping.wordField(names))
        assertNull(FieldMapping(mapOf("Kana" to CardSource.READING)).wordField(names))
    }

    @Test
    fun theMappingsSurviveTheSettingsText_withOddCharacters() {
        val odd = mapOf(
            "Lapis \"JP\" \\ 日本語" to FieldMapping(
                mapOf("Field: \u001f \"q\" \\ {x}\n" to CardSource.WORD, "単語" to CardSource.SENTENCE_MARKED, "emoji 🐱" to CardSource.PITCH),
            ),
            "Empty" to FieldMapping.EMPTY,
        )

        assertEquals(odd, FieldMapping.fromJson(FieldMapping.toJson(odd)))
    }

    @Test
    fun damagedSettingsTextGivesNoMappings_andUnknownSourcesAreDropped() {
        assertEquals(emptyMap<String, FieldMapping>(), FieldMapping.fromJson(null))
        assertEquals(emptyMap<String, FieldMapping>(), FieldMapping.fromJson("{oops"))
        assertEquals(emptyMap<String, FieldMapping>(), FieldMapping.fromJson("[1,2]"))
        assertEquals(emptyMap<String, FieldMapping>(), FieldMapping.fromJson("{\"T\":5}"))

        val read = FieldMapping.fromJson("{\"T\":{\"A\":\"WORD\",\"B\":\"FROM_THE_FUTURE\",\"C\":7}}")
        assertEquals(mapOf("A" to CardSource.WORD), read.getValue("T").sources)
        assertTrue(CardStyle.CUSTOM in CardStyle.entries)
    }
}
