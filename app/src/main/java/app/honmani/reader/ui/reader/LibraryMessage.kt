package app.honmani.reader.ui.reader

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** A message from `assets/honmani/library-bridge.js`. */
sealed interface LibraryMessage {
    /** The answer to a command. */
    sealed interface Reply : LibraryMessage {
        val requestId: Long
    }

    data class Ready(override val requestId: Long, val id: Long, val title: String) : Reply

    data class Failed(override val requestId: Long, val message: String) : Reply

    data class Removed(override val requestId: Long) : Reply

    data class ProgressReset(override val requestId: Long) : Reply

    /** ttu's saved position for a book: [characters] before it (null if none), saved at [modified] (ms). */
    data class Bookmark(override val requestId: Long, val characters: Long?, val modified: Long) : Reply

    data class BookmarkSet(override val requestId: Long) : Reply

    /** ttu's stored reader settings (`reader-settings.js`), by localStorage key; null for unset. */
    data class Settings(override val requestId: Long, val values: Map<String, String?>) : Reply

    data class Progress(val books: List<TtuProgress>) : LibraryMessage

    /** The end of book [id] was on screen (`book-end.js`). */
    data class Finished(val id: Long) : LibraryMessage

    /** The system's dark mode changed and the reader theme follows it: ttu needs a reload. */
    data object ThemeChanged : LibraryMessage

    companion object {
        private const val MAX_MESSAGE_LENGTH = 1_000_000
        private const val MAX_TITLE_LENGTH = 1_000
        private const val MAX_ERROR_LENGTH = 500
        private const val MAX_SETTING_LENGTH = 200

        private val json = Json { ignoreUnknownKeys = true }

        /**
         * Parses a library message, or returns null for anything else. Like all bridge messages,
         * these come from page script on the reader origin and are checked.
         */
        fun parse(message: String?): LibraryMessage? {
            if (message == null || message.length > MAX_MESSAGE_LENGTH) return null
            val envelope = try {
                json.decodeFromString<LibraryEnvelope>(message)
            } catch (_: SerializationException) {
                return null
            } catch (_: IllegalArgumentException) {
                return null
            }
            return when (envelope.type) {
                "library.ready" -> {
                    val id = envelope.id ?: return null
                    val title = envelope.title ?: return null
                    if (id <= 0 || title.isEmpty() || title.length > MAX_TITLE_LENGTH) return null
                    Ready(envelope.requestId ?: return null, id, title)
                }
                "library.failed" -> Failed(
                    envelope.requestId ?: return null,
                    envelope.message.orEmpty().take(MAX_ERROR_LENGTH),
                )
                "library.removed" -> Removed(envelope.requestId ?: return null)
                "library.reset" -> ProgressReset(envelope.requestId ?: return null)
                "library.bookmark" -> Bookmark(
                    envelope.requestId ?: return null,
                    envelope.characters?.takeIf { it >= 0 },
                    envelope.modified?.coerceAtLeast(0) ?: 0,
                )
                "library.bookmarkSet" -> BookmarkSet(envelope.requestId ?: return null)
                "library.settings" -> Settings(
                    envelope.requestId ?: return null,
                    envelope.settings.filter { (key, value) ->
                        key.length <= MAX_SETTING_LENGTH && (value == null || value.length <= MAX_SETTING_LENGTH)
                    },
                )
                "library.theme" -> ThemeChanged
                "library.finished" -> Finished(envelope.id?.takeIf { it > 0 } ?: return null)
                "library.progress" -> Progress(
                    envelope.books
                        .filter { it.id > 0 && it.progress.isFinite() }
                        .map { TtuProgress(it.id, it.progress.coerceIn(0f, 1f), it.characters?.coerceAtLeast(0)) },
                )
                else -> null
            }
        }
    }
}

@Serializable
private data class LibraryEnvelope(
    val type: String,
    val requestId: Long? = null,
    val id: Long? = null,
    val title: String? = null,
    val message: String? = null,
    val books: List<BookProgress> = emptyList(),
    val settings: Map<String, String?> = emptyMap(),
    val characters: Long? = null,
    val modified: Long? = null,
)

@Serializable
private data class BookProgress(val id: Long, val progress: Float, val characters: Long? = null)
