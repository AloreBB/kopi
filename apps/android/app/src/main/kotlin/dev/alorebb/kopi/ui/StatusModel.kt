package dev.alorebb.kopi.ui

import androidx.annotation.StringRes
import dev.alorebb.kopi.R
import dev.alorebb.kopi.daemon.DaemonStatus
import dev.alorebb.kopi.shizuku.ShizukuState

/**
 * What the status screen and the daemon's notification show. Pure. [last] is already the preview
 * the daemon computed (masked card, hidden content…), never the copied text; [lastAgo] is the "hace" part.
 */
data class StatusModel(
    @StringRes val title: Int,
    val titleArg: String? = null,
    val last: String? = null,
    val lastAgo: String? = null,
    @StringRes val action: Int? = null,
    @StringRes val detail: Int? = null,
) {
    /** True when nothing needs the user's attention. */
    val healthy: Boolean get() = action == null

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
            return when (status.state) {
                "unpaired" -> StatusModel(R.string.status_unpaired, action = R.string.action_scan_code)
                "not_paired" -> StatusModel(R.string.err_not_paired, action = R.string.action_pair_again)
                "version_mismatch" -> StatusModel(R.string.err_version, action = R.string.action_how_to_update)
                "connected" -> StatusModel(R.string.status_connected, titleArg = status.macName, last = last, lastAgo = ago)
                "not_found" -> StatusModel(R.string.status_not_found, last = last, lastAgo = ago)
                else -> StatusModel(R.string.status_searching, last = last, lastAgo = ago)
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
