package at.bettertrack.app.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Real connectivity for the offline banner (§7.4) and the sync triggers:
 * a default-network callback exposed as a [StateFlow]. "Online" means the
 * default network reports VALIDATED internet — captive portals and dead
 * Wi-Fi count as offline, exactly what an offline-first UI wants.
 */
class ConnectivityMonitor(context: Context) {

    private val connectivityManager =
        context.applicationContext.getSystemService(ConnectivityManager::class.java)

    private val _isOnline = MutableStateFlow(currentlyOnline())
    val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            _isOnline.value = caps.isOnline()
        }

        override fun onLost(network: Network) {
            // The default network died ⇒ offline NOW. Never "re-check" here:
            // ConnectivityManager can still report the dying network as active
            // for a moment, and if no other network takes over there is no
            // further callback to correct a stale true. If another network
            // becomes the default, onCapabilitiesChanged flips us back online.
            _isOnline.value = false
        }

        override fun onUnavailable() {
            _isOnline.value = false
        }
    }

    private var registered = false

    /** Register the callback once; lives for the process lifetime. */
    fun start() {
        if (registered) return
        registered = true
        connectivityManager?.registerDefaultNetworkCallback(callback)
        _isOnline.value = currentlyOnline()
    }

    private fun currentlyOnline(): Boolean {
        val cm = connectivityManager ?: return false
        val caps = cm.activeNetwork?.let(cm::getNetworkCapabilities) ?: return false
        return caps.isOnline()
    }

    private fun NetworkCapabilities.isOnline(): Boolean =
        hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}
