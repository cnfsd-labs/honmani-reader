package app.honmani.reader.ui.reader

import app.honmani.reader.data.words.WordMarksMode
import app.honmani.reader.lookup.RubySpan
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** A message from `assets/honmani/tap-bridge.js` or `library-bridge.js`. */
sealed interface BridgeMessage {
    data class Lookup(val tap: TapLookup) : BridgeMessage

    /** A tap on the book away from text: close the lookup panel. */
    data object Dismiss : BridgeMessage

    data class Library(val message: LibraryMessage) : BridgeMessage

    /** An opened book is laid out and on screen (`assets/honmani/book-shown.js`). */
    data class BookShown(val id: Long) : BridgeMessage

    /**
     * ttu saved the reading position of a book after a page turn (`assets/honmani/reading-activity.js`):
     * [characters] read up to it and, if ttu has it, where it is in the book ([progress], 0 to 1).
     */
    data class Position(val id: Long, val characters: Long, val progress: Float? = null) : BridgeMessage

    /** The reader bar's Settings entry: open Honmani's reader settings (`assets/honmani/reader-settings.js`). */
    data object OpenSettings : BridgeMessage

    /** ttu's settings are drawn on screen (`assets/honmani/book-shown.js`). */
    data object SettingsShown : BridgeMessage

    /** The reader touched or scrolled the book (`assets/honmani/reading-activity.js`). */
    data object Activity : BridgeMessage

    /** The book page's background colour, opaque ARGB (`assets/honmani/book-frame.js`). */
    data class Background(val color: Int) : BridgeMessage

    /** Paragraphs near the screen whose words to mark (`assets/honmani/word-marks.js`). */
    data class Marks(val requestId: Long, val mode: WordMarksMode, val blocks: List<MarksBlock>) : BridgeMessage

    /** A paragraph's text without furigana, and the page's id for it. */
    @Serializable
    data class MarksBlock(val id: Long = 0, val text: String = "")

