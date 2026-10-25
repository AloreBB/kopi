package dev.alorebb.kopi.ui

import dev.alorebb.kopi.R
import dev.alorebb.kopi.daemon.DaemonStatus
import dev.alorebb.kopi.shizuku.ShizukuState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class StatusModelTest {
    private val now = 1_761_400_000_000

    private fun status(state: String, last: String? = null, lastTs: Long? = null) =
        DaemonStatus(state, macName = "MacBook Air", last = last, lastTs = lastTs, access = "ready")

    @Test
    fun connectedShowsMacName() {
        val model = StatusModel.render(ShizukuState.Ready, true, status("connected"), now)
        assertEquals(R.string.status_connected, model.title)
        assertEquals("MacBook Air", model.titleArg)
        assertNull(model.action)
    }

    @Test
    fun shizukuMissingShowsReactivate() {
        val model = StatusModel.render(ShizukuState.NotRunning, true, status("connected"), now)
        assertEquals(R.string.err_shizuku, model.title)
        assertEquals(R.string.action_reactivate_shizuku, model.action)
        assertEquals(R.string.shizuku_how_to_start, model.detail)
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
        assertEquals(R.string.err_daemon_stopped, StatusModel.render(ShizukuState.Ready, true, null, now).title)
    }

    @Test
    fun notPairedShowsPairAgain() {
        val model = StatusModel.render(ShizukuState.Ready, true, status("not_paired"), now)
        assertEquals(R.string.err_not_paired, model.title)
        assertEquals(R.string.action_pair_again, model.action)
    }

    @Test
    fun unpairedShowsScanCode() {
        val model = StatusModel.render(ShizukuState.Ready, true, DaemonStatus("unpaired", access = "ready"), now)
        assertEquals(R.string.status_unpaired, model.title)
        assertEquals(R.string.action_scan_code, model.action)
    }

    @Test
    fun versionMismatchShowsHowToUpdate() {
        val model = StatusModel.render(ShizukuState.Ready, true, status("version_mismatch"), now)
        assertEquals(R.string.err_version, model.title)
        assertEquals(R.string.action_how_to_update, model.action)
    }

    @Test
    fun searchingAndNotFound() {
        assertEquals(R.string.status_searching, StatusModel.render(ShizukuState.Ready, true, status("searching"), now).title)
        assertEquals(R.string.status_not_found, StatusModel.render(ShizukuState.Ready, true, status("not_found"), now).title)
    }

    @Test
    fun lastItemUsesPreview() {
        val model = StatusModel.render(ShizukuState.Ready, true, status("connected", "tarjeta •••• 4242", now - 5_000), now)
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
