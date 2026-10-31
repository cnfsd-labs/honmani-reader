package app.honmani.reader.data.net

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoginGateTest {
    @Test
    fun httpsLoginsGoAtOnce_httpOnesOnceTheServerAsked() {
        val gate = LoginGate()
        assertTrue(gate.maySend("https://books.example.com/opds"))
        assertFalse(gate.maySend("http://192.168.1.10:8083/opds"))

        gate.asked("http://192.168.1.10:8083/opds")
        assertTrue(gate.maySend("http://192.168.1.10:8083/kosync/users/auth"))
        assertTrue(gate.maySend("HTTP://192.168.1.10:8083/opds/new"))
        // Another port is another server.
        assertFalse(gate.maySend("http://192.168.1.10:8084/opds"))
        assertFalse(gate.maySend("not an address"))
    }

    @Test
    fun dockerStyleHostNamesAreRemembered() {
        val gate = LoginGate()
        assertFalse(gate.maySend("http://calibre_web:8083/opds"))
        gate.asked("http://calibre_web:8083/opds")
        assertTrue(gate.maySend("http://calibre_web:8083/kosync/users/auth"))
        assertFalse(gate.maySend("http://calibre_web:8084/opds"))
    }

    @Test
    fun onAnotherNetworkTheServerHasToAskAgain() {
        var network: Any? = "home Wi-Fi"
        val gate = LoginGate(network = { network })
        gate.asked("http://nas:8083/opds")
        assertTrue(gate.maySend("http://nas:8083/opds"))

        network = "café Wi-Fi"
        assertFalse(gate.maySend("http://nas:8083/opds"))

        network = "home Wi-Fi"
        assertTrue(gate.maySend("http://nas:8083/opds"))
    }

    @Test
    fun onAHotspotNoLoginGoesOverHttp() {
        var hotspot = false
        val gate = LoginGate(hotspot = { hotspot })
        gate.asked("http://nas:8083/opds")
        assertFalse(gate.blocked("http://nas:8083/opds"))

        hotspot = true
        assertTrue(gate.blocked("http://nas:8083/opds"))
        assertFalse(gate.maySend("http://nas:8083/opds"))
        assertFalse(gate.blocked("https://books.example.com/opds"))
        assertTrue(gate.maySend("https://books.example.com/opds"))
    }
}
