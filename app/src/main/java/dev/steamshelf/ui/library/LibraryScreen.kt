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
import androidx.compose.material.icons.filled.Warning
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
import androidx.annotation.StringRes
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import dev.steamshelf.R
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
import dev.steamshelf.ui.common.labelRes
import dev.steamshelf.ui.common.message
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

    val resources = LocalResources.current
    LaunchedEffect(state.sync.errorId) {
        val error = state.sync.error
        if (error != null && viewModel.consumeError(state.sync.errorId)) snackbar.showSnackbar(error.message(resources))
    }
    NotificationPermissionRequest(syncRunning = state.sync.running)

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.library_title))
                        if (content.totalCount > 0) {
                            val number = NumberFormat.getIntegerInstance()
                            Text(
                                if (content.items.size == content.shownTotal) {
                                    pluralStringResource(R.plurals.library_count, content.shownTotal, number.format(content.shownTotal))
                                } else {
                                    pluralStringResource(
                                        R.plurals.library_count_filtered,
                                        content.shownTotal,
                                        number.format(content.items.size),
                                        number.format(content.shownTotal),
                                    )
                                },
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
                    ) { Icon(Icons.Default.Casino, contentDescription = stringResource(R.string.random_pick)) }
                    Box {
                        IconButton(onClick = { showSortMenu = true }) {
                            Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = stringResource(R.string.sort))
                        }
                        DropdownMenu(expanded = showSortMenu, onDismissRequest = { showSortMenu = false }) {
                            SortField.entries.forEach { field ->
                                val active = prefs.sort.field == field
                                DropdownMenuItem(
                                    text = { Text(stringResource(field.labelRes()), color = if (active) MaterialTheme.colorScheme.primary else Color.Unspecified) },
                                    trailingIcon = if (active) {
                                        {
                                            Icon(
                                                if (prefs.sort.descending) Icons.Default.ArrowDownward else Icons.Default.ArrowUpward,
                                                contentDescription = stringResource(if (prefs.sort.descending) R.string.sort_descending else R.string.sort_ascending),
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
                            contentDescription = stringResource(R.string.switch_layout),
                        )
                    }
                    IconButton(onClick = onOpenSettings) {
                        val avatar = state.account?.avatarUrl
                        if (avatar != null) {
                            AsyncImage(
                                model = avatar,
                                contentDescription = stringResource(R.string.settings_title),
                                modifier = Modifier
                                    .size(30.dp)
                                    .clip(CircleShape),
                            )
                        } else {
                            Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.settings_title))
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
                reviewCount = content.reviewCount,
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
                    content.totalCount == 0 && state.sync.running -> CenteredLoading(stringResource(R.string.library_loading))
                    content.totalCount == 0 -> EmptyState(
                        stringResource(R.string.library_empty_title),
                        stringResource(R.string.library_empty_message),
                        stringResource(R.string.library_empty_action),
                        viewModel::refresh,
                    )
                    content.items.isEmpty() -> EmptyState(
                        stringResource(R.string.library_no_match_title),
                        stringResource(R.string.library_no_match_message),
                        stringResource(R.string.library_no_match_action),
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
        placeholder = { Text(stringResource(R.string.search_hint)) },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onChange("") }) { Icon(Icons.Default.Clear, contentDescription = stringResource(R.string.search_clear)) }
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
    reviewCount: Int,
    onOpenAll: () -> Unit,
    onUpdate: ((LibraryFilter) -> LibraryFilter) -> Unit,
    onClear: () -> Unit,
) {
    data class Quick(@StringRes val label: Int, val selected: Boolean, val toggle: (LibraryFilter) -> LibraryFilter)

    val quick = listOf(
        Quick(R.string.play_state_unplayed, PlayStateFilter.UNPLAYED in filter.playStates) {
            it.copy(playStates = it.playStates.toggle(PlayStateFilter.UNPLAYED))
        },
        Quick(R.string.quick_in_progress, PlayStateFilter.IN_PROGRESS in filter.playStates) {
            it.copy(playStates = it.playStates.toggle(PlayStateFilter.IN_PROGRESS))
        },
        Quick(R.string.quick_linux, filter.proton.containsAll(LINUX_READY)) {
            it.copy(proton = if (it.proton.containsAll(LINUX_READY)) it.proton - LINUX_READY else it.proton + LINUX_READY)
        },
        Quick(R.string.quick_deck_verified, DeckFilter.VERIFIED in filter.deck) {
            it.copy(deck = it.deck.toggle(DeckFilter.VERIFIED))
        },
        Quick(R.string.quick_under_15h, filter.length.containsAll(UNDER_15H) && filter.length.size == UNDER_15H.size) {
            it.copy(length = if (it.length == UNDER_15H) emptySet() else UNDER_15H)
        },
        Quick(R.string.quick_recent, PlayStateFilter.RECENT in filter.playStates) {
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
                label = {
                    Text(
                        if (filter.activeCount > 0) stringResource(R.string.filters_count, filter.activeCount)
                        else stringResource(R.string.filters),
                    )
                },
                leadingIcon = { Icon(Icons.Default.FilterList, contentDescription = null, modifier = Modifier.size(18.dp)) },
            )
        }
        if (filter.activeCount > 0) {
            item {
                FilterChip(
                    selected = false,
                    onClick = onClear,
                    label = { Text(stringResource(R.string.clear)) },
                    leadingIcon = { Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(18.dp)) },
                )
            }
        }
        // Only offered when there's something to check (or the filter is on, so it can be turned off).
        if (reviewCount > 0 || filter.hltbNeedsReview) {
            item(key = "hltb-review") {
                FilterChip(
                    selected = filter.hltbNeedsReview,
                    onClick = { onUpdate { it.copy(hltbNeedsReview = !it.hltbNeedsReview) } },
                    label = {
                        Text(
                            if (reviewCount > 0) stringResource(R.string.quick_check_hltb, reviewCount)
                            else stringResource(R.string.quick_check_hltb_none),
                        )
                    },
                    leadingIcon = { Icon(Icons.Default.Warning, contentDescription = null, modifier = Modifier.size(18.dp)) },
                )
            }
        }
        items(quick, key = { it.label }) { q ->
            FilterChip(selected = q.selected, onClick = { onUpdate(q.toggle) }, label = { Text(stringResource(q.label)) })
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
            val phase = stringResource(sync.phase?.labelRes() ?: R.string.sync_starting)
            val label = if (sync.total > 0) stringResource(R.string.sync_bar_progress, phase, sync.done, sync.total) else phase
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
        IconButton(onClick = onCancel) { Icon(Icons.Default.Close, contentDescription = stringResource(R.string.sync_stop_description)) }
    }
}

@Composable
private fun StatsLine(stats: LibraryStats) {
    val number = NumberFormat.getIntegerInstance()
    val res = LocalResources.current
    val played = pluralStringResource(
        R.plurals.stats_line, stats.count, number.format(stats.count), formatHours(res, stats.playedHours),
    )
    val text = if (stats.withHltb > 0) {
        stringResource(R.string.stats_left_to_beat, played, formatHours(res, stats.remainingHours))
    } else {
        played
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
