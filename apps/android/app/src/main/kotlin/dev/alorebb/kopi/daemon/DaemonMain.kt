package dev.alorebb.kopi.daemon

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Looper
import android.util.Log
import java.io.IOException
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * Entry point of the `dev.alorebb.kopi:daemon` process, started as the shell uid through Shizuku with
 * `app_process -Djava.class.path=<apk> /system/bin --nice-name=dev.alorebb.kopi:daemon DaemonMain <apk>`.
 *
 * Shizuku's own UserService starter cannot be used here: on this MediaTek ROM,
 * `LoadedApk.makeApplicationInner` calls `Application.getProcessName().equals(...)`, which is null
 * outside zygote, so Shizuku's starter dies with a NullPointerException before loading our class.
 *
 * Only one daemon runs: it holds the abstract socket [SOCKET]. A second launch asks the running one
 * to re-deliver its binder to the app and exits; if the running one is from another APK (the app was
 * updated) it exits instead and the new launch takes over.
 */
object DaemonMain {
    const val SOCKET = "dev.alorebb.kopi.daemon"
    private const val TAG = "Kopi"

    @JvmStatic
    fun main(args: Array<String>) {
        val apk = args.firstOrNull() ?: System.getProperty("java.class.path").orEmpty()
        val server = claimOrHandOff(apk) ?: exitProcess(0)
        Looper.prepareMainLooper()
        SystemContext.get()
        val daemon = KopiDaemon()
        deliver(daemon)
        thread(name = "kopi-control", isDaemon = true) { serve(server, apk, daemon) }
        Looper.loop()
    }

    /** Returns the socket if this process becomes the daemon, or null if a running one took the request. */
    private fun claimOrHandOff(apk: String): LocalServerSocket? {
        repeat(50) {
            try {
                return LocalServerSocket(SOCKET)
            } catch (_: IOException) {
                val reply = runCatching { ask("attach $apk") }.getOrNull()
                if (reply == "ok") return null
                Thread.sleep(100)   // "restart" or no answer: wait for the old daemon to go away
            }
        }
        Log.w(TAG, "daemon launch gave up")
        return null
    }

    private fun ask(line: String): String? = LocalSocket().use { socket ->
        socket.connect(LocalSocketAddress(SOCKET))
        socket.soTimeout = 3_000
        socket.outputStream.write("$line\n".toByteArray())
        socket.inputStream.bufferedReader().readLine()
    }

    private fun serve(server: LocalServerSocket, apk: String, daemon: KopiDaemon) {
        while (true) {
            val client = runCatching { server.accept() }.getOrNull() ?: continue
            client.use {
                val line = it.inputStream.bufferedReader().readLine().orEmpty()
                if (!line.startsWith("attach ")) return@use
                if (line.removePrefix("attach ") != apk) {
                    Log.i(TAG, "daemon replaced by a new apk")
                    it.outputStream.write("restart\n".toByteArray())
                    it.close()
                    server.close()
                    exitProcess(0)
                }
                deliver(daemon)
                it.outputStream.write("ok\n".toByteArray())
            }
        }
    }

    private fun deliver(daemon: KopiDaemon) {
        try {
            AppAttach.deliver(daemon)
            Log.i(TAG, "daemon attached to app")
        } catch (e: Throwable) {
            Log.w(TAG, "daemon attach failed reason=${(e.cause ?: e).javaClass.simpleName}")
        }
    }
}
