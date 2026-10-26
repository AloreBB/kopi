package dev.alorebb.kopi.ui

import androidx.annotation.StringRes
import dev.alorebb.kopi.R
import dev.alorebb.kopi.daemon.DaemonStatus
import dev.alorebb.kopi.shizuku.ShizukuState

/**
 * What the status screen, the setup wizard and the daemon's notification show. Pure. Each problem
 * maps to its own cause and fix. [last] is already the preview the daemon computed (masked card,
 * hidden content…), never the copied text; [lastAgo] is the "hace" part.
 */
data class StatusModel(
    @StringRes val title: Int,
    val titleArg: String? = null,
    val last: String? = null,
    val lastAgo: String? = null,
    @StringRes val action: Int? = null,
    @StringRes val detail: Int? = null,
    /** Needs the user's attention, even when the fix is not a button here (e.g. it is on the Mac). */
    val problem: Boolean = action != null,
) {
    /** True when nothing needs the user's attention. */
    val healthy: Boolean get() = !problem

    companion object {
        fun render(shizuku: ShizukuState, daemonReachable: Boolean, status: DaemonStatus?, nowMs: Long): StatusModel {
            when (shizuku) {
                ShizukuState.NotInstalled -> return StatusModel(R.string.err_shizuku_missing, action = R.string.action_install_shizuku)
                ShizukuState.NotRunning -> return StatusModel(
                    R.string.err_shizuku, action = R.string.action_reactivate_shizuku, detail = R.string.shizuku_how_to_start,
                )
                ShizukuState.NoPermission -> return StatusModel(R.string.err_shizuku_permission, action = R.string.action_grant_shizuku)
                ShizukuState.Ready -> Unit
            }
            if (!daemonReachable || status == null) return StatusModel(R.string.err_daemon_stopped, action = R.string.action_start_kopi)
            val last = status.last
            val ago = status.lastTs?.let { ago(nowMs - it) }
            val vpnHint = if (status.vpn) R.string.vpn_hint else null
            return when (status.state) {
                "not_paired" -> StatusModel(R.string.err_not_paired, action = R.string.action_search_mac)
                "version_mismatch" -> StatusModel(R.string.err_version, action = R.string.action_how_to_update)
                "mac_busy" -> StatusModel(R.string.err_mac_busy, detail = R.string.mac_busy_how, problem = true)
                "connected" -> StatusModel(R.string.status_connected, titleArg = status.macName, last = last, lastAgo = ago)
                "not_found" ->
                    if (status.paired) StatusModel(R.string.status_not_found, last = last, lastAgo = ago, detail = vpnHint)
                    else StatusModel(R.string.err_mac_not_found_yet, detail = vpnHint ?: R.string.not_found_hint, problem = true)
                else -> StatusModel(R.string.status_searching, last = last, lastAgo = ago, detail = vpnHint)
            }
        }

        /** "5 s", "3 min", "2 h", "1 d". */
        fun ago(deltaMs: Long): String {
            val s = maxOf(0, deltaMs) / 1_000
            return when {
                s < 60 -> "$s s"
                s < 3_600 -> "${s / 60} min"
                s < 86_400 -> "${s / 3_600} h"
                else -> "${s / 86_400} d"
            }
        }
    }
}
