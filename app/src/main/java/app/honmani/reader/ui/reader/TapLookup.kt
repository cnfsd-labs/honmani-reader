package app.honmani.reader.ui.reader

import app.honmani.reader.data.library.HIGHLIGHT_TEXT_MAX
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

    /** An edge tap turns the page: [next] or the previous one (`assets/honmani/tap-bridge.js`). */
    data class TurnPage(val next: Boolean) : BridgeMessage

    data class Library(val message: LibraryMessage) : BridgeMessage

    /** A tap on a picture in the book: its [bytes] (PNG, JPEG, WebP or GIF) for the zoom viewer (`assets/honmani/tap-bridge.js`). */
    class Image(val bytes: ByteArray) : BridgeMessage

    /** An opened book is laid out and on screen (`assets/honmani/book-shown.js`). */
    data class BookShown(val id: Long) : BridgeMessage

    /**
     * ttu saved the reading position of a book after a page turn (`assets/honmani/reading-activity.js`):
     * [characters] read up to it and, if ttu has it, where it is in the book ([progress], 0 to 1).
     */
    data class Position(val id: Long, val characters: Long, val progress: Float? = null) : BridgeMessage

    /** The reader bar's Settings entry: open Honmani's reader settings (`assets/honmani/reader-settings.js`). */
    data object OpenSettings : BridgeMessage

    /** A tap on the footer along the bottom of the page: open the reader's bottom menu (`assets/honmani/reader-menu.js`). */
    data object OpenMenu : BridgeMessage

    /** A long swipe up on the page opens the same menu (`reader-menu.js`), unless the lookup panel is open. */
    data object OpenMenuBySwipe : BridgeMessage

    /** ttu's settings are drawn on screen (`assets/honmani/book-shown.js`). */
    data object SettingsShown : BridgeMessage

    /** The reader touched or scrolled the book (`assets/honmani/reading-activity.js`). */
    data object Activity : BridgeMessage

    /** A long press just marked its first word (`assets/honmani/tap-bridge.js`): the phone ticks once. */
    data object LongPressMark : BridgeMessage

    /** The book page's background colour, opaque ARGB (`assets/honmani/book-frame.js`). */
    data class Background(val color: Int) : BridgeMessage

    /** Paragraphs near the screen whose words to mark (`assets/honmani/word-marks.js`). */
    data class Marks(
        val requestId: Long,
        val mode: WordMarksMode,
        val blocks: List<MarksBlock>,
        /** Also mark the words the reader knows, to hide their furigana. */
        val knownFurigana: Boolean = false,
        /** Also mark the sentences with exactly one unknown word. */
        val unknownSentences: Boolean = false,
        /** Also mark the words whose review cards are due today. */
        val dueMarks: Boolean = false,
    ) : BridgeMessage

    /** The book page is up and wants its saved highlights (`assets/honmani/highlights.js`). */
    data class HighlightsRequest(val bookId: Long) : BridgeMessage

    /** One sentence for read aloud: its number and the text to speak (`assets/honmani/read-aloud.js`). */
    @Serializable
    data class ReadAloudItem(val i: Int = -1, val text: String = "")

    /** The page's next sentences for read aloud; [end] is null, "section" or "book" once the text ran out. */
    data class ReadAloudSentences(val gen: Int, val items: List<ReadAloudItem>, val end: String?) : BridgeMessage

    /** Read aloud asked for text that is no longer in the page. */
    data class ReadAloudLost(val gen: Int) : BridgeMessage

    /** The audiobook's passage could not be brought on screen by turning pages (`assets/honmani/audiobook.js`). */
    data class AudiobookLost(val gen: Int) : BridgeMessage

    /** The reader turned the page or scrolled by hand while a passage is followed, or the page took the view along again (`sentence-follow.js`). */
    data class FollowState(val detached: Boolean) : BridgeMessage

    /** A paragraph's text without furigana, and the page's id for it. */
    @Serializable
    data class MarksBlock(val id: Long = 0, val text: String = "")

    companion object {
        /**
         * Parses a bridge message, or returns null for anything unknown or malformed. The `type`
         * is read once and picks the parser; each kind has its own length cap.
         */
        fun parse(message: String?): BridgeMessage? {
            if (message == null) return null
            // Only a picture is longer than the usual cap, and the page writes its type first.
            if (message.length > MAX_MESSAGE_LENGTH && !(message.length <= MAX_IMAGE_LENGTH && message.startsWith(IMAGE_PREFIX))) return null
            val obj = parseObject(message) ?: return null
            val type = (obj["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
            val compact by lazy { message.replace(" ", "") }
            return when (type) {
                "lookup" -> TapLookup.parse(message)?.let(::Lookup)
                "turnPage" -> parseTurnPage(obj)
                "image" -> parseImage(obj)
                "dismiss" -> Dismiss.takeIf { compact == DISMISS_MESSAGE }
                "bookShown" -> parseBookShown(message, obj)
                "reader.position" -> parsePosition(message, obj)
                "reader.background" -> parseBackground(message, obj)
                "marks.request" -> parseMarks(message, obj)
                "highlights.request" -> parseHighlightsRequest(message, obj)
                "readAloud.sentences" -> parseReadAloudSentences(message, obj)
                "readAloud.lost" -> parseReadAloudLost(message, obj)
                "audiobook.lost" -> parseAudiobookLost(message, obj)
                "follow.detached" -> FollowState(true).takeIf { compact == FOLLOW_DETACHED_MESSAGE }
                "follow.attached" -> FollowState(false).takeIf { compact == FOLLOW_ATTACHED_MESSAGE }
                "reader.activity" -> Activity.takeIf { compact == ACTIVITY_MESSAGE }
                "reader.longPress" -> LongPressMark.takeIf { compact == LONG_PRESS_MESSAGE }
                "pageShown" -> SettingsShown.takeIf { compact == SETTINGS_SHOWN_MESSAGE }
                "reader.settings" -> OpenSettings.takeIf { compact == OPEN_SETTINGS_MESSAGE }
                "reader.menu" -> OpenMenu.takeIf { compact == OPEN_MENU_MESSAGE }
                "reader.menuSwipe" -> OpenMenuBySwipe.takeIf { compact == OPEN_MENU_SWIPE_MESSAGE }
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

        private fun parseTurnPage(obj: JsonObject): TurnPage? =
            (obj["next"] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.let {
                when (it) {
                    "true" -> TurnPage(true)
                    "false" -> TurnPage(false)
                    else -> null
                }
            }

        private fun parseImage(obj: JsonObject): Image? {
            val data = (obj["data"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
            return PictureData.decode(data)?.let(::Image)
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
        private data class HighlightsRequestEnvelope(val type: String = "", val bookId: Long = 0)

        private fun parseHighlightsRequest(message: String, obj: JsonObject): HighlightsRequest? {
            if (message.length > MAX_BOOK_SHOWN_LENGTH) return null
            val envelope = decode<HighlightsRequestEnvelope>(obj) ?: return null
            return if (envelope.bookId > 0) HighlightsRequest(envelope.bookId) else null
        }

        @Serializable
        private data class ReadAloudSentencesEnvelope(
            val type: String = "",
            val gen: Int = -1,
            val items: List<ReadAloudItem> = emptyList(),
            val end: String? = null,
        )

        private fun parseReadAloudSentences(message: String, obj: JsonObject): ReadAloudSentences? {
            if (message.length > MAX_READ_ALOUD_LENGTH) return null
            val envelope = decode<ReadAloudSentencesEnvelope>(obj) ?: return null
            val valid = envelope.gen >= 0 && envelope.items.size <= MAX_READ_ALOUD_ITEMS &&
                envelope.items.all { it.i >= 0 && it.text.length <= MAX_READ_ALOUD_TEXT } &&
                (envelope.end == null || envelope.end == "section" || envelope.end == "book")
            return if (valid) ReadAloudSentences(envelope.gen, envelope.items, envelope.end) else null
        }

        @Serializable
        private data class ReadAloudLostEnvelope(val type: String = "", val gen: Int = -1)

        private fun parseReadAloudLost(message: String, obj: JsonObject): ReadAloudLost? {
            if (message.length > MAX_BOOK_SHOWN_LENGTH) return null
            val envelope = decode<ReadAloudLostEnvelope>(obj) ?: return null
            return if (envelope.gen >= 0) ReadAloudLost(envelope.gen) else null
        }

        private fun parseAudiobookLost(message: String, obj: JsonObject): AudiobookLost? {
            if (message.length > MAX_BOOK_SHOWN_LENGTH) return null
            val envelope = decode<ReadAloudLostEnvelope>(obj) ?: return null
            return if (envelope.gen >= 0) AudiobookLost(envelope.gen) else null
        }

        @Serializable
        private data class MarksEnvelope(
            val type: String = "",
            val requestId: Long = 0,
            val mode: String = "",
            val knownFurigana: Boolean = false,
            val unknownSentences: Boolean = false,
            val dueMarks: Boolean = false,
            val blocks: List<MarksBlock> = emptyList(),
        )

        private fun parseMarks(message: String, obj: JsonObject): Marks? {
            if (message.length > MAX_MARKS_LENGTH) return null
            val envelope = decode<MarksEnvelope>(obj) ?: return null
            if (envelope.blocks.size > MAX_MARKS_BLOCKS) return null
            val blocks = envelope.blocks.filter { it.text.length <= MAX_MARKS_TEXT }
            return Marks(envelope.requestId, WordMarksMode.fromStorage(envelope.mode), blocks, envelope.knownFurigana, envelope.unknownSentences, envelope.dueMarks)
        }

        /** Largest of the per-kind caps (library messages); anything longer is dropped unread. */
        private const val MAX_MESSAGE_LENGTH = 1_000_000

        /** A picture's data URL is up to 3,000,000 characters (`MAX_PICTURE_DATA` in `tap-bridge.js`). */
        private const val MAX_IMAGE_LENGTH = 3_100_000
        private const val MAX_BOOK_SHOWN_LENGTH = 200
        private const val MAX_POSITION_LENGTH = 200
        private const val MAX_BACKGROUND_LENGTH = 200

        /** Generous bounds: the page sends up to about 6,000 characters, 4,000 per paragraph. */
        private const val MAX_MARKS_LENGTH = 64_000
        private const val MAX_MARKS_BLOCKS = 500
        private const val MAX_MARKS_TEXT = 8_000

        /** The page sends up to 10 sentences, each up to 4,000 characters (`read-aloud.js`). */
        private const val MAX_READ_ALOUD_LENGTH = 200_000
        private const val MAX_READ_ALOUD_ITEMS = 20
        private const val MAX_READ_ALOUD_TEXT = 4_000
        private const val IMAGE_PREFIX = """{"type":"image","""
        private const val DISMISS_MESSAGE = """{"type":"dismiss"}"""
        private const val SETTINGS_SHOWN_MESSAGE = """{"type":"pageShown","path":"/settings"}"""
        private const val OPEN_SETTINGS_MESSAGE = """{"type":"reader.settings"}"""
        private const val OPEN_MENU_MESSAGE = """{"type":"reader.menu"}"""
        private const val OPEN_MENU_SWIPE_MESSAGE = """{"type":"reader.menuSwipe"}"""
        private const val FOLLOW_DETACHED_MESSAGE = """{"type":"follow.detached"}"""
        private const val FOLLOW_ATTACHED_MESSAGE = """{"type":"follow.attached"}"""
        private const val ACTIVITY_MESSAGE = """{"type":"reader.activity"}"""
        private const val LONG_PRESS_MESSAGE = """{"type":"reader.longPress"}"""
    }
}

/** What a long press selected, counted in ttu characters from the start of the book. */
data class SelectedPassage(val start: Long, val end: Long, val text: String)

/** What a highlight made from a long press can cover; [Selection] is what was selected. */
enum class HighlightExtent {
    Selection, Sentence, Paragraph;

    companion object {
        /**
         * The chip chosen when the panel opens: the one the reader chose last ([remembered], a name) if this
         * lookup offers it, else a sentence if the page found one, else the selection. A [remembered]
         * extent that is not offered is not forgotten: it applies again where it is.
         */
        fun initial(available: Set<HighlightExtent>, remembered: String?): HighlightExtent =
            entries.firstOrNull { it.name == remembered && it in available }
                ?: if (Sentence in available) Sentence else Selection
    }
}

/**
 * The places a highlight of a long press can take: the [selection] itself and, when the page could
 * work them out, the [sentence] and the [paragraph] around it (each holds the selection).
 */
data class HighlightExtents(
    val selection: SelectedPassage,
    val sentence: SelectedPassage? = null,
    val paragraph: SelectedPassage? = null,
) {
    /** The passage of [extent], or the selection where the page sent no such extent. */
    operator fun get(extent: HighlightExtent): SelectedPassage = when (extent) {
        HighlightExtent.Selection -> selection
        HighlightExtent.Sentence -> sentence ?: selection
        HighlightExtent.Paragraph -> paragraph ?: selection
    }

    /** The extents to offer, in order, each with its passage. */
    val available: Map<HighlightExtent, SelectedPassage>
        get() = buildMap {
            put(HighlightExtent.Selection, selection)
            sentence?.let { put(HighlightExtent.Sentence, it) }
            paragraph?.let { put(HighlightExtent.Paragraph, it) }
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
    /** The text was chosen by a long press (Android's own selection tools are hidden), not a tap. */
    val bySelection: Boolean = false,
    /** The whole selection (not only [text]) and its sentence and paragraph, in the book's characters, for saving a highlight; null for a tap. */
    val selected: HighlightExtents? = null,
    /** A tap on a character inside this saved highlight (`highlight.id`), else null. */
    val highlightId: Long? = null,
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
                bySelection = envelope.selection,
                selected = if (envelope.selection) extentsOf(envelope.highlight) else null,
                highlightId = envelope.highlightId?.takeIf { it > 0 && !envelope.selection },
            )
            if (!lookup.isValid()) return null
            // Furigana only helps ranking: drop it, not the lookup, if its indexes do not fit.
            val ruby = envelope.ruby.map { RubySpan(it.start, it.end, it.reading) }
            return if (isValidRuby(ruby, lookup.text.length)) lookup.copy(ruby = ruby) else lookup
        }

        private fun passage(wire: PassageEnvelope?): SelectedPassage? =
            wire?.takeIf { it.start >= 0 && it.end > it.start && it.text.isNotBlank() }
                ?.let { SelectedPassage(it.start, it.end, it.text.take(HIGHLIGHT_TEXT_MAX)) }

        /** The selection with the sentence and paragraph that hold it; those that do not are dropped. */
        private fun extentsOf(wire: HighlightEnvelope?): HighlightExtents? {
            val selection = passage(wire?.word) ?: return null
            fun around(candidate: PassageEnvelope?) =
                passage(candidate)?.takeIf { it.start <= selection.start && it.end >= selection.end }
            return HighlightExtents(selection, around(wire?.sentence), around(wire?.paragraph))
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
        val selection: Boolean = false,
        val highlight: HighlightEnvelope? = null,
        val highlightId: Long? = null,
    )

    @Serializable
    private data class HighlightEnvelope(
        val word: PassageEnvelope? = null,
        val sentence: PassageEnvelope? = null,
        val paragraph: PassageEnvelope? = null,
    )

    @Serializable
    private data class PassageEnvelope(val start: Long = 0, val end: Long = 0, val text: String = "")

    @Serializable
    private data class RubyEnvelope(val start: Int, val end: Int, val reading: String)
}
