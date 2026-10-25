package dev.alorebb.kopi

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import dev.alorebb.kopi.shizuku.DaemonLauncher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku

/** Provisional until Task 15: starts the daemon when Shizuku is ready and forwards `kopi://pair` links. */
class MainActivity : Activity() {
    private val launcher by lazy { DaemonLauncher(applicationContext) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val permissionResult = Shizuku.OnRequestPermissionResultListener { _, result ->
        if (result == PackageManager.PERMISSION_GRANTED) launcher.start()
    }
    private val binderReceived = Shizuku.OnBinderReceivedListener { onShizukuReady() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Shizuku.addRequestPermissionResultListener(permissionResult)
        Shizuku.addBinderReceivedListenerSticky(binderReceived)
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(permissionResult)
        Shizuku.removeBinderReceivedListener(binderReceived)
        scope.cancel()
        super.onDestroy()
    }

    private fun onShizukuReady() {
        if (Shizuku.isPreV11()) return
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) launcher.start()
        else Shizuku.requestPermission(SHIZUKU_REQUEST)
    }

    private fun handle(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val raw = intent.dataString ?: return
        val name = Settings.Global.getString(contentResolver, Settings.Global.DEVICE_NAME) ?: Build.MODEL
        scope.launch {
            val daemon = withTimeoutOrNull(DAEMON_WAIT_MS) { launcher.daemon.filterNotNull().first() } ?: run {
                Log.w(TAG, "pair skipped reason=daemon_unavailable")
                return@launch
            }
            val ok = runCatching { daemon.pair(raw, name) }.getOrDefault(false)
            Log.i(TAG, "pair result=$ok")
        }
    }

    private companion object {
        const val TAG = "Kopi"
        const val SHIZUKU_REQUEST = 1
        const val DAEMON_WAIT_MS = 10_000L
    }
}
