package dev.alorebb.kopi.daemon

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

object DaemonPaths {
    val DIR = File("/data/local/tmp/kopi")                  // 700, owned by shell
    val IDENTITY = File(DIR, "identity.p12")                // 600
    val STATE = File(DIR, "state.json")                     // 600
    val LOG = File(DIR, "kopi.log")                         // 600, rotates at 1 MB
    val CLIPS = File("/sdcard/Android/media/dev.alorebb.kopi/clips")
    const val CLIPS_AUTHORITY = "dev.alorebb.kopi.clips"

    /** Creates [dir] (and parents) and restricts it to its owner. */
    fun ensureOwnerOnlyDir(dir: File) {
        dir.mkdirs()
        Files.setPosixFilePermissions(dir.toPath(), PosixFilePermissions.fromString("rwx------"))
    }

    /** Writes [bytes] to [file] atomically with mode 600. */
    fun writeOwnerOnly(file: File, bytes: ByteArray) {
        val parent = file.absoluteFile.parentFile!!
        if (!parent.isDirectory) ensureOwnerOnlyDir(parent)
        val tmp = File(parent, "${file.name}.tmp")
        tmp.delete()
        tmp.createNewFile()
        Files.setPosixFilePermissions(tmp.toPath(), PosixFilePermissions.fromString("rw-------"))
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(file)) {
            file.delete()
            check(tmp.renameTo(file)) { "cannot replace ${file.name}" }
        }
    }
}
