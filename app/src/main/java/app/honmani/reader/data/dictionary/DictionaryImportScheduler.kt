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

    /** Downloading failed (no connection, server error); the detail says why. */
    DOWNLOAD_FAILED,

    /** The file could not be read or the database could not be written. */
    FAILED,
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
