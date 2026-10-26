package dev.alorebb.kopi.ui

import dev.alorebb.kopi.R
import dev.alorebb.kopi.daemon.DaemonStatus
import dev.alorebb.kopi.shizuku.ShizukuState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StatusModelTest {
    private val now = 1_761_400_000_000

    private fun status(state: String, last: String? = null, lastTs: Long? = null, paired: Boolean = true, vpn: Boolean = false) =
        DaemonStatus(state, macName = if (paired) "MacBook Air" else null, last = last, lastTs = lastTs, access = "ready", paired = paired, vpn = vpn)

    private fun ready(status: DaemonStatus?) = StatusModel.render(ShizukuState.Ready, status != null, status, now)

    @Test
    fun connectedShowsMacName() {
        val model = ready(status("connected"))
        assertEquals(R.string.status_connected, model.title)
        assertEquals("MacBook Air", model.titleArg)
        assertNull(model.action)
        assertTrue(model.healthy)
    }

    @Test
    fun shizukuNotRunningShowsReactivateWithSteps() {
        val model = StatusModel.render(ShizukuState.NotRunning, true, status("connected"), now)
        assertEquals(R.string.err_shizuku, model.title)
        assertEquals(R.string.action_reactivate_shizuku, model.action)
        assertEquals(R.string.shizuku_how_to_start, model.detail)
        // Also when the daemon is unreachable: Shizuku is the real cause.
        assertEquals(R.string.err_shizuku, StatusModel.render(ShizukuState.NotRunning, false, null, now).title)
    }

    @Test
    fun shizukuNotInstalledOrNoPermission() {
        assertEquals(R.string.action_install_shizuku, StatusModel.render(ShizukuState.NotInstalled, false, null, now).action)
        assertEquals(R.string.action_grant_shizuku, StatusModel.render(ShizukuState.NoPermission, false, null, now).action)
    }

    @Test
    fun daemonDownShowsStartKopi() {
        val model = StatusModel.render(ShizukuState.Ready, false, status("connected"), now)
        assertEquals(R.string.err_daemon_stopped, model.title)
        assertEquals(R.string.action_start_kopi, model.action)
        assertNull(model.detail)
        assertEquals(R.string.err_daemon_stopped, StatusModel.render(ShizukuState.Ready, true, null, now).title)
    }

    @Test
    fun notPairedOffersSearchingAgain() {
        val model = ready(status("not_paired"))
        assertEquals(R.string.err_not_paired, model.title)
        assertEquals(R.string.action_search_mac, model.action)
    }

    @Test
    fun unpairedSearchingIsNotAnError() {
        val model = ready(status("searching", paired = false))
        assertEquals(R.string.status_searching, model.title)
        assertNull(model.action)
        assertTrue(model.healthy)
    }

    @Test
    fun unpairedNotFoundExplainsWhatToDo() {
        val model = ready(status("not_found", paired = false))
        assertEquals(R.string.err_mac_not_found_yet, model.title)
        assertEquals(R.string.not_found_hint, model.detail)
    }

    @Test
    fun pairedNotFound() {
        val model = ready(status("not_found"))
        assertEquals(R.string.status_not_found, model.title)
        assertNull(model.detail)
        assertNull(model.action)
    }

    @Test
    fun macBusyExplainsHowToFreeIt() {
        val model = ready(status("mac_busy", paired = false))
        assertEquals(R.string.err_mac_busy, model.title)
        assertEquals(R.string.mac_busy_how, model.detail)
        assertNull(model.action)  // the fix is on the Mac
        assertFalse(model.healthy)
    }

    @Test
    fun versionMismatchShowsHowToUpdate() {
        val model = ready(status("version_mismatch"))
        assertEquals(R.string.err_version, model.title)
        assertEquals(R.string.action_how_to_update, model.action)
    }

    @Test
    fun searching() {
        assertEquals(R.string.status_searching, ready(status("searching")).title)
    }

    @Test
    fun macNotFoundWithVpnShowsVpnHint() {
        assertEquals(R.string.vpn_hint, ready(status("not_found", vpn = true)).detail)
        assertEquals(R.string.vpn_hint, ready(status("not_found", paired = false, vpn = true)).detail)
        assertEquals(R.string.vpn_hint, ready(status("searching", vpn = true)).detail)
        assertNull(ready(status("connected", vpn = true)).detail)
    }

    @Test
    fun lastItemUsesPreview() {
        val model = ready(status("connected", "tarjeta •••• 4242", now - 5_000))
        assertEquals("tarjeta •••• 4242", model.last)
        assertEquals("5 s", model.lastAgo)
    }

    @Test
    fun agoIsCoarse() {
        assertEquals("0 s", StatusModel.ago(-300))
        assertEquals("59 s", StatusModel.ago(59_999))
        assertEquals("1 min", StatusModel.ago(60_000))
        assertEquals("59 min", StatusModel.ago(3_599_999))
        assertEquals("1 h", StatusModel.ago(3_600_000))
        assertEquals("2 d", StatusModel.ago(2 * 86_400_000L))
    }
}
