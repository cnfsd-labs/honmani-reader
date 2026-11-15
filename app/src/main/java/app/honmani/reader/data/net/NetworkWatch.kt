package app.honmani.reader.data.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The phone's current network, and whether it is a hotspot with a login page (Android's captive
 * portal check: a café's or hotel's Wi-Fi). Android only reports the login page until the reader
 * has signed in to it, so a network seen with one stays a hotspot until the phone leaves it. A
 * hotspot joined while Honmani was not running and already signed in to is not recognised.
 */
class NetworkWatch(context: Context) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val hotspots = ConcurrentHashMap.newKeySet<Network>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    init {
        try {
            connectivity?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    listeners.forEach { it() }
                }

                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                    if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)) hotspots += network
                }

                override fun onLost(network: Network) {
                    hotspots -= network
                }
            })
        } catch (_: RuntimeException) {
            // Without the callback only a login page showing right now is recognised.
        }
    }

    /** Calls [listener] (on a system thread) whenever the phone gets a network. */
    fun onAvailable(listener: () -> Unit) {
        listeners += listener
    }

    fun current(): Network? = try {
        connectivity?.activeNetwork
    } catch (_: RuntimeException) {
        null
    }

    /** Whether the current network costs by the byte (mobile data, a metered Wi-Fi). */
    fun isMetered(): Boolean = try {
        connectivity?.isActiveNetworkMetered ?: false
    } catch (_: RuntimeException) {
        false
    }

    fun isHotspot(): Boolean {
        val network = current() ?: return false
        if (network in hotspots) return true
        val capabilities = try {
            connectivity?.getNetworkCapabilities(network)
        } catch (_: RuntimeException) {
            null
        }
        if (capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL) != true) return false
        hotspots += network
        return true
    }
}
