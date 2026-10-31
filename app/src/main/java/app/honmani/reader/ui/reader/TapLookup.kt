package app.honmani.reader.ui.reader

import app.honmani.reader.data.words.WordMarksMode
import app.honmani.reader.lookup.RubySpan
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement

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
        /**
         * Parses a bridge message, or returns null for anything unknown or malformed. The `type`
         * is read once and picks the parser; each kind has its own length cap.
         */
        fun parse(message: String?): BridgeMessage? {
            if (message == null || message.length > MAX_MESSAGE_LENGTH) return null
            val obj = parseObject(message) ?: return null
            val type = (obj["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
            val compact by lazy { message.replace(" ", "") }
            return when (type) {
                "lookup" -> TapLookup.parse(message)?.let(::Lookup)
                "dismiss" -> Dismiss.takeIf { compact == DISMISS_MESSAGE }
                "bookShown" -> parseBookShown(message, obj)
                "reader.position" -> parsePosition(message, obj)
                "reader.background" -> parseBackground(message, obj)
                "marks.request" -> parseMarks(message, obj)
                "reader.activity" -> Activity.takeIf { compact == ACTIVITY_MESSAGE }
                "pageShown" -> SettingsShown.takeIf { compact == SETTINGS_SHOWN_MESSAGE }
                "reader.settings" -> OpenSettings.takeIf { compact == OPEN_SETTINGS_MESSAGE }
                else -> if (type.startsWith("library.")) LibraryMessage.parse(message)?.let(::Library) else null
            }
        }

        // JSON, not a regular expression: Android's regex engine (ICU) rejects patterns the
        // JVM accepts, such as an unescaped "}", and that closed the app at its first message.
        private val bookShownJson = Json { ignoreUnknownKeys = true }

        @Serializable
        private data class BookShownEnvelope(val type: String = "", val id: Long = 0)

        private fun parseObject(message: String): JsonObject? =
            try {
                bookShownJson.parseToJsonElement(message) as? JsonObject
            } catch (_: SerializationException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }

        private inline fun <reified T> decode(obj: JsonObject): T? =
            try {
                bookShownJson.decodeFromJsonElement<T>(obj)
            } catch (_: SerializationException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }

        private fun parseBookShown(message: String, obj: JsonObject): BookShown? {
            if (message.length > MAX_BOOK_SHOWN_LENGTH) return null
            val envelope = decode<BookShownEnvelope>(obj) ?: return null
            return if (envelope.id > 0) BookShown(envelope.id) else null
        }

        @Serializable
        private data class PositionEnvelope(
            val type: String = "",
            val id: Long = 0,
            val characters: Long = -1,
            val progress: Double = -1.0,
        )

        private fun parsePosition(message: String, obj: JsonObject): Position? {
            if (message.length > MAX_POSITION_LENGTH) return null
            val envelope = decode<PositionEnvelope>(obj) ?: return null
            val valid = envelope.id > 0 && envelope.characters >= 0
            val progress = envelope.progress.takeIf { it in 0.0..1.0 }?.toFloat()
            return if (valid) Position(envelope.id, envelope.characters, progress) else null
        }

        @Serializable
        private data class BackgroundEnvelope(val type: String = "", val color: String = "")

        private fun parseBackground(message: String, obj: JsonObject): Background? {
            if (message.length > MAX_BACKGROUND_LENGTH) return null
            val envelope = decode<BackgroundEnvelope>(obj) ?: return null
            val digits = envelope.color.removePrefix("#")
            val valid = envelope.color.startsWith("#") && digits.length == 6 &&
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

        private fun parseMarks(message: String, obj: JsonObject): Marks? {
            if (message.length > MAX_MARKS_LENGTH) return null
            val envelope = decode<MarksEnvelope>(obj) ?: return null
            if (envelope.blocks.size > MAX_MARKS_BLOCKS) return null
            val blocks = envelope.blocks.filter { it.text.length <= MAX_MARKS_TEXT }
            return Marks(envelope.requestId, WordMarksMode.fromStorage(envelope.mode), blocks)
        }

        /** Largest of the per-kind caps (library messages); anything longer is dropped unread. */
        private const val MAX_MESSAGE_LENGTH = 1_000_000
        private const val MAX_BOOK_SHOWN_LENGTH = 200
        private const val MAX_POSITION_LENGTH = 200
        private const val MAX_BACKGROUND_LENGTH = 200

        /** Generous bounds: the page sends up to about 6,000 characters, 4,000 per paragraph. */
        private const val MAX_MARKS_LENGTH = 64_000
        private const val MAX_MARKS_BLOCKS = 500
        private const val MAX_MARKS_TEXT = 8_000
        private const val DISMISS_MESSAGE = """{"type":"dismiss"}"""
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
    /** The sentence before [sentence] in the same paragraph (the bridge cuts it to 200 characters); empty if none or not sent. */
    val previousSentence: String = "",
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
                previousSentence = envelope.previousSentence,
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
            previousSentence.length <= MAX_SENTENCE_LENGTH &&
            sentenceOffset in 0..sentence.length

    @Serializable
    private data class Envelope(
        val type: String,
        val text: String = "",
        val sentence: String = "",
        val sentenceOffset: Int = 0,
        val ruby: List<RubyEnvelope> = emptyList(),
        val tap: Int = 0,
        val previousSentence: String = "",
    )

    @Serializable
    private data class RubyEnvelope(val start: Int, val end: Int, val reading: String)
}
