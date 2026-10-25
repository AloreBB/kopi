package dev.alorebb.kopi.shizuku

import android.content.Context
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import dev.alorebb.kopi.daemon.DaemonMain
import dev.alorebb.kopi.daemon.IKopiDaemon
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import rikka.shizuku.Shizuku
import kotlin.concurrent.thread

/**
 * App side: starts [DaemonMain] as the shell uid through Shizuku, or asks the running daemon to
 * hand its binder over again. The binder arrives in [DaemonBinderProvider] and shows up in [daemon].
 *
 * Deviation from the plan: Shizuku's `bindUserService` cannot start a UserService on this ROM (see
 * [DaemonMain]), so the process is spawned with Shizuku's remote process API instead.
 */
class DaemonLauncher(private val context: Context) {
    val daemon: StateFlow<IKopiDaemon?> = Companion.daemon

    /** Needs a running Shizuku with permission granted; returns right away. */
    fun start() {
        if (!Shizuku.pingBinder() || Shizuku.isPreV11()) return
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "shizuku permission missing")
            return
        }
        val apk = context.applicationInfo.sourceDir
        thread(name = "kopi-launch") {
            try {
                val process = newProcess(arrayOf("sh", "-c", command(apk)))
                process.inputStream.close()
                process.errorStream.close()
                val code = process.waitFor()
                Log.i(TAG, "daemon launch requested exit=$code")
            } catch (e: Throwable) {
                Log.w(TAG, "daemon launch failed reason=${(e.cause ?: e).javaClass.simpleName}")
            }
        }
    }

    companion object {
        private const val TAG = "Kopi"
        const val PROCESS_NAME = "dev.alorebb.kopi:daemon"

        private val daemon = MutableStateFlow<IKopiDaemon?>(null)

        /** The detached shell command: new session, so Shizuku ending the remote process does not end the daemon. */
        fun command(apk: String): String =
            "setsid app_process -Djava.class.path='$apk' /system/bin --nice-name=$PROCESS_NAME " +
                "${DaemonMain::class.java.name} '$apk' </dev/null >/dev/null 2>&1 &"

        internal fun attached(binder: IBinder) {
            val proxy = IKopiDaemon.Stub.asInterface(binder)
            runCatching { binder.linkToDeath({ daemon.compareAndSet(proxy, null) }, 0) }
            daemon.value = proxy
            Log.i(TAG, "daemon connected")
        }

        // Shizuku 13 keeps newProcess private (it nudges towards UserService); it is still the documented
        // way the server spawns shell processes, so call it by reflection.
        private fun newProcess(cmd: Array<String>): Process {
            val m = Shizuku::class.java.getDeclaredMethod("newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java)
            m.isAccessible = true
            return m.invoke(null, cmd, null, null) as Process
        }
    }
}
