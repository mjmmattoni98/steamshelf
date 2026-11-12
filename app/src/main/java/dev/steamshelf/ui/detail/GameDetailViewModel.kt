package dev.steamshelf.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.steamshelf.R
import dev.steamshelf.data.LibraryRepository
import dev.steamshelf.data.hltb.HltbGame
import dev.steamshelf.data.hltb.HltbMatcher
import dev.steamshelf.data.hltb.HltbRateLimitedException
import dev.steamshelf.model.GameItem
import dev.steamshelf.model.PlayStatus
import dev.steamshelf.ui.common.UiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MatchSearchState(
    val query: String,
    val searching: Boolean = false,
    val results: List<HltbGame> = emptyList(),
    val error: UiText? = null,
)

class GameDetailViewModel(
    private val appId: Int,
    private val repository: LibraryRepository,
) : ViewModel() {

    val item: StateFlow<GameItem?> = repository.items
        .map { items -> items.firstOrNull { it.appId == appId } }
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val tagNames: StateFlow<Map<Int, String>> = repository.tagNames

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _matchSearch = MutableStateFlow<MatchSearchState?>(null)
    val matchSearch: StateFlow<MatchSearchState?> = _matchSearch.asStateFlow()

    private var searchJob: Job? = null

    init {
        viewModelScope.launch {
            repository.awaitLoaded()
            repository.ensureDetails(appId)
            _loading.value = false
        }
    }

    fun openMatchDialog() {
        val name = item.value?.name ?: return
        val queries = listOf(HltbMatcher.searchQuery(name)) + HltbMatcher.fallbackQueries(name)
        _matchSearch.value = MatchSearchState(queries.first())
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            for (query in queries) {
                _matchSearch.update { it?.copy(query = query) }
                runSearch(query)
                val current = _matchSearch.value ?: return@launch
                if (current.results.isNotEmpty() || current.error != null) return@launch
            }
        }
    }

    fun closeMatchDialog() {
        searchJob?.cancel()
        _matchSearch.value = null
    }

    fun onQueryChange(query: String) {
        _matchSearch.update { it?.copy(query = query) }
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(450)
            runSearch(query)
        }
    }

    fun search(query: String) {
        searchJob?.cancel()
        searchJob = viewModelScope.launch { runSearch(query) }
    }

    private suspend fun runSearch(query: String) {
        if (query.isBlank()) return
        _matchSearch.update { it?.copy(searching = true, error = null) }
        try {
            val results = repository.searchHltb(query)
            _matchSearch.update { it?.copy(searching = false, results = results) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: HltbRateLimitedException) {
            _matchSearch.update { it?.copy(searching = false, error = UiText.Error(e)) }
        } catch (e: Exception) {
            _matchSearch.update { it?.copy(searching = false, error = UiText.Res(R.string.match_search_failed, UiText.Error(e))) }
        }
    }

    fun pick(match: HltbGame) {
        repository.setHltbMatch(appId, match)
        closeMatchDialog()
    }

    /** The automatic match is right: stop flagging it and never replace it. */
    fun confirmMatch() = repository.confirmHltbMatch(appId)

    fun setStatus(status: PlayStatus?) = repository.setPlayStatus(appId, status)

    fun pickNoMatch() {
        repository.setNoHltbMatch(appId)
        closeMatchDialog()
    }

    fun autoMatch() {
        closeMatchDialog()
        viewModelScope.launch {
            _loading.value = true
            try {
                repository.rematchHltb(appId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The card keeps showing the previous state; the user can retry.
            } finally {
                _loading.value = false
            }
        }
    }
}
