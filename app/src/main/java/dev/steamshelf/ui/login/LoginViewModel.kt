package dev.steamshelf.ui.login

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.steamshelf.R
import dev.steamshelf.data.LibraryRepository
import dev.steamshelf.data.SettingsStore
import dev.steamshelf.data.steam.SteamApi
import dev.steamshelf.data.steam.SteamIdInput
import dev.steamshelf.data.steam.SteamOpenId
import dev.steamshelf.model.Account
import dev.steamshelf.ui.common.UiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

data class LoginUiState(
    val steamIdInput: String = "",
    val apiKey: String = "",
    val signedInViaSteam: Boolean = false,
    val busy: Boolean = false,
    val error: UiText? = null,
)

class LoginViewModel(
    private val http: OkHttpClient,
    private val steam: SteamApi,
    private val settings: SettingsStore,
    private val repository: LibraryRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(LoginUiState())
    val state: StateFlow<LoginUiState> = _state.asStateFlow()

    fun onSteamIdChange(value: String) =
        _state.update { it.copy(steamIdInput = value, signedInViaSteam = false, error = null) }

    fun onApiKeyChange(value: String) = _state.update { it.copy(apiKey = value.trim(), error = null) }

    /** Called with the OpenID redirect the WebView intercepted. */
    fun onSteamSignIn(callbackUrl: String) = run {
        _state.update { it.copy(busy = true, error = null) }
        try {
            val steamId = SteamOpenId.verify(http, callbackUrl)
            _state.update { it.copy(steamIdInput = steamId, signedInViaSteam = true) }
        } finally {
            _state.update { it.copy(busy = false) }
        }
    }

    fun connect() = run {
        val current = _state.value
        val input = SteamIdInput.parse(current.steamIdInput)
            ?: throw InvalidInput(R.string.login_error_no_id)
        val key = current.apiKey
        if (!API_KEY.matches(key)) {
            throw InvalidInput(R.string.error_api_key_format)
        }
        _state.update { it.copy(busy = true, error = null) }
        try {
            val steamId = when (input) {
                is SteamIdInput.Id -> input.steamId
                is SteamIdInput.Vanity -> steam.resolveVanity(key, input.name)
            }
            val profile = steam.getPlayerSummary(key, steamId)
            // Confirms the key can actually read this library before we commit to it.
            steam.getOwnedGames(key, steamId, includeFreeGames = false)
            settings.saveAccount(Account(steamId, key, profile.personaName, profile.avatarUrl, profile.profileUrl))
            repository.sync()
        } finally {
            _state.update { it.copy(busy = false) }
        }
    }

    private fun run(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: InvalidInput) {
                _state.update { it.copy(error = UiText.Res(e.messageRes)) }
            } catch (e: Exception) {
                _state.update { it.copy(error = UiText.Error(e)) }
            }
        }
    }

    private class InvalidInput(@param:StringRes val messageRes: Int) : Exception()

    private companion object {
        val API_KEY = Regex("^[A-Fa-f0-9]{32}$")
    }
}
