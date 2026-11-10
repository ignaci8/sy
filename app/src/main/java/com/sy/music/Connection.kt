package com.sy.music

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Cómo está internet. La velocidad medida no sirve porque YouTube frena las descargas aunque
 * la conexión sea buena: lo que cuenta es lo que Android informa y si el reproductor se traba.
 */
object Connection {
    private const val SLOW_WINDOW_MS = 5 * 60_000L
    private const val UNUSABLE_WINDOW_MS = 2 * 60_000L
    private const val VERY_SLOW_LINK_KBPS = 300

    val online = MutableStateFlow(true)

    @Volatile private var linkKbps = 0

    @Volatile private var slowUntil = 0L

    @Volatile private var unusableUntil = 0L

    private val now get() = SystemClock.elapsedRealtime()

    val slow get() = !online.value || now < slowUntil || linkKbps in 1 until VERY_SLOW_LINK_KBPS

    val unusable get() = !online.value || now < unusableUntil

    fun init(context: Context) {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        update(manager.getNetworkCapabilities(manager.activeNetwork))
        manager.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { // otra red: lo aprendido ya no vale
                slowUntil = 0
                unusableUntil = 0
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) =
                update(capabilities)

            override fun onLost(network: Network) = update(null)
        })
    }

    fun reportSlow() {
        slowUntil = now + SLOW_WINDOW_MS
    }

    fun reportUnusable() {
        reportSlow()
        unusableUntil = now + UNUSABLE_WINDOW_MS
    }

    private fun update(capabilities: NetworkCapabilities?) {
        linkKbps = capabilities?.linkDownstreamBandwidthKbps ?: 0
        online.value = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
    }
}
