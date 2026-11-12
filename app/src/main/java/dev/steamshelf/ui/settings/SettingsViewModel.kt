package dev.steamshelf.ui.settings

import android.webkit.CookieManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.steamshelf.R
import dev.steamshelf.data.LibraryRepository
import dev.steamshelf.data.SettingsStore
import dev.steamshelf.data.SyncPrefs
import dev.steamshelf.data.steam.SteamApi
import dev.steamshelf.model.Account
import dev.steamshelf.model.SyncStatus
import dev.steamshelf.ui.common.UiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val settings: SettingsStore,
    private val repository: LibraryRepository,
    private val steam: SteamApi,
) : ViewModel() {

    val account: StateFlow<Account?> = settings.account
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val syncPrefs: StateFlow<SyncPrefs> = settings.syncPrefs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SyncPrefs())
    val sync: StateFlow<SyncStatus> = repository.sync

    private val _keyError = MutableStateFlow<UiText?>(null)
    val keyError: StateFlow<UiText?> = _keyError.asStateFlow()

    private val _message = MutableStateFlow<UiText?>(null)
    val message: StateFlow<UiText?> = _message.asStateFlow()

    fun setAutoProtonDb(enabled: Boolean) = viewModelScope.launch { settings.setAutoProtonDb(enabled) }
    fun setAutoHltb(enabled: Boolean) = viewModelScope.launch { settings.setAutoHltb(enabled) }
    fun setIncludeFreeGames(enabled: Boolean) = viewModelScope.launch { settings.setIncludeFreeGames(enabled) }

    fun refreshEverything() {
        repository.sync(force = true)
        _message.value = UiText.Res(R.string.settings_refreshing)
    }

    fun clearCachedData() = viewModelScope.launch {
        repository.clearEnrichment()
        _message.value = UiText.Res(R.string.settings_cleared)
    }

    fun messageShown() {
        _message.value = null
    }

    /** Validates the new key against the current account before saving it. Returns true on success. */
    fun updateApiKey(key: String, onSaved: () -> Unit) = viewModelScope.launch {
        val trimmed = key.trim()
        val current = settings.account.first() ?: return@launch
        if (!Regex("^[A-Fa-f0-9]{32}$").matches(trimmed)) {
            _keyError.value = UiText.Res(R.string.error_api_key_format)
            return@launch
        }
        try {
            steam.getPlayerSummary(trimmed, current.steamId)
            settings.saveAccount(current.copy(apiKey = trimmed))
            _keyError.value = null
            onSaved()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _keyError.value = UiText.Error(e)
        }
    }

    fun clearKeyError() {
        _keyError.value = null
    }

    fun signOut() = viewModelScope.launch {
        repository.clearAll()
        settings.clearAccount()
        // Forget the Steam web session used by "Sign in through Steam".
        CookieManager.getInstance().removeAllCookies(null)
    }
}
