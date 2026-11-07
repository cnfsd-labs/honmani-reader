package app.honmani.reader.data.settings

import android.content.SharedPreferences
import app.honmani.reader.data.review.ReviewLimit
import app.honmani.reader.data.review.ReviewRules
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Settings → Flashcards: how many new cards and reviews a day, what the front shows, when a word counts as known. */
class ReviewSettings(private val preferences: SharedPreferences) {
    private val _newPerDay = MutableStateFlow(storedNewPerDay())
    val newPerDay: StateFlow<Int> = _newPerDay.asStateFlow()

    private val _reviewsPerDay = MutableStateFlow(storedReviewsPerDay())

    /** Most reviews a day ([ReviewLimit.CHOICES]); [ReviewLimit.NO_LIMIT]: all that are due. */
    val reviewsPerDay: StateFlow<Int> = _reviewsPerDay.asStateFlow()

    private val _showSentence = MutableStateFlow(preferences.getBoolean(KEY_SENTENCE, true))

    /** The front shows the saved sentence with the word marked; off: the word alone. */
    val showSentence: StateFlow<Boolean> = _showSentence.asStateFlow()

    private val _speakers = MutableStateFlow(preferences.getBoolean(KEY_SPEAKERS, true))

    /** The card shows speaker buttons for the word and the sentence. */
    val speakers: StateFlow<Boolean> = _speakers.asStateFlow()

    private val _knownAfterDays = MutableStateFlow(storedKnownAfter())

    /** Memory stability in days from which a word counts as known: one of [KNOWN_AFTER_CHOICES]. */
    val knownAfterDays: StateFlow<Int> = _knownAfterDays.asStateFlow()

    fun setNewPerDay(value: Int) {
        _newPerDay.value = value.coerceIn(0, MAX_NEW_PER_DAY)
        preferences.edit().putInt(KEY_NEW_PER_DAY, _newPerDay.value).apply()
    }

    fun setReviewsPerDay(value: Int) {
        _reviewsPerDay.value = value.coerceAtLeast(0)
        preferences.edit().putInt(KEY_REVIEWS_PER_DAY, _reviewsPerDay.value).apply()
    }

    fun setShowSentence(value: Boolean) {
        _showSentence.value = value
        preferences.edit().putBoolean(KEY_SENTENCE, value).apply()
    }

    fun setSpeakers(value: Boolean) {
        _speakers.value = value
        preferences.edit().putBoolean(KEY_SPEAKERS, value).apply()
    }

    fun setKnownAfterDays(value: Int) {
        _knownAfterDays.value = if (value in KNOWN_AFTER_CHOICES) value else ReviewRules.DEFAULT_KNOWN_AFTER_DAYS
        preferences.edit().putInt(KEY_KNOWN_AFTER, _knownAfterDays.value).apply()
    }

    /** Reads the stored values again (a backup was restored). */
    fun reload() {
        _newPerDay.value = storedNewPerDay()
        _reviewsPerDay.value = storedReviewsPerDay()
        _showSentence.value = preferences.getBoolean(KEY_SENTENCE, true)
        _speakers.value = preferences.getBoolean(KEY_SPEAKERS, true)
        _knownAfterDays.value = storedKnownAfter()
    }

    private fun storedNewPerDay() = preferences.getInt(KEY_NEW_PER_DAY, DEFAULT_NEW_PER_DAY).coerceIn(0, MAX_NEW_PER_DAY)

    private fun storedReviewsPerDay() = preferences.getInt(KEY_REVIEWS_PER_DAY, ReviewLimit.DEFAULT).coerceAtLeast(0)

    private fun storedKnownAfter() = preferences.getInt(KEY_KNOWN_AFTER, ReviewRules.DEFAULT_KNOWN_AFTER_DAYS)
        .takeIf { it in KNOWN_AFTER_CHOICES } ?: ReviewRules.DEFAULT_KNOWN_AFTER_DAYS

    companion object {
        const val KEY_NEW_PER_DAY = "review_new_per_day"
        const val KEY_REVIEWS_PER_DAY = "review_reviews_per_day"
        const val KEY_SENTENCE = "review_show_sentence"
        const val KEY_SPEAKERS = "review_speakers"
        const val KEY_KNOWN_AFTER = "review_known_after_days"
        const val DEFAULT_NEW_PER_DAY = 10
        const val MAX_NEW_PER_DAY = 200
        val NEW_PER_DAY_CHOICES = listOf(0, 5, 10, 20, 30, 50)
        val KNOWN_AFTER_CHOICES = listOf(14, 21, 30)
    }
}
