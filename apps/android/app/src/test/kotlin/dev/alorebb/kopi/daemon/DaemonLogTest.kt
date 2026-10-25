package dev.alorebb.kopi.daemon

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class DaemonLogTest {
    @TempDir
    lateinit var dir: File

    @Test
    fun fileLogNeverContainsText() {
        val file = File(dir, "kopi.log")
        val copied = "4242 4242 4242 4242"
        DaemonLog(file).event("copy", "6f1c2a9e-3b4d-4e5f-8a7b-1c2d3e4f5a6b", "text", copied.length)
        val written = file.readText()
        assertTrue(written.contains("copy kind=text size=19 id=6f1c2a9e-3b4d-4e5f-8a7b-1c2d3e4f5a6b"))
        assertFalse(written.contains("4242"))
    }

    @Test
    fun rotatesAtOneMegabyte() {
        val file = File(dir, "kopi.log")
        val log = DaemonLog(file)
        repeat(20_000) { log.event("copy", "6f1c2a9e-3b4d-4e5f-8a7b-1c2d3e4f5a6b", "text", it) }
        assertTrue(file.length() <= 1024 * 1024, "current log is ${file.length()} bytes")
        assertTrue(File(dir, "kopi.log.1").isFile)
    }
}
