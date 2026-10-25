package dev.alorebb.kopi.daemon

import dev.alorebb.kopi.core.LastItem
import dev.alorebb.kopi.core.preview
import dev.alorebb.kopi.net.PeerState
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What `IKopiDaemon.status()` returns, as JSON. [last] is already the preview (masked card,
 * hidden content…), never the copied text.
 * state: searching | connected | not_found | not_paired | version_mismatch | unpaired ; access: ready | denied
 */
@Serializable
data class DaemonStatus(
    val state: String,
    val macName: String? = null,
    val last: String? = null,
    val lastTs: Long? = null,
    val access: String,
) {
    fun toJson(): String = json.encodeToString(serializer(), this)

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun fromJson(raw: String): DaemonStatus? = runCatching { json.decodeFromString(serializer(), raw) }.getOrNull()

        fun of(peer: PeerState, paired: Boolean, macName: String?, last: LastItem?, access: Boolean): DaemonStatus {
            val state = if (!paired) "unpaired" else when (peer) {
                PeerState.Searching -> "searching"
                is PeerState.Connected -> "connected"
                PeerState.NotFound -> "not_found"
                PeerState.NotPaired -> "not_paired"
                PeerState.VersionMismatch -> "version_mismatch"
            }
            val name = (peer as? PeerState.Connected)?.macName ?: macName
            return DaemonStatus(
                state = state,
                macName = if (paired) name else null,
                last = last?.let { preview(it.preview) },
                lastTs = last?.ts,
                access = if (access) "ready" else "denied",
            )
        }
    }
}
