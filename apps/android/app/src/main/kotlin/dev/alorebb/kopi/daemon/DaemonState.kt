package dev.alorebb.kopi.daemon

import dev.alorebb.kopi.net.Endpoint
import dev.alorebb.kopi.net.PeerStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class DaemonStateData(
    val macFingerprint: String? = null,
    val macName: String? = null,
    val lastHost: String? = null,
    val lastPort: Int? = null,
)

/** Paired-Mac state in `state.json` (600). Only fingerprints, names and addresses; never clipboard content. */
class DaemonState(private val file: File = DaemonPaths.STATE) : PeerStore {
    private val json = Json { ignoreUnknownKeys = true }

    /** A missing or corrupt file reads as empty. */
    @Synchronized
    fun read(): DaemonStateData =
        runCatching { json.decodeFromString(DaemonStateData.serializer(), file.readText()) }.getOrDefault(DaemonStateData())

    @Synchronized
    fun clear() {
        file.delete()
    }

    @Synchronized
    private fun write(update: (DaemonStateData) -> DaemonStateData) {
        DaemonPaths.writeOwnerOnly(file, json.encodeToString(DaemonStateData.serializer(), update(read())).toByteArray())
    }

    override suspend fun pairedFingerprint(): String? = read().macFingerprint

    override suspend fun pairedMacName(): String? = read().macName

    override suspend fun lastEndpoint(): Endpoint? = read().let { s ->
        val host = s.lastHost ?: return null
        val port = s.lastPort ?: return null
        Endpoint(host, port)
    }

    override suspend fun savePairing(fingerprint: String, macName: String, endpoint: Endpoint) =
        write { DaemonStateData(fingerprint, macName, endpoint.host, endpoint.port) }

    override suspend fun saveLastEndpoint(endpoint: Endpoint) =
        write { it.copy(lastHost = endpoint.host, lastPort = endpoint.port) }
}
