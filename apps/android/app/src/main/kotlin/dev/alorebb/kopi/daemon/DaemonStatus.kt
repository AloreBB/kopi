package dev.alorebb.kopi.daemon

import dev.alorebb.kopi.core.LastItem
import dev.alorebb.kopi.core.preview
import dev.alorebb.kopi.net.PeerState
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What `IKopiDaemon.status()` returns, as JSON. [last] is already the preview (masked card,
 * hidden content…), never the copied text.
 * state: searching | connected | not_found | not_paired | version_mismatch | mac_busy ; access: ready | denied
 * [paired]: a Mac is stored (otherwise the daemon is looking for one to pair with on its own).
 * [vpn]: a VPN is active on the phone, which can hide the Mac on the local network.
 */
@Serializable
data class DaemonStatus(
    val state: String,
    val macName: String? = null,
    val last: String? = null,
    val lastTs: Long? = null,
    val access: String,
    val paired: Boolean = false,
    val vpn: Boolean = false,
) {
    fun toJson(): String = json.encodeToString(serializer(), this)

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun fromJson(raw: String): DaemonStatus? = runCatching { json.decodeFromString(serializer(), raw) }.getOrNull()

        fun of(peer: PeerState, paired: Boolean, macName: String?, last: LastItem?, access: Boolean, vpn: Boolean = false): DaemonStatus {
            val state = when (peer) {
                PeerState.Searching -> "searching"
                is PeerState.Connected -> "connected"
                PeerState.NotFound -> "not_found"
                PeerState.NotPaired -> "not_paired"
                PeerState.VersionMismatch -> "version_mismatch"
                PeerState.MacBusy -> "mac_busy"
            }
            val name = (peer as? PeerState.Connected)?.macName ?: macName
            return DaemonStatus(
                state = state,
                macName = if (paired || peer is PeerState.Connected) name else null,
                last = last?.let { preview(it.preview) },
                lastTs = last?.ts,
                access = if (access) "ready" else "denied",
                paired = paired,
                vpn = vpn,
            )
        }
    }
}
