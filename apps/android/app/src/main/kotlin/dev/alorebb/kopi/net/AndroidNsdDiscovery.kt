package dev.alorebb.kopi.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import dev.alorebb.kopi.core.Limits
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * [NsdDiscovery] over `NsdManager`: browses `_kopi._tcp` and resolves the first service
 * found. Any Mac found is fine to try: TLS pinning rejects one that is not ours.
 */
class AndroidNsdDiscovery(context: Context) : NsdDiscovery {
    private val nsd = context.applicationContext.getSystemService(NsdManager::class.java)

    override suspend fun discover(): Endpoint? = suspendCancellableCoroutine { cont ->
        val done = AtomicBoolean(false)
        val resolving = AtomicBoolean(false)
        val started = AtomicBoolean(false)
        lateinit var discovery: NsdManager.DiscoveryListener

        fun stopDiscovery() {
            if (started.compareAndSet(true, false)) runCatching { nsd.stopServiceDiscovery(discovery) }
        }

        fun finish(endpoint: Endpoint?) {
            if (!done.compareAndSet(false, true)) return
            stopDiscovery()
            cont.resume(endpoint)
        }

        val resolve = object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                resolving.set(false)
            }

            @Suppress("DEPRECATION")
            override fun onServiceResolved(info: NsdServiceInfo) {
                val host = info.host?.hostAddress
                if (host == null) resolving.set(false) else finish(Endpoint(host, info.port))
            }
        }

        discovery = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onServiceLost(info: NsdServiceInfo) = Unit

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                started.set(false)
                finish(null)
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit

            override fun onServiceFound(info: NsdServiceInfo) {
                // resolveService handles one request at a time on older releases.
                @Suppress("DEPRECATION")
                if (!done.get() && resolving.compareAndSet(false, true)) nsd.resolveService(info, resolve)
            }
        }

        started.set(true)
        nsd.discoverServices(Limits.BONJOUR_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discovery)
        cont.invokeOnCancellation {
            done.set(true)
            stopDiscovery()
        }
    }
}
