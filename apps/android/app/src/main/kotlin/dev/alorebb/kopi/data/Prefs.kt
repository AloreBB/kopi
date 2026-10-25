package dev.alorebb.kopi.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.kopiDataStore: DataStore<Preferences> by preferencesDataStore(name = "kopi")

/** Paired-Mac state. Only fingerprints, names and addresses; never clipboard content. */
class Prefs(context: Context) {
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

    suspend fun clear() {
        store.edit { it.clear() }
    }

    private suspend fun <T> set(key: Preferences.Key<T>, value: T) {
        store.edit { it[key] = value }
    }
}
