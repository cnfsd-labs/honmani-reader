package app.honmani.reader.data.net

import java.util.concurrent.ConcurrentHashMap

/**
 * When a login may go to a server over plain http, where anyone on the network can read it.
 *
 * A book server or sync server at home is often `http://192.168.1.10:8083`. On someone else's
 * Wi-Fi that address can be another device, which would receive the password with the first
 * request. So over http the first request to a server goes without the login, and the login only
 * follows once the server has asked for it (401). That is remembered per server and per network
 * ([network]: Android's current network, which is a new one after joining another Wi-Fi), so a
 * server that asked at home asks again elsewhere. A server that deliberately asks for a login
 * still gets it: only https (or a VPN) protects it from the network, which the dialog says.
 *
 * On a hotspot with a login page ([hotspot]: a café's or hotel's Wi-Fi) no login goes over
 * http at all, asked or not ([blocked]).
 *
 * Over https a login always goes with the first request: the certificate proves the server.
 */
class LoginGate(
    private val network: () -> Any? = { null },
    private val hotspot: () -> Boolean = { false },
) {
    private val asked = ConcurrentHashMap.newKeySet<Pair<String, Any?>>()

    /** Whether a login may go with a request to [url] now. */
    fun maySend(url: String): Boolean {
        val origin = originOf(url) ?: return false
        if (!origin.startsWith("http://")) return true
        return !hotspot() && (origin to network()) in asked
    }

    /** Whether a login for [url] is withheld even if the server asks: http on a hotspot. */
    fun blocked(url: String): Boolean = originOf(url)?.startsWith("http://") == true && hotspot()

    /** The server of [url] answered 401: it asked for the login on this network. */
    fun asked(url: String) {
        originOf(url)?.let { asked += it to network() }
    }
}
