package dev.alorebb.kopi.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.alorebb.kopi.net.Endpoint
import dev.alorebb.kopi.net.PeerStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.kopiDataStore: DataStore<Preferences> by preferencesDataStore(name = "kopi")

/** Paired-Mac state. Only fingerprints, names and addresses; never clipboard content. */
class Prefs(context: Context) : PeerStore {
    private val store = context.applicationContext.kopiDataStore

    private object Keys {
        val MAC_FINGERPRINT = stringPreferencesKey("macFingerprint")
        val MAC_NAME = stringPreferencesKey("macName")
        val LAST_HOST = stringPreferencesKey("lastHost")
        val LAST_PORT = intPreferencesKey("lastPort")
    }

    val macFingerprint: Flow<String?> = store.data.map { it[Keys.MAC_FINGERPRINT] }
    val macName: Flow<String?> = store.data.map { it[Keys.MAC_NAME] }
    val lastHost: Flow<String?> = store.data.map { it[Keys.LAST_HOST] }
    val lastPort: Flow<Int?> = store.data.map { it[Keys.LAST_PORT] }

    suspend fun setMacFingerprint(value: String) = set(Keys.MAC_FINGERPRINT, value)
    suspend fun setMacName(value: String) = set(Keys.MAC_NAME, value)
    suspend fun setLastHost(value: String) = set(Keys.LAST_HOST, value)
    suspend fun setLastPort(value: Int) = set(Keys.LAST_PORT, value)

    override suspend fun pairedFingerprint(): String? = macFingerprint.first()
    override suspend fun pairedMacName(): String? = macName.first()

    override suspend fun lastEndpoint(): Endpoint? = store.data.first().let { p ->
        val host = p[Keys.LAST_HOST] ?: return null
        val port = p[Keys.LAST_PORT] ?: return null
        Endpoint(host, port)
    }

    override suspend fun savePairing(fingerprint: String, macName: String, endpoint: Endpoint) {
        store.edit {
            it[Keys.MAC_FINGERPRINT] = fingerprint
            it[Keys.MAC_NAME] = macName
            it[Keys.LAST_HOST] = endpoint.host
            it[Keys.LAST_PORT] = endpoint.port
        }
    }

    override suspend fun saveLastEndpoint(endpoint: Endpoint) {
        store.edit {
            it[Keys.LAST_HOST] = endpoint.host
            it[Keys.LAST_PORT] = endpoint.port
        }
    }

    suspend fun clear() {
        store.edit { it.clear() }
    }

    private suspend fun <T> set(key: Preferences.Key<T>, value: T) {
        store.edit { it[key] = value }
    }
}
