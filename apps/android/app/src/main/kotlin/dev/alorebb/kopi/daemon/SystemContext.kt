package dev.alorebb.kopi.daemon

import android.annotation.SuppressLint
import android.content.AttributionSource
import android.content.Context
import android.content.ContextWrapper
import android.os.IBinder
import android.os.Process

/**
 * A [Context] for the shell daemon, which has no Application of its own: the framework's system
 * context (from `ActivityThread`, by reflection) presented as `com.android.shell`. The system context
 * says it is package `android`, which uid 2000 does not own, so services that check the caller's
 * package (NSD, connectivity) are built here with this wrapper instead of the system context.
 */
object SystemContext {
    @Volatile private var cached: Context? = null

    @SuppressLint("PrivateApi")
    fun get(): Context {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val at = Class.forName("android.app.ActivityThread")
            val thread = at.getMethod("currentActivityThread").invoke(null)
                ?: at.getMethod("systemMain").invoke(null)
            val system = at.getMethod("getSystemContext").invoke(thread) as Context
            return ShellContext(system).also { cached = it }
        }
    }

    @SuppressLint("PrivateApi")
    private class ShellContext(base: Context) : ContextWrapper(base) {
        private val services = mutableMapOf<String, Any?>()

        override fun getPackageName(): String = ClipboardCall.SHELL_PACKAGE
        override fun getOpPackageName(): String = ClipboardCall.SHELL_PACKAGE
        override fun getAttributionSource(): AttributionSource =
            AttributionSource.Builder(Process.myUid()).setPackageName(ClipboardCall.SHELL_PACKAGE).build()
        override fun getApplicationContext(): Context = this

        override fun getSystemService(name: String): Any? = synchronized(services) {
            when (name) {
                NSD_SERVICE -> services.getOrPut(name) { wrap("android.net.nsd.NsdManager", "android.net.nsd.INsdManager", "servicediscovery") }
                CONNECTIVITY_SERVICE -> services.getOrPut(name) { wrap("android.net.ConnectivityManager", "android.net.IConnectivityManager", "connectivity") }
                else -> super.getSystemService(name)
            }
        }

        /** `new Manager(this, IService.Stub.asInterface(ServiceManager.getService(service)))`. */
        private fun wrap(manager: String, iface: String, service: String): Any {
            val binder = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java)
                .invoke(null, service) as IBinder
            val stub = Class.forName("$iface\$Stub").getMethod("asInterface", IBinder::class.java).invoke(null, binder)
            val ifaceClass = Class.forName(iface)
            return Class.forName(manager).getDeclaredConstructor(Context::class.java, ifaceClass)
                .apply { isAccessible = true }
                .newInstance(this, stub)
        }
    }
}
