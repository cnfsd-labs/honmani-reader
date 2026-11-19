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
    /** The first automatic import read only the newest books: offer "Import older books". */
    val offerOlderBooks: Boolean = false,
) {
    /**
     * It has a user name but no password, as a restored backup leaves it: the login has to be typed
     * again. A deliberately empty password is stored (encrypted) and doesn't count.
     */
    val needsPassword: Boolean get() = username != null && !hasPassword
}

/** The book catalogues the user added, with their logins. */
class OpdsRepository(
    private val dao: OpdsCatalogDao,
    private val cipher: CredentialCipher,
    private val client: OpdsClient,
    private val now: () -> Long = System::currentTimeMillis,
    /** Whether the phone is on a hotspot with a login page, which answers http in the server's place. */
    private val onHotspot: () -> Boolean = { false },
) {
    val catalogs: Flow<List<OpdsCatalog>> = dao.observeAll().map { list ->
        list.map { OpdsCatalog(it.id, it.title, it.url, it.username, it.passwordCipher != null, it.autoImport, offersOlderBooks(it)) }
    }

    /** Catalogues with automatic import on, with their state. */
    suspend fun autoImportCatalogs(): List<OpdsCatalogEntity> = dao.observeAll().first().filter { it.autoImport }

    suspend fun setAutoImport(id: Long, enabled: Boolean) {
        dao.find(id)?.let { dao.update(it.copy(autoImport = enabled)) }
    }

    /**
     * Marks [catalog] as imported from once ([olderToo]: its older books as well), unless its
     * address or login changed since the import read it (a new server is looked through from the
     * start).
     */
    suspend fun setAutoImportComplete(catalog: OpdsCatalogEntity, olderToo: Boolean = false) {
        dao.find(catalog.id)
            ?.takeIf { it.url == catalog.url && it.username == catalog.username }
            ?.let { dao.update(it.copy(autoImportComplete = true, olderBooksImported = it.olderBooksImported || olderToo)) }
    }

    /** Catalogue [id] with its state, or null if it is gone. */
    suspend fun catalog(id: Long): OpdsCatalogEntity? = dao.find(id)

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
                if (e.reason == OpdsException.Reason.CERTIFICATE && !hasScheme(address) && speaksPlainHttp(url)) {
                    throw OpdsException(OpdsException.Reason.HTTP_ONLY, "${'$'}url has no https", e)
                }
                if (e.reason != OpdsException.Reason.NOT_A_CATALOGUE) throw e
                if (failure == null) failure = e
            }
        }
        throw failure ?: OpdsException(OpdsException.Reason.NOT_A_CATALOGUE, "No address")
    }

    /**
     * Whether the server of the https [url] answers over plain http. Asked without the login: over
     * http it goes only once the user typed http:// and saw the warning. Only a login request or a
     * page counts as an answer: other errors (a captive portal's 511, a redirect loop) don't, and on
     * a hotspot with a login page the portal answers http for every server, so nothing counts.
     */
    private suspend fun speaksPlainHttp(url: String): Boolean {
        if (onHotspot()) return false
        val http = "http://" + url.substringAfter("://")
        return try {
            client.feed(OpdsServer(http, null, null), http)
            true
        } catch (e: OpdsException) {
            e.reason == OpdsException.Reason.UNAUTHORIZED || e.reason == OpdsException.Reason.NOT_A_CATALOGUE
        }
    }

    /**
     * Adds a catalogue, or updates catalogue [id]. A new one with the address (http or https) and
     * user name of one already listed updates that one instead: adding a restored server again
     * fills in its password rather than listing it twice. A null [password] on an update keeps the
     * stored one; an empty one is stored as the empty password (only a restored server has none). A null [autoImport] keeps the setting (on for new ones).
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
        val existing = if (id != null) dao.find(id) else dao.observeAll().first().firstOrNull { sameServer(it, url, user) }
        val passwordCipher = when {
            user == null -> null
            password == null -> existing?.passwordCipher
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
                    olderBooksImported = existing.olderBooksImported && existing.url == url && existing.username == user,
                ),
            )
            existing.id
        }
    }

    /** The listed catalogue that [save] without an id would update for [url] and [username]; null if none. */
    suspend fun listedId(url: String, username: String?): Long? {
        val user = username?.trim()?.ifEmpty { null }
        return dao.observeAll().first().firstOrNull { sameServer(it, url, user) }?.id
    }

    suspend fun delete(id: Long) = dao.delete(id)

    companion object {
        /** Whether "Import older books" is offered for [catalog]: its first import read only the newest page. */
        fun offersOlderBooks(catalog: OpdsCatalogEntity) =
            catalog.autoImport && catalog.autoImportComplete && !catalog.olderBooksImported

        private fun hasScheme(address: String) = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://").containsMatchIn(address.trim())

        /** Whether [catalog] is the server of [url] with login [user]; http and https count as one. */
        internal fun sameServer(catalog: OpdsCatalogEntity, url: String, user: String?): Boolean {
            fun key(u: String) = u.substringAfter("://").trimEnd('/').let { rest ->
                rest.substringBefore('/').lowercase() + rest.drop(rest.substringBefore('/').length)
            }
            return catalog.username == user && key(catalog.url) == key(url)
        }

        /** The URLs to try for what the user typed, most likely first. */
        internal fun candidateUrls(address: String): List<String> {
            val trimmed = address.trim().trimEnd('/')
            if (trimmed.isEmpty()) return emptyList()
            val url = if (hasScheme(trimmed)) trimmed else "https://$trimmed"
            return if (url.endsWith("/opds")) listOf(url) else listOf(url, "$url/opds")
        }
    }
}
