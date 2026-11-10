package dev.steamshelf.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.steamshelf.data.LibraryRepository
import dev.steamshelf.data.SettingsStore
import dev.steamshelf.model.Account
import dev.steamshelf.model.GameItem
import dev.steamshelf.model.LibraryFilter
import dev.steamshelf.model.LibraryPrefs
import dev.steamshelf.model.LibraryQuery
import dev.steamshelf.model.LibraryStats
import dev.steamshelf.model.SortField
import dev.steamshelf.model.SortOption
import dev.steamshelf.model.SyncStatus
import dev.steamshelf.model.ViewMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TagOption(val id: Int, val name: String, val count: Int)

data class LibraryContent(
    val loaded: Boolean = false,
    val totalCount: Int = 0,
    val items: List<GameItem> = emptyList(),
    val stats: LibraryStats = LibraryStats(),
    val tags: List<TagOption> = emptyList(),
)

data class LibraryUiState(
    val content: LibraryContent = LibraryContent(),
    val sync: SyncStatus = SyncStatus(),
    val account: Account? = null,
)

@OptIn(FlowPreview::class)
class LibraryViewModel(
    private val repository: LibraryRepository,
    private val settings: SettingsStore,
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _prefs = MutableStateFlow<LibraryPrefs?>(null)
    val prefs: StateFlow<LibraryPrefs?> = _prefs.asStateFlow()

    private var shownErrorId = 0L

    private val content = combine(
        repository.items,
        _prefs.filterNotNull(),
        _query,
        repository.tagNames,
        repository.loaded,
    ) { items, prefs, query, tagNames, loaded ->
        val visible = LibraryQuery.apply(items, prefs.filter, query, prefs.sort)
        LibraryContent(
            loaded = loaded,
            totalCount = items.size,
            items = visible,
            stats = LibraryStats.of(visible),
            tags = tagOptions(items, prefs.filter.tags, tagNames),
        )
    }.conflate().flowOn(Dispatchers.Default)

    val state: StateFlow<LibraryUiState> = combine(content, repository.sync, settings.account) { c, sync, account ->
        LibraryUiState(c, sync, account)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState())

    init {
        viewModelScope.launch {
            _prefs.value = settings.libraryPrefs.first()
            _prefs.filterNotNull().drop(1).debounce(400).collect { settings.saveLibraryPrefs(it) }
        }
        viewModelScope.launch {
            if (repository.shouldAutoSync()) repository.sync()
        }
    }

    fun setQuery(value: String) {
        _query.value = value
    }

    fun updateFilter(transform: (LibraryFilter) -> LibraryFilter) =
        _prefs.update { it?.copy(filter = transform(it.filter)) }

    fun clearFilters() = _prefs.update { it?.copy(filter = LibraryFilter()) }

    /** Picking the active field flips direction; a new field starts in its natural direction. */
    fun setSort(field: SortField) = _prefs.update { prefs ->
        prefs ?: return@update null
        val sort = if (prefs.sort.field == field) prefs.sort.copy(descending = !prefs.sort.descending)
        else SortOption(field, field.defaultDescending)
        prefs.copy(sort = sort)
    }

    fun toggleViewMode() = _prefs.update {
        it?.copy(viewMode = if (it.viewMode == ViewMode.LIST) ViewMode.GRID else ViewMode.LIST)
    }

    fun refresh() = repository.sync()

    fun cancelSync() = repository.cancelSync()

    fun randomPick(): Int? = state.value.content.items.randomOrNull()?.appId

    /** Returns true the first time a given sync error should be shown. */
    fun consumeError(id: Long): Boolean {
        if (id <= shownErrorId) return false
        shownErrorId = id
        return true
    }

    private fun tagOptions(items: List<GameItem>, selected: Set<Int>, names: Map<Int, String>): List<TagOption> {
        val counts = HashMap<Int, Int>()
        items.forEach { item -> item.store?.tagIds?.forEach { counts[it] = (counts[it] ?: 0) + 1 } }
        return counts.entries
            .filter { (id, count) -> names.containsKey(id) && (count >= 2 || id in selected) }
            .sortedByDescending { it.value }
            .map { (id, count) -> TagOption(id, names.getValue(id), count) }
    }
}
