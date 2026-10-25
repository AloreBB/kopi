package dev.alorebb.kopi.shizuku

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import rikka.shizuku.Shizuku

sealed interface ShizukuState {
    data object NotInstalled : ShizukuState
    data object NotRunning : ShizukuState
    data object NoPermission : ShizukuState
    data object Ready : ShizukuState

    companion object {
        fun of(installed: Boolean, running: Boolean, granted: Boolean): ShizukuState = when {
            !installed -> NotInstalled
            !running -> NotRunning
            !granted -> NoPermission
            else -> Ready
        }
    }
}

/** App side: whether Shizuku is installed, running and has granted Kopi permission. */
class ShizukuGate(context: Context) {
    private val pm = context.applicationContext.packageManager
    private val _state = MutableStateFlow(compute())
    val state: StateFlow<ShizukuState> = _state.asStateFlow()

    init {
        Shizuku.addBinderReceivedListenerSticky { refresh() }
        Shizuku.addBinderDeadListener { refresh() }
        Shizuku.addRequestPermissionResultListener { _, _ -> refresh() }
    }

    fun refresh() {
        _state.value = compute()
    }

    /**
     * Shows Shizuku's permission dialog. Returns false when Shizuku will not show it (the user chose
     * "deny and don't ask again"), so the caller sends the user to the Shizuku app instead.
     */
    fun requestPermission(): Boolean {
        if (!running()) return false
        return runCatching {
            if (Shizuku.shouldShowRequestPermissionRationale()) return false
            Shizuku.requestPermission(REQUEST_CODE)
            true
        }.getOrDefault(false)
    }

    fun installIntent(): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Opens the Shizuku app, or its download page when it is missing. */
    fun openIntent(): Intent = pm.getLaunchIntentForPackage(PACKAGE)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) ?: installIntent()

    private fun running() = runCatching { Shizuku.pingBinder() && !Shizuku.isPreV11() }.getOrDefault(false)

    private fun compute(): ShizukuState {
        val installed = runCatching { pm.getPackageInfo(PACKAGE, 0) }.isSuccess
        val running = running()
        val granted = running && runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)
        return ShizukuState.of(installed, running, granted)
    }

    companion object {
        const val PACKAGE = "moe.shizuku.privileged.api"
        private const val REQUEST_CODE = 1
    }
}
