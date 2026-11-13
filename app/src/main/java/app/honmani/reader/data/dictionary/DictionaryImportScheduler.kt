package app.honmani.reader.data.dictionary

import kotlinx.coroutines.flow.Flow

/** Why an import failed, for the UI to explain. */
enum class ImportError {
    /** Not a Yomitan dictionary; the detail says what is wrong. */
    INVALID,

    /** The detail is the title of the installed dictionary. */
    ALREADY_INSTALLED,

    /** Valid, but nothing Honmani can use; the detail is the title. */
    NO_TERMS,

    /** A download from the publisher had no content, nor had its fallback; the detail is the name. */
    INCOMPLETE_RELEASE,

    /** Downloading failed (no connection, server error); the detail says why. */
    DOWNLOAD_FAILED,

    /** The file could not be read or the database could not be written. */
    FAILED,
}

/** What went wrong with a download or import, in a few plain kinds for the UI. */
enum class FailureReason {
    /** No connection, a timeout or a download cut short. */
    OFFLINE,

    /** The server answered with an error or something unexpected. */
    SERVER,

    /** The phone ran out of space. */
    NO_SPACE,

    OTHER,
    ;

    companion object {
        /** [NO_SPACE] if [error] is a full disk or database, else [otherwise]. */
        fun ofStorage(error: Throwable, otherwise: FailureReason = OTHER): FailureReason {
            var current: Throwable? = error
            var depth = 0
            while (current != null && depth++ < 5) {
                val message = current.message.orEmpty()
                if (current is android.database.sqlite.SQLiteFullException ||
                    message.contains("No space left", ignoreCase = true) ||
                    message.contains("ENOSPC") ||
                    message.contains("database or disk is full", ignoreCase = true)
                ) {
                    return NO_SPACE
                }
                current = current.cause
            }
            return otherwise
        }

        /**
         * The plain kind of any download or file failure: a full disk, what a [DownloadException] says,
         * no connection ([java.net.UnknownHostException], timeouts, TLS), else [OTHER]. The exception's
         * own text never reaches the UI.
         */
        fun of(error: Throwable): FailureReason {
            val storage = ofStorage(error)
            if (storage != OTHER) return storage
            var current: Throwable? = error
            var depth = 0
            while (current != null && depth++ < 5) {
                when (current) {
                    is DownloadException -> return current.reason
                    is java.net.UnknownHostException,
                    is java.net.SocketException,
                    is java.net.SocketTimeoutException,
                    is javax.net.ssl.SSLException,
                    -> return OFFLINE
                }
                current = current.cause
            }
            return OTHER
        }

        /** The reason saved with a failed work item; [OTHER] if missing or unknown. */
        fun parse(name: String?): FailureReason = entries.firstOrNull { it.name == name } ?: OTHER
    }
}

sealed interface ImportStatus {
    /** Unique per import, so the UI can tell one failure from the next. */
    val id: String

    data class Queued(override val id: String) : ImportStatus

    data class Running(
        override val id: String,
        val progress: ImportProgress?,
        /** Set while the dictionary file is being downloaded, before the import starts. */
        val download: DownloadProgress? = null,
    ) : ImportStatus

    data class Failed(
        override val id: String,
        val error: ImportError,
        val detail: String?,
        /** The catalog dictionary whose download failed; null for imports of files and updates. */
        val dictionaryId: String? = null,
        val reason: FailureReason = FailureReason.OTHER,
    ) : ImportStatus
}

data class DownloadProgress(
    val name: String,
    val bytesDone: Long,
    /** -1 if unknown. */
    val bytesTotal: Long,
)

/** Runs dictionary imports in the background, one at a time, in the order requested. */
interface DictionaryImportScheduler {
    /** Queues an import of the document at [uri] (a content URI from the system picker). */
    fun enqueue(uri: String)

    /** Queues a download of [dictionary] followed by its import. */
    fun download(dictionary: CatalogDictionary)

    /** Queues a download of [update] that replaces the installed version once imported. */
    fun update(update: DictionaryUpdate)

    /** Imports that are queued, running or failed; successful imports are not included. */
    val imports: Flow<List<ImportStatus>>

    /** Forgets failed imports, once the user has seen them. */
    fun clearFailures()
}
