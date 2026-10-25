package dev.alorebb.kopi.daemon

import android.annotation.SuppressLint
import android.content.AttributionSource
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Process

/**
 * Hands the daemon's binder to the app through its [dev.alorebb.kopi.shizuku.DaemonBinderProvider],
 * the same way Shizuku delivers its own binder: `getContentProviderExternal` + `IContentProvider.call`
 * as `com.android.shell`. Only called right after the app asked for it, so the app is running.
 */
object AppAttach {
    const val AUTHORITY = "dev.alorebb.kopi.daemon"
    const val METHOD = "attach"
    const val EXTRA_BINDER = "binder"

    @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
    fun deliver(binder: IBinder) {
        val am = Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null)
        val token = Binder()
        val getExternal = am.javaClass.methods.first { it.name == "getContentProviderExternal" && it.parameterTypes.size == 4 }
        val holder = getExternal.invoke(am, AUTHORITY, 0, token, "kopi") ?: error("provider not found")
        try {
            val provider = holder.javaClass.getField("provider").get(holder) ?: error("provider is null")
            val call = provider.javaClass.methods.first {
                it.name == "call" && it.parameterTypes.size == 5 && it.parameterTypes[0] == AttributionSource::class.java
            }
            val source = AttributionSource.Builder(Process.myUid()).setPackageName("com.android.shell").build()
            val extras = Bundle().apply { putBinder(EXTRA_BINDER, binder) }
            call.invoke(provider, source, AUTHORITY, METHOD, null, extras)
        } finally {
            runCatching {
                am.javaClass.methods.first { it.name == "removeContentProviderExternalAsUser" || it.name == "removeContentProviderExternal" }
                    .let { m -> if (m.parameterTypes.size == 3) m.invoke(am, AUTHORITY, token, 0) else m.invoke(am, AUTHORITY, token) }
            }
        }
    }
}
