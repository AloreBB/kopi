package dev.alorebb.kopi.daemon

import dev.alorebb.kopi.net.Endpoint
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

class DaemonStateTest {
    @TempDir
    lateinit var dir: File

    private val fp = "a".repeat(64)
    private val lan = Endpoint("192.168.1.61", 47474)

    private fun file() = File(dir, "state.json")

    @Test
    fun missingFileReadsEmpty() = runTest {
        val state = DaemonState(file())
        assertEquals(DaemonStateData(), state.read())
        assertNull(state.pairedFingerprint())
        assertNull(state.lastEndpoint())
    }

    @Test
    fun savePairingPersistsAcrossInstances() = runTest {
        DaemonState(file()).savePairing(fp, "MacBook Air", lan)
        val other = DaemonState(file())
        assertEquals(fp, other.pairedFingerprint())
        assertEquals("MacBook Air", other.pairedMacName())
        assertEquals(lan, other.lastEndpoint())
    }

    @Test
    fun saveLastEndpointKeepsPairing() = runTest {
        val state = DaemonState(file())
        state.savePairing(fp, "MacBook Air", lan)
        state.saveLastEndpoint(Endpoint("10.0.0.5", 47474))
        assertEquals(DaemonStateData(fp, "MacBook Air", "10.0.0.5", 47474), DaemonState(file()).read())
    }

    @Test
    fun clearRemovesFile() = runTest {
        val state = DaemonState(file())
        state.savePairing(fp, "MacBook Air", lan)
        state.clear()
        assertFalse(file().exists())
        assertEquals(DaemonStateData(), state.read())
    }

    @Test
    fun fileIsOwnerOnly() = runTest {
        DaemonState(file()).savePairing(fp, "MacBook Air", lan)
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file().toPath())))
    }

    @Test
    fun corruptFileReadsEmpty() {
        file().writeText("{")
        assertEquals(DaemonStateData(), DaemonState(file()).read())
    }
}
