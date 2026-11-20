// Whether the phone has signal, from Android's own default network. The app uses it to send waiting events and check
// for newer data as soon as signal comes back; the screens will use it to say when seller links need a connection.
//
// Online means the default network says it can reach the internet. It does not wait for Android to confirm that it
// really does (its "validated" mark), which can come late or never on some networks; a request that then fails is
// simply tried again later.
package garden.hereabouts.app.services

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class Connectivity(context: Context) {
    private val manager: ConnectivityManager? =
        context.applicationContext.getSystemService(ConnectivityManager::class.java)

    private val _isOnline = MutableStateFlow(currentlyOnline())

    /** True while the phone's default network can reach the internet. Read from the start, so it is right at launch. */
    val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            _isOnline.value = hasInternet(manager?.getNetworkCapabilities(network))
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            _isOnline.value = hasInternet(capabilities)
        }

        override fun onLost(network: Network) {
            _isOnline.value = false
        }
    }

    private val registered: Boolean = try {
        manager?.registerDefaultNetworkCallback(callback)
        manager != null
    } catch (e: RuntimeException) {
        // Android allows each app only so many callbacks. Without one, the value read at the start stays.
        false
    }

    /** Stops listening. Called when the screen that made it is gone for good. */
    fun close() {
        if (!registered) return
        try {
            manager?.unregisterNetworkCallback(callback)
        } catch (e: RuntimeException) {
            // Already gone.
        }
    }

    private fun currentlyOnline(): Boolean {
        val manager = manager ?: return false
        return try {
            hasInternet(manager.activeNetwork?.let { manager.getNetworkCapabilities(it) })
        } catch (e: RuntimeException) {
            false
        }
    }

    private fun hasInternet(capabilities: NetworkCapabilities?): Boolean =
        capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
}
