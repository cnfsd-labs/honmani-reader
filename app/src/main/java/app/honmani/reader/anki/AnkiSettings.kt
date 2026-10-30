package app.honmani.reader.anki

import android.content.SharedPreferences
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

    private val _googleVoice = MutableStateFlow(preferences.getString(KEY_GOOGLE_VOICE, null))

    /** The chosen Google voice; null for [GoogleCloudVoice.PREFERRED_VOICE]. */
    val googleVoice: StateFlow<String?> = _googleVoice.asStateFlow()

    private val _deck = MutableStateFlow(preferences.getString(KEY_DECK, null) ?: AnkiCard.DECK)

    /** The AnkiDroid deck new cards go to ("Honmani" unless another was chosen). */
    val deck: StateFlow<String> = _deck.asStateFlow()

    private val _hasGoogleKey = MutableStateFlow(devicePreferences.contains(KEY_GOOGLE_KEY))
    val hasGoogleKey: StateFlow<Boolean> = _hasGoogleKey.asStateFlow()

    fun setAudio(audio: CardAudio) {
        _audio.value = audio
        preferences.edit().putString(KEY_AUDIO, audio.name).apply()
    }

    fun setSentenceSpeed(speed: SentenceSpeed) {
        _sentenceSpeed.value = speed
        preferences.edit().putString(KEY_SENTENCE_SPEED, speed.name).apply()
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
        _audio.value = CardAudio.fromName(preferences.getString(KEY_AUDIO, null))
        _sentenceSpeed.value = SentenceSpeed.fromName(preferences.getString(KEY_SENTENCE_SPEED, null))
        _googleVoice.value = preferences.getString(KEY_GOOGLE_VOICE, null)
        _deck.value = preferences.getString(KEY_DECK, null) ?: AnkiCard.DECK
    }

    /** The note type whose card template Honmani has checked (see [AnkiExporter]). */
    var checkedNoteTypeId: Long?
        get() = devicePreferences.getLong(KEY_CHECKED_NOTE_TYPE, -1L).takeIf { it >= 0 }
        set(value) {
            devicePreferences.edit().putLong(KEY_CHECKED_NOTE_TYPE, value ?: -1L).apply()
        }

    /** Honmani's note type in AnkiDroid, remembered so it isn't looked up every time. */
    var noteTypeId: Long?
        get() = devicePreferences.getLong(KEY_NOTE_TYPE, -1L).takeIf { it >= 0 }
        set(value) {
            val editor = devicePreferences.edit()
            if (value == null) editor.remove(KEY_NOTE_TYPE) else editor.putLong(KEY_NOTE_TYPE, value)
            editor.apply()
        }

    companion object {
        const val KEY_AUDIO = "anki_audio"
        const val KEY_GOOGLE_KEY = "anki_google_key"
        const val KEY_GOOGLE_VOICE = "anki_google_voice"
        const val KEY_SENTENCE_SPEED = "anki_sentence_speed"
        const val KEY_DECK = "anki_deck"
        // "v2": the note type got Pitch and Frequency fields; an id kept from before is not it.
        const val KEY_NOTE_TYPE = "anki_note_type_v2"
        const val KEY_CHECKED_NOTE_TYPE = "anki_checked_note_type_v2"
    }
}
