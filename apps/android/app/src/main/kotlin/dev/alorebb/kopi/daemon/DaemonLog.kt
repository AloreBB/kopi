package dev.alorebb.kopi.daemon

import android.util.Log
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Daemon log: logcat tag `Kopi` plus `kopi.log` (600, rotates at 1 MB to `kopi.log.1`).
 * Takes only an event name, `kind`, `size` and `id`: there is no parameter for clipboard content.
 */
class DaemonLog(private val file: File = DaemonPaths.LOG) {
    private val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    fun event(name: String, id: String? = null, kind: String? = null, size: Int? = null) {
        val line = buildString {
            append(name)
            kind?.let { append(" kind=").append(it) }
            size?.let { append(" size=").append(it) }
            id?.let { append(" id=").append(it) }
        }
        // android.util.Log is a stub on the JVM (tests).
        runCatching { Log.i(TAG, line) }
        runCatching { append(line) }
    }

    @Synchronized
    private fun append(line: String) {
        val entry = "${time.format(Date())} $line\n".toByteArray()
        if (file.length() + entry.size > MAX_BYTES) {
            val old = File(file.path + ".1")
            old.delete()
            file.renameTo(old)
        }
        if (!file.exists()) {
            file.absoluteFile.parentFile?.let { if (!it.isDirectory) DaemonPaths.ensureOwnerOnlyDir(it) }
            file.createNewFile()
            Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString("rw-------"))
        }
        file.appendBytes(entry)
    }

    companion object {
        const val TAG = "Kopi"
        const val MAX_BYTES = 1024L * 1024
    }
}
