package app.honmani.reader.data.sync

import app.honmani.reader.data.net.originOf
import java.net.URI
import java.net.URISyntaxException

/**
 * Reading progress sync with a book server's own login. Calibre-Web Automated serves its OPDS
 * catalogue and a KOReader sync server (`/kosync`) on one address, with one account, so the
 * catalogue dialog can turn sync on without asking again.
 */
class CatalogSync(private val settings: KoSyncSettings, private val client: KoSyncClient) {
    /** Whether sync runs on the server of [catalogUrl] as [username]. */
    fun isOn(catalogUrl: String, username: String?): Boolean {
        val account = settings.account.value ?: return false
        return username != null && account.username == username && sameServer(account.server, catalogUrl)
    }

    /** The server sync is signed in to, if it is another one (turning sync on here replaces it). */
    fun otherServer(catalogUrl: String, username: String?): String? =
        settings.account.value?.takeIf { !isOn(catalogUrl, username) }?.server

    /** Signs in to sync on the server of [catalogUrl]; throws [KoSyncException] if it has none. */
    suspend fun turnOn(catalogUrl: String, username: String, password: String) {
        settings.signIn(client.signIn(origin(catalogUrl), username, password))
    }

    /** Signs out of sync if it runs on the server of [catalogUrl] as [username]. */
    fun turnOff(catalogUrl: String, username: String?) {
        if (isOn(catalogUrl, username)) settings.signOut()
    }

    companion object {
        /** "http://nas:8083/opds" → "http://nas:8083": where the sync server lives. */
        fun origin(url: String): String {
            val trimmed = url.trim()
            return try {
                val uri = URI(trimmed)
                if (uri.scheme == null || uri.host == null) return trimmed
                uri.scheme.lowercase() + "://" + uri.host.lowercase() + if (uri.port >= 0) ":${uri.port}" else ""
            } catch (_: URISyntaxException) {
                trimmed
            }
        }

        private fun sameServer(syncServer: String, catalogUrl: String): Boolean {
            val sync = KoSyncClient.endpoint(syncServer, "")
            // Default ports spelled out, so "http://nas" and "http://nas:80" are one server.
            return (originOf(sync) ?: origin(sync)) == (originOf(catalogUrl) ?: origin(catalogUrl))
        }
    }
}
