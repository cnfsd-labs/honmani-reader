package app.honmani.reader.readaloud

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** How a book was last used: read on the page, or listened to as an audiobook. */
enum class SessionMode { READING, LISTENING }

/**
 * Remembers per book whether its last session included playing the audiobook, which decides if the
 * library's bubble says "Continue listening" or "Continue reading". Opening a book makes it
 * READING; the first playback in that session makes it LISTENING; opening it again without playing
 * makes it READING again. [read] and [write] keep the mode (the raw name) for a book's content hash.
 *
 * It also holds the one-shot request "play this book's audiobook as it opens", made when the reader
 * taps "Continue listening": in memory only, valid for [AUTOPLAY_MS] and used once.
 */
class SessionModes(
    private val read: (bookHash: String) -> String?,
    private val write: (bookHash: String, mode: String) -> Unit,
) {
    private val _changes = MutableStateFlow(0)

    /** Counts every change of a mode, so the library can look again. */
    val changes: StateFlow<Int> get() = _changes

    private var autoplayHash: String? = null
    private var autoplayAt = 0L

    fun mode(bookHash: String): SessionMode =
        if (read(bookHash) == SessionMode.LISTENING.name) SessionMode.LISTENING else SessionMode.READING

    /** The book was opened. */
    fun opened(bookHash: String) = set(bookHash, SessionMode.READING)

    /** The audiobook of the book is playing. */
    fun played(bookHash: String) = set(bookHash, SessionMode.LISTENING)

    /** The pill offers "Continue listening": last time it was listened to, and there is still audio and a place in it. */
    fun continueListening(bookHash: String, paired: Boolean, positionMs: Long): Boolean =
        paired && positionMs > 0 && mode(bookHash) == SessionMode.LISTENING

    /** The book that opens next should start its audiobook; [now] is a monotonic clock in ms. */
    @Synchronized
    fun armAutoplay(bookHash: String, now: Long) {
        autoplayHash = bookHash
        autoplayAt = now
    }

    /** True once if [armAutoplay] was called for [bookHash] less than [AUTOPLAY_MS] ago. */
    @Synchronized
    fun takeAutoplay(bookHash: String, now: Long): Boolean {
        val armed = autoplayHash == bookHash && now - autoplayAt in 0..AUTOPLAY_MS
        autoplayHash = null
        return armed
    }

    private fun set(bookHash: String, mode: SessionMode) {
        if (read(bookHash) == mode.name) return
        write(bookHash, mode.name)
        _changes.value++
    }

    companion object {
        const val AUTOPLAY_MS = 20_000L
    }
}
