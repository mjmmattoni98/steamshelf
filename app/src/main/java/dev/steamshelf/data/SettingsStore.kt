package dev.steamshelf.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.steamshelf.model.Account
import dev.steamshelf.model.LibraryPrefs
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

private val Context.dataStore by preferencesDataStore(name = "settings")

data class SyncPrefs(
    val autoProtonDb: Boolean = true,
    val autoHltb: Boolean = true,
    val includeFreeGames: Boolean = true,
    val lastLibrarySync: Long = 0,
)

/** Account, sync options and library view preferences, persisted with DataStore. */
class SettingsStore(context: Context, private val json: Json) {
    private val store = context.applicationContext.dataStore

    private object Keys {
        val STEAM_ID = stringPreferencesKey("steam_id")
        val API_KEY = stringPreferencesKey("api_key")
        val PERSONA = stringPreferencesKey("persona_name")
        val AVATAR = stringPreferencesKey("avatar_url")
        val PROFILE = stringPreferencesKey("profile_url")
        val AUTO_PROTON = booleanPreferencesKey("auto_protondb")
        val AUTO_HLTB = booleanPreferencesKey("auto_hltb")
        val INCLUDE_FREE = booleanPreferencesKey("include_free_games")
        val LAST_SYNC = longPreferencesKey("last_library_sync")
        val LIBRARY_PREFS = stringPreferencesKey("library_prefs")
    }

    val account: Flow<Account?> = store.data.map { p ->
        val id = p[Keys.STEAM_ID]
        val key = p[Keys.API_KEY]
        if (id.isNullOrBlank() || key.isNullOrBlank()) null
        else Account(id, key, p[Keys.PERSONA].orEmpty(), p[Keys.AVATAR], p[Keys.PROFILE])
    }.distinctUntilChanged()

    val syncPrefs: Flow<SyncPrefs> = store.data.map { p ->
        SyncPrefs(
            autoProtonDb = p[Keys.AUTO_PROTON] ?: true,
            autoHltb = p[Keys.AUTO_HLTB] ?: true,
            includeFreeGames = p[Keys.INCLUDE_FREE] ?: true,
            lastLibrarySync = p[Keys.LAST_SYNC] ?: 0,
        )
    }.distinctUntilChanged()

    val libraryPrefs: Flow<LibraryPrefs> = store.data.map { p ->
        p[Keys.LIBRARY_PREFS]?.let { runCatching { json.decodeFromString<LibraryPrefs>(it) }.getOrNull() }
            ?: LibraryPrefs()
    }.distinctUntilChanged()

    suspend fun saveAccount(account: Account) = store.edit { p ->
        p[Keys.STEAM_ID] = account.steamId
        p[Keys.API_KEY] = account.apiKey
        p[Keys.PERSONA] = account.personaName
        p.setOrRemove(Keys.AVATAR, account.avatarUrl)
        p.setOrRemove(Keys.PROFILE, account.profileUrl)
    }

    suspend fun clearAccount() = store.edit { p ->
        listOf(Keys.STEAM_ID, Keys.API_KEY, Keys.PERSONA, Keys.AVATAR, Keys.PROFILE, Keys.LAST_SYNC)
            .forEach { p.remove(it) }
    }

    suspend fun setAutoProtonDb(enabled: Boolean) = store.edit { it[Keys.AUTO_PROTON] = enabled }
    suspend fun setAutoHltb(enabled: Boolean) = store.edit { it[Keys.AUTO_HLTB] = enabled }
    suspend fun setIncludeFreeGames(enabled: Boolean) = store.edit { it[Keys.INCLUDE_FREE] = enabled }
    suspend fun setLastLibrarySync(epochMillis: Long) = store.edit { it[Keys.LAST_SYNC] = epochMillis }

    suspend fun saveLibraryPrefs(prefs: LibraryPrefs) = store.edit {
        it[Keys.LIBRARY_PREFS] = json.encodeToString(LibraryPrefs.serializer(), prefs)
    }

    private fun androidx.datastore.preferences.core.MutablePreferences.setOrRemove(
        key: Preferences.Key<String>,
        value: String?,
    ) {
        if (value == null) remove(key) else this[key] = value
    }
}
