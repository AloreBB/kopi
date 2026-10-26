package dev.alorebb.kopi.net

import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/** Whether a VPN is up. A VPN often routes LAN traffic into the tunnel, so NSD and the Mac become unreachable. */
object Vpn {
    @Suppress("DEPRECATION") // allNetworks: also catches per-app VPNs that are not the default network.
    fun active(cm: ConnectivityManager): Boolean = runCatching {
        cm.allNetworks.any { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }
    }.getOrDefault(false)
}
