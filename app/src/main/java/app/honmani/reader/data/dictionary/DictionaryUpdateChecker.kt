package app.honmani.reader.data.dictionary

import java.net.URI
import java.net.URISyntaxException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** A newer version of an installed dictionary, as its publisher's `index.json` describes it. */
data class DictionaryUpdate(
    /** The installed dictionary this replaces. */
    val dictionaryId: Long,
    /** The installed dictionary's title, to show while the update downloads. */
    val name: String,
    /** The new version's revision ("2026.10.17.0"). */
    val revision: String,
    val downloadUrl: String,
)

/**
 * Asks the publishers of installed dictionaries whether there is a new version, the way Yomitan
 * does: dictionaries that declare `isUpdatable` name an `indexUrl` whose `index.json` carries
 * the latest revision and its `downloadUrl` (Jitendex, JMdict, KANJIDIC and Jiten do). Only the
 * two URLs from the dictionary itself are requested, over HTTPS, with nothing about the reader.
 * A new `downloadUrl` counts only on the host of the one stored with the dictionary.
 *
 * Updates are offered, not installed: Jitendex is a 40 MB download and a minute of import.
 * JMdict and KANJIDIC are rebuilt almost daily, so the automatic check leaves out dictionaries
 * installed less than [MIN_AGE_MS] ago; asking explicitly checks them all.
 */
class DictionaryUpdateChecker(
    private val dao: DictionaryDao,
    private val fetchText: suspend (url: String) -> String,
    private val scope: CoroutineScope,
    private val log: (String) -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val found = MutableStateFlow<List<DictionaryUpdate>>(emptyList())
    private val _checking = MutableStateFlow(false)

    /** Whether a check is running. */
    val checking: StateFlow<Boolean> = _checking.asStateFlow()

    private val _checked = MutableStateFlow(false)

    /** Whether a check has finished since the app started. */
    val checked: StateFlow<Boolean> = _checked.asStateFlow()

    /** Updates for dictionaries still installed in the version that was checked. */
    val updates: Flow<List<DictionaryUpdate>> = combine(found, dao.observeReady()) { updates, installed ->
        updates.filter { update ->
            installed.any { it.id == update.dictionaryId && it.revision != update.revision }
        }
    }

    /**
     * Checks in the background; does nothing while a check runs. [all]: also dictionaries
     * installed recently (when the reader asks, rather than at app start).
     */
    fun start(all: Boolean = false) {
        if (_checking.value) return
        _checking.value = true
        scope.launch {
            try {
                found.value = check(all)
                _checked.value = true
            } finally {
                _checking.value = false
            }
        }
    }

    /**
     * Checks every updatable dictionary (with [all], also those installed recently); one that
     * can't be checked is skipped.
     */
    suspend fun check(all: Boolean = true): List<DictionaryUpdate> =
        dao.allReady().filter { it.isUpdatable && (all || clock() - it.importedAt >= MIN_AGE_MS) }.mapNotNull { dictionary ->
            val indexUrl = dictionary.indexUrl?.takeIf(::isHttps) ?: return@mapNotNull null
            try {
                updateFor(dictionary, fetchText(indexUrl))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("Update check for ${dictionary.title} failed: $e")
                null
            }
        }

    companion object {
        /** How old an installed dictionary must be for the automatic check to look at it. */
        const val MIN_AGE_MS = 7L * 24 * 60 * 60 * 1000

        /** The update [index] (the publisher's current `index.json`) offers for [installed], if any. */
        fun updateFor(installed: DictionaryEntity, index: String): DictionaryUpdate? {
            val json = Json.parseToJsonElement(index) as? JsonObject ?: return null
            fun string(key: String) = (json[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val revision = string("revision")?.takeIf { it.isNotBlank() } ?: return null
            if (revision == installed.revision) return null
            // The download address may change, but only within the host the dictionary was
            // installed from: a hijacked or hostile index.json must not send the app elsewhere.
            val stored = installed.downloadUrl?.takeIf(::isHttps)
            val offered = string("downloadUrl")?.takeIf { isHttps(it) && stored != null && sameHost(it, stored) }
            val downloadUrl = offered ?: stored ?: return null
            return DictionaryUpdate(installed.id, installed.title, revision, downloadUrl)
        }

        private fun isHttps(url: String) = url.startsWith("https://")

        private fun sameHost(a: String, b: String): Boolean {
            val hostA = hostOf(a) ?: return false
            return hostA.equals(hostOf(b), ignoreCase = true)
        }

        private fun hostOf(url: String): String? = try {
            URI(url).host
        } catch (_: URISyntaxException) {
            null
        }
    }
}
