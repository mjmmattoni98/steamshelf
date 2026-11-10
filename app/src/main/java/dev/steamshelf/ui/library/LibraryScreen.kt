package dev.steamshelf.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import dev.steamshelf.model.DeckFilter
import dev.steamshelf.model.LengthFilter
import dev.steamshelf.model.LibraryFilter
import dev.steamshelf.model.LibraryPrefs
import dev.steamshelf.model.LibraryStats
import dev.steamshelf.model.PlayStateFilter
import dev.steamshelf.model.ProtonFilter
import dev.steamshelf.model.SortField
import dev.steamshelf.model.SyncPhase
import dev.steamshelf.model.SyncStatus
import dev.steamshelf.model.ViewMode
import dev.steamshelf.ui.common.formatHours
import dev.steamshelf.ui.common.toggle
import java.text.NumberFormat

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    onOpenGame: (Int) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val prefs = viewModel.prefs.collectAsStateWithLifecycle().value ?: LibraryPrefs()
    val content = state.content
    var showFilters by rememberSaveable { mutableStateOf(false) }
    var showSortMenu by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.sync.errorId) {
        val error = state.sync.error
        if (error != null && viewModel.consumeError(state.sync.errorId)) snackbar.showSnackbar(error)
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Library")
                        if (content.totalCount > 0) {
                            Text(
                                if (content.items.size == content.totalCount) "${content.totalCount} games"
                                else "${content.items.size} of ${content.totalCount} games",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                actions = {
                    IconButton(
                        onClick = { viewModel.randomPick()?.let(onOpenGame) },
                        enabled = content.items.isNotEmpty(),
                    ) { Icon(Icons.Default.Casino, contentDescription = "Pick a random game") }
                    Box {
                        IconButton(onClick = { showSortMenu = true }) {
                            Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "Sort")
                        }
                        DropdownMenu(expanded = showSortMenu, onDismissRequest = { showSortMenu = false }) {
                            SortField.entries.forEach { field ->
                                val active = prefs.sort.field == field
                                DropdownMenuItem(
                                    text = { Text(field.label, color = if (active) MaterialTheme.colorScheme.primary else Color.Unspecified) },
                                    trailingIcon = if (active) {
                                        {
                                            Icon(
                                                if (prefs.sort.descending) Icons.Default.ArrowDownward else Icons.Default.ArrowUpward,
                                                contentDescription = if (prefs.sort.descending) "Descending" else "Ascending",
                                                tint = MaterialTheme.colorScheme.primary,
                                            )
                                        }
                                    } else {
                                        null
                                    },
                                    onClick = {
                                        viewModel.setSort(field)
                                        showSortMenu = false
                                    },
                                )
                            }
                        }
                    }
                    IconButton(onClick = viewModel::toggleViewMode) {
                        Icon(
                            if (prefs.viewMode == ViewMode.LIST) Icons.Default.GridView else Icons.AutoMirrored.Filled.ViewList,
                            contentDescription = "Switch layout",
                        )
                    }
                    IconButton(onClick = onOpenSettings) {
                        val avatar = state.account?.avatarUrl
                        if (avatar != null) {
                            AsyncImage(
                                model = avatar,
                                contentDescription = "Settings",
                                modifier = Modifier
                                    .size(30.dp)
                                    .clip(CircleShape),
                            )
                        } else {
                            Icon(Icons.Default.Settings, contentDescription = "Settings")
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            SearchField(query, viewModel::setQuery)
            QuickFilters(
                filter = prefs.filter,
                onOpenAll = { showFilters = true },
                onUpdate = viewModel::updateFilter,
                onClear = viewModel::clearFilters,
            )
            SyncBar(state.sync, onCancel = viewModel::cancelSync)
            if (content.items.isNotEmpty()) StatsLine(content.stats)

            PullToRefreshBox(
                // Only the library download blocks the list; enrichment progress lives in the SyncBar.
                isRefreshing = state.sync.running && state.sync.phase == SyncPhase.LIBRARY,
                onRefresh = viewModel::refresh,
                modifier = Modifier.fillMaxSize(),
            ) {
                when {
                    !content.loaded -> CenteredLoading()
                    content.totalCount == 0 && state.sync.running -> CenteredLoading("Loading your library…")
                    content.totalCount == 0 -> EmptyState(
                        "No games yet",
                        "Pull down to load your Steam library.",
                        "Load library",
                        viewModel::refresh,
                    )
                    content.items.isEmpty() -> EmptyState(
                        "No games match",
                        "Try removing a filter or changing the search.",
                        "Clear filters",
                    ) {
                        viewModel.clearFilters()
                        viewModel.setQuery("")
                    }
                    prefs.viewMode == ViewMode.GRID -> {
                        val gridState = rememberLazyGridState()
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(112.dp),
                            state = gridState,
                            contentPadding = PaddingValues(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            items(content.items, key = { it.appId }) { item ->
                                GameGridCell(item, onClick = { onOpenGame(item.appId) })
                            }
                        }
                    }
                    else -> {
                        val listState = rememberLazyListState()
                        LazyColumn(
                            state = listState,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            items(content.items, key = { it.appId }) { item ->
                                GameRow(item, prefs.sort.field, onClick = { onOpenGame(item.appId) })
                            }
                        }
                    }
                }
            }
        }
    }

    if (showFilters) {
        FilterSheet(
            filter = prefs.filter,
            tags = content.tags,
            resultCount = content.items.size,
            onUpdate = viewModel::updateFilter,
            onClear = viewModel::clearFilters,
            onDismiss = { showFilters = false },
        )
    }
}

@Composable
private fun SearchField(query: String, onChange: (String) -> Unit) {
    TextField(
        value = query,
        onValueChange = onChange,
        placeholder = { Text("Search your library") },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onChange("") }) { Icon(Icons.Default.Clear, contentDescription = "Clear search") }
            }
        },
        singleLine = true,
        shape = RoundedCornerShape(28.dp),
        colors = TextFieldDefaults.colors(
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
    )
}

