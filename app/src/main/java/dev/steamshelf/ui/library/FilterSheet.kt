package dev.steamshelf.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.steamshelf.model.DeckFilter
import dev.steamshelf.model.LengthFilter
import dev.steamshelf.model.LibraryFilter
import dev.steamshelf.model.PlayStateFilter
import dev.steamshelf.model.PlayerModeFilter
import dev.steamshelf.model.ProtonFilter
import dev.steamshelf.ui.common.toggle

private const val COLLAPSED_TAG_COUNT = 24

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FilterSheet(
    filter: LibraryFilter,
    tags: List<TagOption>,
    resultCount: Int,
    onUpdate: ((LibraryFilter) -> LibraryFilter) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    var showAllTags by rememberSaveable { mutableStateOf(false) }
    var tagQuery by rememberSaveable { mutableStateOf("") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Filters", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onClear, enabled = filter.activeCount > 0) { Text("Reset all") }
            }

            ChipSection("Play status") {
                PlayStateFilter.entries.forEach { option ->
                    Chip(option.label, option in filter.playStates) {
                        onUpdate { it.copy(playStates = it.playStates.toggle(option)) }
                    }
                }
            }

            ChipSection("ProtonDB & Linux", "How well it runs on Linux / Steam Deck via Proton") {
                ProtonFilter.entries.forEach { option ->
                    Chip(option.label, option in filter.proton) {
                        onUpdate { it.copy(proton = it.proton.toggle(option)) }
                    }
                }
            }

            ChipSection("Steam Deck") {
                DeckFilter.entries.forEach { option ->
                    Chip(option.label, option in filter.deck) {
                        onUpdate { it.copy(deck = it.deck.toggle(option)) }
                    }
                }
            }

            ChipSection("Time to beat", "HowLongToBeat main story") {
                LengthFilter.entries.forEach { option ->
                    Chip(option.label, option in filter.length) {
                        onUpdate { it.copy(length = it.length.toggle(option)) }
                    }
                }
            }

            ChipSection("Players") {
                PlayerModeFilter.entries.forEach { option ->
                    Chip(option.label, option in filter.playerModes) {
                        onUpdate { it.copy(playerModes = it.playerModes.toggle(option)) }
                    }
                }
                Chip("Full controller support", filter.fullControllerSupport) {
                    onUpdate { it.copy(fullControllerSupport = !it.fullControllerSupport) }
                }
            }

            ChipSection("Steam reviews") {
                listOf(0 to "Any", 70 to "70%+ positive", 80 to "80%+", 90 to "90%+").forEach { (value, label) ->
                    Chip(label, filter.minReviewPercent == value) {
                        onUpdate { it.copy(minReviewPercent = value) }
                    }
                }
            }

            if (tags.isNotEmpty()) {
                val needle = tagQuery.trim()
                val matching = if (needle.isEmpty()) tags else tags.filter { it.name.contains(needle, ignoreCase = true) }
                val selected = matching.filter { it.id in filter.tags }
                val rest = matching.filterNot { it.id in filter.tags }
                val visible = selected + if (showAllTags || needle.isNotEmpty()) rest else rest.take(COLLAPSED_TAG_COUNT)
                ChipSection("Tags", "Games must have every selected tag") {
                    visible.forEach { tag ->
                        Chip("${tag.name} · ${tag.count}", tag.id in filter.tags) {
                            onUpdate { it.copy(tags = it.tags.toggle(tag.id)) }
                        }
                    }
                }
                if (tags.size > COLLAPSED_TAG_COUNT) {
                    OutlinedTextField(
                        value = tagQuery,
                        onValueChange = { tagQuery = it },
                        label = { Text("Search tags") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (needle.isEmpty()) {
                        TextButton(onClick = { showAllTags = !showAllTags }) {
                            Text(if (showAllTags) "Show fewer tags" else "Show all ${tags.size} tags")
                        }
                    }
                }
            }

            Button(onClick = onDismiss, modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)) {
                Text(if (resultCount == 1) "Show 1 game" else "Show $resultCount games")
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipSection(title: String, subtitle: String? = null, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        if (subtitle != null) {
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = if (selected) {
            { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.height(FilterChipDefaults.IconSize)) }
        } else {
            null
        },
    )
}