    companion object {
        /** Parses a bridge message, or returns null for anything unknown or malformed. */
        fun parse(message: String?): BridgeMessage? =
            TapLookup.parse(message)?.let(::Lookup)
                ?: Dismiss.takeIf { message?.replace(" ", "") == """{"type":"dismiss"}""" }
                ?: LibraryMessage.parse(message)?.let(::Library)
                ?: parseBookShown(message)
                ?: parsePosition(message)
                ?: parseBackground(message)
                ?: parseMarks(message)
                ?: Activity.takeIf { message?.replace(" ", "") == ACTIVITY_MESSAGE }
                ?: SettingsShown.takeIf { message?.replace(" ", "") == SETTINGS_SHOWN_MESSAGE }
                ?: OpenSettings.takeIf { message?.replace(" ", "") == OPEN_SETTINGS_MESSAGE }

        // JSON, not a regular expression: Android's regex engine (ICU) rejects patterns the
        // JVM accepts, such as an unescaped "}", and that closed the app at its first message.
        private val bookShownJson = Json { ignoreUnknownKeys = true }

        @Serializable
        private data class BookShownEnvelope(val type: String = "", val id: Long = 0)

        private fun parseBookShown(message: String?): BookShown? {
            if (message == null || message.length > MAX_BOOK_SHOWN_LENGTH || "bookShown" !in message) return null
            val envelope = try {
                bookShownJson.decodeFromString<BookShownEnvelope>(message)
            } catch (_: SerializationException) {
                return null
            } catch (_: IllegalArgumentException) {
                return null
            }
            return if (envelope.type == "bookShown" && envelope.id > 0) BookShown(envelope.id) else null
        }

        @Serializable
        private data class PositionEnvelope(
            val type: String = "",
            val id: Long = 0,
            val characters: Long = -1,
            val progress: Double = -1.0,
        )

        private fun parsePosition(message: String?): Position? {
            if (message == null || message.length > MAX_BOOK_SHOWN_LENGTH || "reader.position" !in message) return null
            val envelope = try {
                bookShownJson.decodeFromString<PositionEnvelope>(message)
            } catch (_: SerializationException) {
                return null
            } catch (_: IllegalArgumentException) {
                return null
            }
            val valid = envelope.type == "reader.position" && envelope.id > 0 && envelope.characters >= 0
            val progress = envelope.progress.takeIf { it in 0.0..1.0 }?.toFloat()
            return if (valid) Position(envelope.id, envelope.characters, progress) else null
        }

        @Serializable
        private data class BackgroundEnvelope(val type: String = "", val color: String = "")

        private fun parseBackground(message: String?): Background? {
            if (message == null || message.length > MAX_BOOK_SHOWN_LENGTH || "reader.background" !in message) return null
            val envelope = try {
                bookShownJson.decodeFromString<BackgroundEnvelope>(message)
            } catch (_: SerializationException) {
                return null
            } catch (_: IllegalArgumentException) {
                return null
            }
            val digits = envelope.color.removePrefix("#")
            val valid = envelope.type == "reader.background" && envelope.color.startsWith("#") && digits.length == 6 &&
                digits.all { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' }
            return if (valid) Background(digits.toInt(16) or 0xFF000000.toInt()) else null
        }

        @Serializable
        private data class MarksEnvelope(
            val type: String = "",
            val requestId: Long = 0,
            val mode: String = "",
            val blocks: List<MarksBlock> = emptyList(),
        )

        private fun parseMarks(message: String?): Marks? {
            if (message == null || message.length > MAX_MARKS_LENGTH || "marks.request" !in message) return null
            val envelope = try {
                bookShownJson.decodeFromString<MarksEnvelope>(message)
            } catch (_: SerializationException) {
                return null
            } catch (_: IllegalArgumentException) {
                return null
            }
            if (envelope.type != "marks.request" || envelope.blocks.size > MAX_MARKS_BLOCKS) return null
            val blocks = envelope.blocks.filter { it.text.length <= MAX_MARKS_TEXT }
            return Marks(envelope.requestId, WordMarksMode.fromStorage(envelope.mode), blocks)
        }

        private const val MAX_BOOK_SHOWN_LENGTH = 200

        /** Generous bounds: the page sends up to about 6,000 characters, 4,000 per paragraph. */
        private const val MAX_MARKS_LENGTH = 64_000
        private const val MAX_MARKS_BLOCKS = 500
        private const val MAX_MARKS_TEXT = 8_000
        private const val SETTINGS_SHOWN_MESSAGE = """{"type":"pageShown","path":"/settings"}"""
        private const val OPEN_SETTINGS_MESSAGE = """{"type":"reader.settings"}"""
        private const val ACTIVITY_MESSAGE = """{"type":"reader.activity"}"""
    }
}

/** A tap on book text, as reported by `assets/honmani/tap-bridge.js`. */
data class TapLookup(
    /** Up to 20 characters starting at the tapped one; the lookup scans prefixes of this. */
    val text: String,
    /** The sentence containing the tap, for card context. */
    val sentence: String,
    /** Index of [text] within [sentence]. */
    val sentenceOffset: Int,
    /** Furigana over ruby bases inside [text], sorted. */
    val ruby: List<RubySpan> = emptyList(),
    /** The bridge's number of this tap, echoed in the highlight; 0 if unnumbered. */
    val tapNumber: Int = 0,
) {
    companion object {
        // Generous bounds; the bridge sends at most 20 code points and 400 sentence chars.
        private const val MAX_TEXT_LENGTH = 64
        private const val MAX_SENTENCE_LENGTH = 1_000
        private const val MAX_RUBY_SPANS = 20
        private const val MAX_READING_LENGTH = 32

        private val json = Json { ignoreUnknownKeys = true }

        /**
         * Parses a bridge message, or returns null for anything else. Messages come from page
         * script on the reader origin, which includes book content, so they are untrusted.
         */
        fun parse(message: String?): TapLookup? {
            if (message == null || message.length > 4 * MAX_SENTENCE_LENGTH) return null
            val envelope = try {
                json.decodeFromString<Envelope>(message)
            } catch (_: SerializationException) {
                return null
            } catch (_: IllegalArgumentException) {
                return null
            }
            if (envelope.type != "lookup") return null
            val lookup = TapLookup(
                envelope.text, envelope.sentence, envelope.sentenceOffset,
                tapNumber = envelope.tap.coerceAtLeast(0),
            )
            if (!lookup.isValid()) return null
            // Furigana only helps ranking: drop it, not the lookup, if its indexes do not fit.
            val ruby = envelope.ruby.map { RubySpan(it.start, it.end, it.reading) }
            return if (isValidRuby(ruby, lookup.text.length)) lookup.copy(ruby = ruby) else lookup
        }

        private fun isValidRuby(ruby: List<RubySpan>, textLength: Int): Boolean =
            ruby.size <= MAX_RUBY_SPANS &&
                ruby.all { it.start >= 0 && it.end in (it.start + 1)..textLength } &&
                ruby.all { it.reading.isNotBlank() && it.reading.length <= MAX_READING_LENGTH } &&
                ruby.zipWithNext().all { (a, b) -> a.end <= b.start }
    }

    private fun isValid(): Boolean =
        text.isNotBlank() &&
            text.length <= MAX_TEXT_LENGTH &&
            sentence.length <= MAX_SENTENCE_LENGTH &&
            sentenceOffset in 0..sentence.length

    @Serializable
    private data class Envelope(
        val type: String,
        val text: String = "",
        val sentence: String = "",
        val sentenceOffset: Int = 0,
        val ruby: List<RubyEnvelope> = emptyList(),
        val tap: Int = 0,
    )

    @Serializable
    private data class RubyEnvelope(val start: Int, val end: Int, val reading: String)
}
