package app.honmani.reader.data.aozora

import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The work list kept in the app's files (`index.json` and its `index.etag`), so the catalogue
 * opens without the network once the user has loaded it. Not in the database and not backed
 * up (only `files/auto-backup/` is).
 */
class AozoraRepository(private val dir: File, private val source: AozoraSource) {
    private val indexFile get() = File(dir, "index.json")
    private val etagFile get() = File(dir, "index.etag")

    /** The list loaded before, or null if there is none (or it cannot be read: it is deleted then). */
    suspend fun cached(): AozoraIndex? = withContext(Dispatchers.IO) {
        if (!indexFile.isFile) return@withContext null
        try {
            indexFile.inputStream().buffered().use(AozoraIndexParser::parse)
        } catch (_: AozoraException) {
            forget()
            null
        } catch (_: IOException) {
            null
        }
    }

    /**
     * Asks the server for the list (conditionally if one is kept) and keeps it. Returns the
     * current list; the kept one stays if anything fails.
     */
    suspend fun refresh(): AozoraIndex = withContext(Dispatchers.IO) {
        dir.mkdirs()
        val temp = File(dir, "index.json.tmp")
        try {
            val kept = cached()
            val etag = if (kept != null) etagFile.takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotEmpty() } else null
            when (val fetched = source.list(temp, etag)) {
                ListFetch.NotModified -> kept ?: throw AozoraException(AozoraException.Reason.SERVER, "Not modified, but nothing kept")
                is ListFetch.Downloaded -> {
                    val index = temp.inputStream().buffered().use(AozoraIndexParser::parse)
                    if (!temp.renameTo(indexFile)) {
                        indexFile.delete()
                        if (!temp.renameTo(indexFile)) throw IOException("Could not keep the work list")
                    }
                    if (fetched.etag != null) etagFile.writeText(fetched.etag) else etagFile.delete()
                    index
                }
            }
        } finally {
            temp.delete()
        }
    }

    private fun forget() {
        indexFile.delete()
        etagFile.delete()
    }
}
