package dev.alorebb.kopi.ui

import android.app.Application
import android.content.Context
import android.os.PowerManager
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.alorebb.kopi.daemon.DaemonStatus
import dev.alorebb.kopi.daemon.IKopiDaemon
import dev.alorebb.kopi.shizuku.DaemonLauncher
import dev.alorebb.kopi.shizuku.ShizukuGate
import dev.alorebb.kopi.shizuku.ShizukuState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface Screen {
    data class Setup(val step: Int) : Screen
    data object Status : Screen
}

data class UiState(
    val shizuku: ShizukuState,
    val daemonReachable: Boolean = false,
    val status: DaemonStatus? = null,
    /** The daemon was just (re)launched and may still be attaching: show "Comprobando Kopi…", not an error. */
    val checking: Boolean = true,
    val batteryUnrestricted: Boolean = false,
    val nowMs: Long = System.currentTimeMillis(),
) {
    val model: StatusModel get() = StatusModel.render(shizuku, daemonReachable, status, nowMs)
    /** A Mac is stored in the daemon (it pairs on its own with the first Mac it finds). */
    val paired: Boolean get() = daemonReachable && status?.paired == true
}

/**
 * The app only configures and shows status; the daemon pairs on its own and does the syncing. HiOS freezes this
 * process in the background, so the daemon is queried only while the UI is visible (every second).
 * Never sees clipboard content: the daemon hands over the preview only.
 */
class KopiViewModel(app: Application) : AndroidViewModel(app) {
    val gate = ShizukuGate(app)
    private val launcher = DaemonLauncher(app)
    private val prefs = app.getSharedPreferences("kopi", Context.MODE_PRIVATE)
    private val power = app.getSystemService(PowerManager::class.java)
    private val daemonLock = Mutex()

    private val _ui = MutableStateFlow(UiState(gate.state.value))
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private val _screen = MutableStateFlow<Screen>(if (setupDone) Screen.Status else Screen.Setup(1))
    val screen: StateFlow<Screen> = _screen.asStateFlow()

    private var pollJob: Job? = null
    private var lastLaunchAt = 0L

    private var setupDone: Boolean
        get() = prefs.getBoolean(KEY_SETUP_DONE, false)
        set(value) = prefs.edit().putBoolean(KEY_SETUP_DONE, value).apply()

    /** Set by "Detener Kopi" so the app does not start the daemon again on its own. */
    private var userStopped: Boolean
        get() = prefs.getBoolean(KEY_USER_STOPPED, false)
        set(value) = prefs.edit().putBoolean(KEY_USER_STOPPED, value).apply()

    fun onVisible() {
        if (pollJob?.isActive == true) return
        pollJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                tick()
                delay(POLL_MS)
            }
        }
    }

    fun onHidden() {
        pollJob?.cancel()
        pollJob = null
    }

    fun go(screen: Screen) {
        _screen.value = screen
    }

    /** The wizard's last step saw the Mac connected. */
    fun finishSetup() {
        setupDone = true
        _screen.value = Screen.Status
    }

    fun startDaemon() {
        userStopped = false
        lastLaunchAt = 0L
        refreshNow()
    }

    fun stopDaemon() {
        userStopped = true
        daemonCall { it.stop() }
    }

    /** Forgets the stored Mac; the daemon then finds one and pairs again on its own. */
    fun unpair() {
        userStopped = false
        daemonCall { it.unpair() }
    }

    private fun daemonCall(block: (IKopiDaemon) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            launcher.daemon.value?.let { d -> runCatching { block(d) }.onFailure { Log.w(TAG, "daemon call failed reason=${it.javaClass.simpleName}") } }
            refreshNow()
        }
    }

    private fun refreshNow() {
        viewModelScope.launch(Dispatchers.IO) { tick() }
    }

    private suspend fun tick() = daemonLock.withLock {
        gate.refresh()
        val shizuku = gate.state.value
        val status = launcher.daemon.value
            ?.let { d -> runCatching { d.status() }.getOrNull() }
            ?.let(DaemonStatus::fromJson)
        val now = System.currentTimeMillis()
        val mayLaunch = shizuku == ShizukuState.Ready && !userStopped
        // A new app process has no binder yet even if the daemon runs: launching asks it to hand the binder over.
        if (status == null && mayLaunch && now - lastLaunchAt > RELAUNCH_MS) {
            lastLaunchAt = now
            launcher.start()
        }
        val checking = status == null && mayLaunch && now - lastLaunchAt < CHECK_GRACE_MS
        _ui.value = UiState(
            shizuku = shizuku,
            daemonReachable = status != null,
            status = status,
            checking = checking,
            batteryUnrestricted = runCatching { power.isIgnoringBatteryOptimizations(getApplication<Application>().packageName) }.getOrDefault(false),
            nowMs = now,
        )
    }

    private companion object {
        const val TAG = "Kopi"
        const val KEY_SETUP_DONE = "setup_done"
        const val KEY_USER_STOPPED = "user_stopped"
        const val POLL_MS = 1_000L
        const val RELAUNCH_MS = 10_000L
        const val CHECK_GRACE_MS = 5_000L
    }
}
