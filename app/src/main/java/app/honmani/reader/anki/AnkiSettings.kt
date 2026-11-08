package app.honmani.reader.anki

import android.content.SharedPreferences
import app.honmani.reader.data.words.Sentences
import app.honmani.reader.data.opds.CredentialCipher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Where the audio on Anki cards comes from. */
enum class CardAudio {
    OFF,

    /** The phone's own text-to-speech voice: free and offline, clearly synthetic. */
    PHONE,

    /** Google Cloud Text-to-Speech with the reader's API key: natural, needs an account. */
    GOOGLE,
    ;

    companion object {
        val DEFAULT = PHONE

        fun fromName(name: String?): CardAudio = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

/**
 * How fast a card's example sentence is spoken, as a speaking rate. The word itself is always
 * spoken at normal speed: it is short, and a slowed word sounds unnatural.
 */
enum class SentenceSpeed(val rate: Double) {
    NORMAL(1.0),
    SLOWER(0.85),
    SLOW(0.7),
    ;

    companion object {
        val DEFAULT = NORMAL

        fun fromName(name: String?): SentenceSpeed = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

/**
 * Settings for Anki cards, in shared preferences. The Google Cloud API key is encrypted with a
 * Keystore key ([CredentialCipher]) before it is stored.
 *
 * The reader's choices go to [preferences], which cloud backup keeps. The key and AnkiDroid's
 * note type ids go to [devicePreferences], which it doesn't: the Keystore key can't decrypt on
 * another phone, and the ids belong to this phone's AnkiDroid.
 */
class AnkiSettings(
    private val preferences: SharedPreferences,
    private val cipher: CredentialCipher,
    private val devicePreferences: SharedPreferences = preferences,
) {
    private val _audio = MutableStateFlow(CardAudio.fromName(preferences.getString(KEY_AUDIO, null)))
    val audio: StateFlow<CardAudio> = _audio.asStateFlow()

    private val _sentenceSpeed =
        MutableStateFlow(SentenceSpeed.fromName(preferences.getString(KEY_SENTENCE_SPEED, null)))
    val sentenceSpeed: StateFlow<SentenceSpeed> = _sentenceSpeed.asStateFlow()

    private val _enabledChoice = MutableStateFlow(readEnabledChoice())

    /**
     * Whether the reader chose to use Anki cards; null while they haven't (see [isEnabled]).
     * It is read as "contains", so an absent value stays "not chosen yet".
     */
    val enabledChoice: StateFlow<Boolean?> = _enabledChoice.asStateFlow()

    fun setEnabled(on: Boolean) {
        _enabledChoice.value = on
        preferences.edit().putBoolean(KEY_ENABLED, on).apply()
    }

    private fun readEnabledChoice(): Boolean? =
        if (preferences.contains(KEY_ENABLED)) preferences.getBoolean(KEY_ENABLED, true) else null

    private val _audiobookSentences = MutableStateFlow(preferences.getBoolean(KEY_AUDIOBOOK_SENTENCES, true))

    /**
     * Whether a card's sentence audio is cut from the book's paired audiobook, where there is one
     * and the sentence is found in it; otherwise (and if off) the chosen voice speaks it. On by
     * default: it changes only cards from books the reader paired with an audiobook.
     */
    val audiobookSentences: StateFlow<Boolean> = _audiobookSentences.asStateFlow()

    private val _googleVoice = MutableStateFlow(preferences.getString(KEY_GOOGLE_VOICE, null))

    /** The chosen Google voice; null for [GoogleCloudVoice.PREFERRED_VOICE]. */
    val googleVoice: StateFlow<String?> = _googleVoice.asStateFlow()

    private val _deck = MutableStateFlow(preferences.getString(KEY_DECK, null) ?: AnkiCard.DECK)

    /** The AnkiDroid deck new cards go to ("Honmani" unless another was chosen). */
    val deck: StateFlow<String> = _deck.asStateFlow()

    private val _cardStyle = MutableStateFlow(CardStyle.fromName(preferences.getString(KEY_CARD_STYLE, null)))

    /** Which note type new cards and the package use ([CardStyle.HONMANI] unless changed). */
    val cardStyle: StateFlow<CardStyle> = _cardStyle.asStateFlow()

    private val _customNoteTypeName = MutableStateFlow(preferences.getString(KEY_CUSTOM_NOTE_TYPE_NAME, null))

    /** The name of the reader's own note type for [CardStyle.CUSTOM]; null if none was chosen. */
    val customNoteTypeName: StateFlow<String?> = _customNoteTypeName.asStateFlow()

    private val _sentenceEnds = MutableStateFlow(Sentences.cleanEnds(preferences.getString(KEY_SENTENCE_ENDS, null)))

    /** The characters that end a sentence on cards, in highlights and in the sentence tint ([Sentences]). */
    val sentenceEnds: StateFlow<String> = _sentenceEnds.asStateFlow()

    private val _hasGoogleKey = MutableStateFlow(devicePreferences.contains(KEY_GOOGLE_KEY))
    val hasGoogleKey: StateFlow<Boolean> = _hasGoogleKey.asStateFlow()

    private val _knownFromAnkiOffered = MutableStateFlow(devicePreferences.getBoolean(KEY_KNOWN_OFFERED, false))

    /**
     * Whether "mark words from an Anki deck as known" needs no further offer in the Words list:
     * it was done once or the reader said no. The button in the settings stays.
     */
    val knownFromAnkiOffered: StateFlow<Boolean> = _knownFromAnkiOffered.asStateFlow()

    fun setKnownFromAnkiOffered(done: Boolean) {
        devicePreferences.edit().putBoolean(KEY_KNOWN_OFFERED, done).apply()
        _knownFromAnkiOffered.value = done
    }

    fun setAudio(audio: CardAudio) {
        _audio.value = audio
        preferences.edit().putString(KEY_AUDIO, audio.name).apply()
    }

    fun setSentenceSpeed(speed: SentenceSpeed) {
        _sentenceSpeed.value = speed
        preferences.edit().putString(KEY_SENTENCE_SPEED, speed.name).apply()
    }

    fun setAudiobookSentences(on: Boolean) {
        _audiobookSentences.value = on
        preferences.edit().putBoolean(KEY_AUDIOBOOK_SENTENCES, on).apply()
    }

    fun setCardStyle(style: CardStyle) {
        _cardStyle.value = style
        preferences.edit().putString(KEY_CARD_STYLE, style.name).apply()
    }

    /** The saved [FieldMapping] of the note type called [noteType]; empty if there is none. */
    fun fieldMapping(noteType: String): FieldMapping =
        FieldMapping.fromJson(preferences.getString(KEY_FIELD_MAPPINGS, null))[noteType] ?: FieldMapping.EMPTY

    /**
     * Chooses the reader's own note type [name] (its AnkiDroid id is [id]) with [mapping], and
     * switches new cards to it. The mapping is kept per note type name, so a choice made earlier
     * for another note type is still there.
     */
    fun setCustomNoteType(name: String, id: Long, mapping: FieldMapping) {
        val all = FieldMapping.fromJson(preferences.getString(KEY_FIELD_MAPPINGS, null)) + (name to mapping)
        // The id first: a kill in between leaves an id that resolveCustomNoteType does not trust
        // for the old name, never a new name with the old id.
        setNoteTypeId(CardStyle.CUSTOM, id)
        preferences.edit()
            .putString(KEY_FIELD_MAPPINGS, FieldMapping.toJson(all))
            .putString(KEY_CUSTOM_NOTE_TYPE_NAME, name)
            .putString(KEY_CARD_STYLE, CardStyle.CUSTOM.name)
            .apply()
        _customNoteTypeName.value = name
        _cardStyle.value = CardStyle.CUSTOM
    }

    /** Nothing usable in [ends] goes back to [Sentences.DEFAULT_ENDS]. */
    fun setSentenceEnds(ends: String) {
        val clean = Sentences.cleanEnds(ends)
        _sentenceEnds.value = clean
        preferences.edit().putString(KEY_SENTENCE_ENDS, clean).apply()
    }

    /** A blank name goes back to the "Honmani" deck. */
    fun setDeck(name: String) {
        val deck = name.trim().ifEmpty { AnkiCard.DECK }
        _deck.value = deck
        preferences.edit().putString(KEY_DECK, deck).apply()
    }

    fun setGoogleVoice(name: String?) {
        _googleVoice.value = name
        preferences.edit().putString(KEY_GOOGLE_VOICE, name).apply()
    }

    /** The stored API key; null if there is none or it can't be decrypted any more. */
    fun googleKey(): String? = devicePreferences.getString(KEY_GOOGLE_KEY, null)?.let(cipher::decrypt)

    /** Stores [key]; a blank key removes it. */
    fun setGoogleKey(key: String) {
        val trimmed = key.trim()
        if (trimmed.isEmpty()) {
            devicePreferences.edit().remove(KEY_GOOGLE_KEY).apply()
        } else {
            devicePreferences.edit().putString(KEY_GOOGLE_KEY, cipher.encrypt(trimmed)).apply()
        }
        _hasGoogleKey.value = trimmed.isNotEmpty()
    }

    /** The voice for new cards, or null for none. Google without a key falls back to the phone. */
    fun voice(phone: () -> CardVoice): CardVoice? = when (audio.value) {
        CardAudio.OFF -> null
        CardAudio.PHONE -> phone()
        CardAudio.GOOGLE -> googleKey()?.let { key ->
            GoogleCloudVoice(apiKey = key, voice = googleVoice.value ?: GoogleCloudVoice.PREFERRED_VOICE)
        } ?: phone()
    }

    /** Reads the reader's choices again (a backup was restored). */
    fun reload() {
        _enabledChoice.value = readEnabledChoice()
        _audio.value = CardAudio.fromName(preferences.getString(KEY_AUDIO, null))
        _sentenceSpeed.value = SentenceSpeed.fromName(preferences.getString(KEY_SENTENCE_SPEED, null))
        _audiobookSentences.value = preferences.getBoolean(KEY_AUDIOBOOK_SENTENCES, true)
        _googleVoice.value = preferences.getString(KEY_GOOGLE_VOICE, null)
        _deck.value = preferences.getString(KEY_DECK, null) ?: AnkiCard.DECK
        _cardStyle.value = CardStyle.fromName(preferences.getString(KEY_CARD_STYLE, null))
        _customNoteTypeName.value = preferences.getString(KEY_CUSTOM_NOTE_TYPE_NAME, null)
        _sentenceEnds.value = Sentences.cleanEnds(preferences.getString(KEY_SENTENCE_ENDS, null))
    }

    /** The note type whose card template Honmani has checked (see [AnkiExporter]). */
    var checkedNoteTypeId: Long?
        get() = devicePreferences.getLong(KEY_CHECKED_NOTE_TYPE, -1L).takeIf { it >= 0 }
        set(value) {
            devicePreferences.edit().putLong(KEY_CHECKED_NOTE_TYPE, value ?: -1L).apply()
        }

    /** Honmani's note type of [style] in AnkiDroid, remembered so it isn't looked up every time. */
    fun noteTypeId(style: CardStyle): Long? =
        devicePreferences.getLong(noteTypeKey(style), -1L).takeIf { it >= 0 }

    fun setNoteTypeId(style: CardStyle, id: Long?) {
        val editor = devicePreferences.edit()
        if (id == null) editor.remove(noteTypeKey(style)) else editor.putLong(noteTypeKey(style), id)
        editor.apply()
    }

    private fun noteTypeKey(style: CardStyle) = when (style) {
        CardStyle.HONMANI -> KEY_NOTE_TYPE
        CardStyle.SENTENCE -> KEY_SENTENCE_NOTE_TYPE
        CardStyle.CUSTOM -> KEY_CUSTOM_NOTE_TYPE
    }

    companion object {
        /**
         * Whether Add-to-Anki shows: the reader's [choice] if they made one, otherwise whether
         * AnkiDroid is [installed] (desktop Anki users turn it on themselves).
         */
        fun isEnabled(choice: Boolean?, installed: Boolean): Boolean = choice ?: installed

        const val KEY_ENABLED = "anki_enabled"
        const val KEY_AUDIO = "anki_audio"
        const val KEY_AUDIOBOOK_SENTENCES = "anki_audiobook_sentences"
        const val KEY_GOOGLE_KEY = "anki_google_key"
        const val KEY_GOOGLE_VOICE = "anki_google_voice"
        const val KEY_SENTENCE_SPEED = "anki_sentence_speed"
        // On this phone only: it is about this phone's AnkiDroid.
        const val KEY_KNOWN_OFFERED = "anki_known_offered"
        const val KEY_DECK = "anki_deck"
        const val KEY_CARD_STYLE = "anki_card_style"
        const val KEY_SENTENCE_ENDS = "anki_sentence_ends"
        // The reader's own note type is kept by name (and the mappings by name): backed up. Its id is not.
        const val KEY_CUSTOM_NOTE_TYPE_NAME = "anki_custom_note_type_name"
        const val KEY_FIELD_MAPPINGS = "anki_field_mappings"
        const val KEY_CUSTOM_NOTE_TYPE = "anki_custom_note_type"
        // "v2": the note type got Pitch and Frequency fields; an id kept from before is not it.
        const val KEY_NOTE_TYPE = "anki_note_type_v2"
        const val KEY_SENTENCE_NOTE_TYPE = "anki_sentence_note_type"
        const val KEY_CHECKED_NOTE_TYPE = "anki_checked_note_type_v2"
    }
}
