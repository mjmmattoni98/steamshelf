package dev.steamshelf.server

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.steamshelf.data.steam.SteamLanguage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.security.SecureRandom
import java.util.HexFormat

data class ServerPrefs(
    /** Steam language code for store text and tag names (on the phone this follows the device language). */
    val storeLanguage: String = SteamLanguage.ENGLISH,
    /** Two-letter country the store data is requested for. */
    val storeCountry: String = "US",
    /** Session cookies issued before this time (epoch millis) are refused; moved forward on sign-out. */
    val sessionsValidAfter: Long = 0,
)

/** Settings only the server has, stored next to the shared [dev.steamshelf.data.SettingsStore] in the data directory. */
class ServerSettings private constructor(
    private val store: DataStore<Preferences>,
    val prefs: StateFlow<ServerPrefs>,
) {
    private object Keys {
        val LANGUAGE = stringPreferencesKey("store_language")
        val COUNTRY = stringPreferencesKey("store_country")
        val SESSIONS_VALID_AFTER = longPreferencesKey("sessions_valid_after")
        val SESSION_KEY = stringPreferencesKey("session_signing_key")
    }

    suspend fun setStore(language: String, country: String) = store.edit {
        it[Keys.LANGUAGE] = language
        it[Keys.COUNTRY] = country
    }

    /** Signs every browser out: their session cookies were issued before [epochMillis]. */
    suspend fun invalidateSessions(epochMillis: Long) = store.edit { it[Keys.SESSIONS_VALID_AFTER] = epochMillis }

    /** The key that signs session cookies; made on first start and kept, so restarts don't sign anyone out. */
    suspend fun sessionKey(): ByteArray {
        store.data.first()[Keys.SESSION_KEY]?.let { return HexFormat.of().parseHex(it) }
        val key = ByteArray(32).also(SecureRandom()::nextBytes)
        var stored: String? = null
        store.edit { p ->
            // Another caller may have made one in the meantime.
            stored = p[Keys.SESSION_KEY] ?: HexFormat.of().formatHex(key).also { p[Keys.SESSION_KEY] = it }
        }
        return HexFormat.of().parseHex(checkNotNull(stored))
    }

    companion object {
        /** Opens the settings and waits for the first read, so [prefs] never shows defaults over stored values. */
        suspend fun open(store: DataStore<Preferences>, scope: CoroutineScope): ServerSettings {
            val prefs = store.data.map { p ->
                ServerPrefs(
                    storeLanguage = p[Keys.LANGUAGE] ?: SteamLanguage.ENGLISH,
                    storeCountry = p[Keys.COUNTRY] ?: "US",
                    sessionsValidAfter = p[Keys.SESSIONS_VALID_AFTER] ?: 0,
                )
            }.stateIn(scope)
            return ServerSettings(store, prefs)
        }
    }
}
