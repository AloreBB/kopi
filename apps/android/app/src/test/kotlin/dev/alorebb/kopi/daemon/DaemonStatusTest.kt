package dev.alorebb.kopi.daemon

import dev.alorebb.kopi.core.LastItem
import dev.alorebb.kopi.core.PreviewInput
import dev.alorebb.kopi.net.PeerState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DaemonStatusTest {
    @Test
    fun jsonRoundTrips() {
        val status = DaemonStatus("connected", "MacBook Air", "Hola", 1_761_400_000_000, "ready", paired = true, vpn = true)
        assertEquals(status, DaemonStatus.fromJson(status.toJson()))
        assertEquals(DaemonStatus("searching", access = "denied"), DaemonStatus.fromJson("""{"state":"searching","access":"denied"}"""))
        assertNull(DaemonStatus.fromJson("{"))
    }

    @Test
    fun connectedCarriesMacName() {
        val status = DaemonStatus.of(PeerState.Connected("MacBook Air"), paired = true, macName = "MacBook Air", last = null, access = true)
        assertEquals("connected", status.state)
        assertEquals("MacBook Air", status.macName)
        assertEquals("ready", status.access)
        assertTrue(status.paired)
    }

    @Test
    fun peerStatesMapToNames() {
        assertEquals("not_found", DaemonStatus.of(PeerState.NotFound, true, "MacBook Air", null, true).state)
        assertEquals("not_paired", DaemonStatus.of(PeerState.NotPaired, true, "MacBook Air", null, true).state)
        assertEquals("version_mismatch", DaemonStatus.of(PeerState.VersionMismatch, true, "MacBook Air", null, true).state)
        assertEquals("searching", DaemonStatus.of(PeerState.Searching, true, "MacBook Air", null, true).state)
        assertEquals("mac_busy", DaemonStatus.of(PeerState.MacBusy, false, null, null, true).state)
    }

    @Test
    fun unpairedStillReportsTheSearch() {
        val status = DaemonStatus.of(PeerState.Searching, paired = false, macName = null, last = null, access = false)
        assertEquals("searching", status.state)
        assertFalse(status.paired)
        assertNull(status.macName)
        assertEquals("denied", status.access)
    }

    @Test
    fun carriesVpn() {
        assertTrue(DaemonStatus.of(PeerState.NotFound, true, "MacBook Air", null, true, vpn = true).vpn)
        assertFalse(DaemonStatus.of(PeerState.NotFound, true, "MacBook Air", null, true).vpn)
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
