package app.honmani.reader.data.opds

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** A catalogue as the UI sees it; the password stays in the repository. */
data class OpdsCatalog(
    val id: Long,
    val title: String,
    val url: String,
    val username: String?,
    val hasPassword: Boolean,
    /** Whether new Japanese books are downloaded automatically when the app starts. */
    val autoImport: Boolean = true,
)

/** The book catalogues the user added, with their logins. */
class OpdsRepository(
    private val dao: OpdsCatalogDao,
    private val cipher: CredentialCipher,
    private val client: OpdsClient,
    private val now: () -> Long = System::currentTimeMillis,
) {
    val catalogs: Flow<List<OpdsCatalog>> = dao.observeAll().map { list ->
        list.map { OpdsCatalog(it.id, it.title, it.url, it.username, it.passwordCipher != null, it.autoImport) }
    }

    /** Catalogues with automatic import on, with their state. */
    suspend fun autoImportCatalogs(): List<OpdsCatalogEntity> = dao.observeAll().first().filter { it.autoImport }

    suspend fun setAutoImport(id: Long, enabled: Boolean) {
        dao.find(id)?.let { dao.update(it.copy(autoImport = enabled)) }
    }

    suspend fun setAutoImportComplete(id: Long, complete: Boolean) {
        dao.find(id)?.let { dao.update(it.copy(autoImportComplete = complete)) }
    }

    /** The address and login of catalogue [id], or null if it is gone. */
    suspend fun server(id: Long): OpdsServer? = dao.find(id)?.let(::server)

    fun server(catalog: OpdsCatalogEntity) = OpdsServer(
        url = catalog.url,
        username = catalog.username,
        password = catalog.passwordCipher?.let(cipher::decrypt),
    )

    /**
     * Checks that [address] is an OPDS catalogue the login opens, and returns its start feed. A
     * missing scheme means https; a server's home page (Calibre-Web) leads to its `/opds` feed.
     */
    suspend fun connect(address: String, username: String?, password: String?): Pair<String, OpdsFeed> {
        val candidates = candidateUrls(address)
        var failure: OpdsException? = null
        for (url in candidates) {
            try {
                return url to client.feed(OpdsServer(url, username, password), url)
            } catch (e: OpdsException) {
                if (e.reason != OpdsException.Reason.NOT_A_CATALOGUE) throw e
                if (failure == null) failure = e
            }
        }
        throw failure ?: OpdsException(OpdsException.Reason.NOT_A_CATALOGUE, "No address")
    }

    /**
     * Adds a catalogue, or updates catalogue [id]. A null [password] on an update keeps the
     * stored one; an empty one removes it. A null [autoImport] keeps the setting (on for new ones).
     */
    suspend fun save(
        id: Long?,
        title: String,
        url: String,
        username: String?,
        password: String?,
        autoImport: Boolean? = null,
    ): Long {
        val user = username?.trim()?.ifEmpty { null }
        val existing = id?.let { dao.find(it) }
        val passwordCipher = when {
            user == null -> null
            password == null -> existing?.passwordCipher
            password.isEmpty() -> null
            else -> try {
                cipher.encrypt(password)
            } catch (e: CredentialException) {
                throw OpdsException(OpdsException.Reason.PASSWORD_STORAGE, e.message!!, e)
            }
        }
        return if (existing == null) {
            dao.insert(
                OpdsCatalogEntity(
                    title = title,
                    url = url,
                    username = user,
                    passwordCipher = passwordCipher,
                    addedAt = now(),
                    autoImport = autoImport ?: true,
                ),
            )
        } else {
            dao.update(
                existing.copy(
                    title = title,
                    url = url,
                    username = user,
                    passwordCipher = passwordCipher,
                    autoImport = autoImport ?: existing.autoImport,
                    // Another server or login: the next import looks through everything again.
                    autoImportComplete = existing.autoImportComplete && existing.url == url && existing.username == user,
                ),
            )
            existing.id
        }
    }

    suspend fun delete(id: Long) = dao.delete(id)

    companion object {
        /** The URLs to try for what the user typed, most likely first. */
        internal fun candidateUrls(address: String): List<String> {
            val trimmed = address.trim().trimEnd('/')
            if (trimmed.isEmpty()) return emptyList()
            val url = if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://").containsMatchIn(trimmed)) trimmed else "https://$trimmed"
            return if (url.endsWith("/opds")) listOf(url) else listOf(url, "$url/opds")
        }
    }
}