private val LINUX_READY = setOf(ProtonFilter.NATIVE, ProtonFilter.PLATINUM, ProtonFilter.GOLD)
private val UNDER_15H = setOf(LengthFilter.SHORT, LengthFilter.MEDIUM)

@Composable
private fun QuickFilters(
    filter: LibraryFilter,
    onOpenAll: () -> Unit,
    onUpdate: ((LibraryFilter) -> LibraryFilter) -> Unit,
    onClear: () -> Unit,
) {
    data class Quick(val label: String, val selected: Boolean, val toggle: (LibraryFilter) -> LibraryFilter)

    val quick = listOf(
        Quick("Never played", PlayStateFilter.UNPLAYED in filter.playStates) {
            it.copy(playStates = it.playStates.toggle(PlayStateFilter.UNPLAYED))
        },
        Quick("In progress", PlayStateFilter.IN_PROGRESS in filter.playStates) {
            it.copy(playStates = it.playStates.toggle(PlayStateFilter.IN_PROGRESS))
        },
        Quick("Runs great on Linux", filter.proton.containsAll(LINUX_READY)) {
            it.copy(proton = if (it.proton.containsAll(LINUX_READY)) it.proton - LINUX_READY else it.proton + LINUX_READY)
        },
        Quick("Deck Verified", DeckFilter.VERIFIED in filter.deck) {
            it.copy(deck = it.deck.toggle(DeckFilter.VERIFIED))
        },
        Quick("Under 15 h", filter.length.containsAll(UNDER_15H) && filter.length.size == UNDER_15H.size) {
            it.copy(length = if (it.length == UNDER_15H) emptySet() else UNDER_15H)
        },
        Quick("Played recently", PlayStateFilter.RECENT in filter.playStates) {
            it.copy(playStates = it.playStates.toggle(PlayStateFilter.RECENT))
        },
    )

    LazyRow(
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            FilterChip(
                selected = filter.activeCount > 0,
                onClick = onOpenAll,
                label = { Text(if (filter.activeCount > 0) "Filters · ${filter.activeCount}" else "Filters") },
                leadingIcon = { Icon(Icons.Default.FilterList, contentDescription = null, modifier = Modifier.size(18.dp)) },
            )
        }
        if (filter.activeCount > 0) {
            item {
                FilterChip(
                    selected = false,
                    onClick = onClear,
                    label = { Text("Clear") },
                    leadingIcon = { Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(18.dp)) },
                )
            }
        }
        items(quick, key = { it.label }) { q ->
            FilterChip(selected = q.selected, onClick = { onUpdate(q.toggle) }, label = { Text(q.label) })
        }
    }
}

@Composable
private fun SyncBar(sync: SyncStatus, onCancel: () -> Unit) {
    if (!sync.running) return
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            val label = buildString {
                append(sync.phase?.label ?: "Syncing")
                if (sync.total > 0) append(" · ${sync.done}/${sync.total}")
            }
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (sync.total > 0) {
                LinearProgressIndicator(
                    progress = { sync.done.toFloat() / sync.total },
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                )
            } else {
                LinearProgressIndicator(
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                )
            }
        }
        IconButton(onClick = onCancel) { Icon(Icons.Default.Close, contentDescription = "Stop syncing") }
    }
}

@Composable
private fun StatsLine(stats: LibraryStats) {
    val number = NumberFormat.getIntegerInstance()
    val text = buildString {
        append("${number.format(stats.count)} games · ${formatHours(stats.playedHours)} played")
        if (stats.withHltb > 0) append(" · ≈ ${formatHours(stats.remainingHours)} left to beat")
    }
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
    )
}

@Composable
private fun CenteredLoading(message: String? = null) {
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
        if (message != null) {
            Text(message, modifier = Modifier.padding(top = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun EmptyState(title: String, message: String, action: String, onAction: () -> Unit) {
    // A LazyColumn so pull-to-refresh still works on the empty screen.
    LazyColumn(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        item {
            Column(
                Modifier.padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                Text(
                    message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                if (action.isNotEmpty()) {
                    OutlinedButton(onClick = onAction, modifier = Modifier.padding(top = 8.dp)) { Text(action) }
                }
            }
        }
    }
}
