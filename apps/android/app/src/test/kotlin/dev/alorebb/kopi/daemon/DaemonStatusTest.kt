package dev.alorebb.kopi.daemon

import dev.alorebb.kopi.core.LastItem
import dev.alorebb.kopi.core.PreviewInput
import dev.alorebb.kopi.net.PeerState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class DaemonStatusTest {
    @Test
    fun jsonRoundTrips() {
        val status = DaemonStatus("connected", "MacBook Air", "Hola", 1_761_400_000_000, "ready")
        assertEquals(status, DaemonStatus.fromJson(status.toJson()))
        assertEquals(DaemonStatus("unpaired", access = "denied"), DaemonStatus.fromJson("""{"state":"unpaired","access":"denied"}"""))
        assertNull(DaemonStatus.fromJson("{"))
    }

    @Test
    fun connectedCarriesMacName() {
        val status = DaemonStatus.of(PeerState.Connected("MacBook Air"), paired = true, macName = "MacBook Air", last = null, access = true)
        assertEquals("connected", status.state)
        assertEquals("MacBook Air", status.macName)
        assertEquals("ready", status.access)
    }

    @Test
    fun unpairedWhenNoMac() {
        val status = DaemonStatus.of(PeerState.NotPaired, paired = false, macName = null, last = null, access = false)
        assertEquals("unpaired", status.state)
        assertEquals("denied", status.access)
        assertEquals("not_found", DaemonStatus.of(PeerState.NotFound, true, "MacBook Air", null, true).state)
        assertEquals("not_paired", DaemonStatus.of(PeerState.NotPaired, true, "MacBook Air", null, true).state)
        assertEquals("version_mismatch", DaemonStatus.of(PeerState.VersionMismatch, true, "MacBook Air", null, true).state)
        assertEquals("searching", DaemonStatus.of(PeerState.Searching, true, "MacBook Air", null, true).state)
    }

    @Test
    fun cardIsMaskedInLast() {
        val ts = 1_761_400_000_000
        val status = DaemonStatus.of(
            PeerState.Connected("MacBook Air"), true, "MacBook Air",
            LastItem(PreviewInput.Text("4242 4242 4242 4242", false), ts), true,
        )
        assertEquals("tarjeta •••• 4242", status.last)
        assertEquals(ts, status.lastTs)
        assertFalse(status.toJson().contains("4242 4242"))
    }
}
