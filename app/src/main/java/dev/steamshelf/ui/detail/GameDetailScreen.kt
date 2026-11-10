package dev.steamshelf.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.steamshelf.data.hltb.HltbApi
import dev.steamshelf.data.hltb.HltbMatcher
import dev.steamshelf.data.protondb.ProtonDbApi
import dev.steamshelf.model.DeckCompat
import dev.steamshelf.model.FULL_CONTROLLER_CATEGORY
import dev.steamshelf.model.GameItem
import dev.steamshelf.model.PARTIAL_CONTROLLER_CATEGORY
import dev.steamshelf.ui.common.DeckPill
import dev.steamshelf.ui.common.Palette
import dev.steamshelf.ui.common.Pill
import dev.steamshelf.ui.common.ProtonPill
import dev.steamshelf.ui.common.SectionCard
import dev.steamshelf.ui.common.StatTile
import dev.steamshelf.ui.common.SteamImage
import dev.steamshelf.ui.common.formatCount
import dev.steamshelf.ui.common.formatDate
import dev.steamshelf.ui.common.formatHours
import dev.steamshelf.ui.common.formatMinutes
import dev.steamshelf.ui.common.formatSeconds
import dev.steamshelf.ui.common.openUrl
import dev.steamshelf.ui.common.shortRelativeDate
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GameDetailScreen(viewModel: GameDetailViewModel, onBack: () -> Unit) {
    val item by viewModel.item.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val tagNames by viewModel.tagNames.collectAsStateWithLifecycle()
    val matchSearch by viewModel.matchSearch.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(item?.name.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        val game = item
        if (game == null) {
            Box(
                Modifier
                    .padding(padding)
                    .fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }
            return@Scaffold
        }
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Header(game)
            PlaytimeCard(game)
            HltbCard(game, loading, onChangeMatch = viewModel::openMatchDialog, onOpen = { id ->
                openUrl(context, HltbApi.pageUrl(id))
            })
            ProtonCard(game, loading, onOpen = { openUrl(context, ProtonDbApi.pageUrl(game.appId)) })
            StoreCard(game, tagNames)
            Links(
                onStore = { openUrl(context, "https://store.steampowered.com/app/${game.appId}", externalApp = true) },
                onSteamDb = { openUrl(context, "https://steamdb.info/app/${game.appId}/") },
            )
            Spacer(Modifier.height(16.dp))
        }
    }

    matchSearch?.let { search ->
        HltbMatchDialog(
            gameName = item?.name.orEmpty(),
            state = search,
            onQueryChange = viewModel::onQueryChange,
            onSearch = viewModel::search,
            onPick = viewModel::pick,
            onNoMatch = viewModel::pickNoMatch,
            onAutoMatch = viewModel::autoMatch,
            onDismiss = viewModel::closeMatchDialog,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Header(game: GameItem) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SteamImage(
            urls = game.headerUrls,
            contentDescription = game.name,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(460f / 215f)
                .clip(RoundedCornerShape(12.dp)),
        )
        Text(game.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        val byline = listOfNotNull(
            game.store?.developers?.firstOrNull(),
            game.store?.releaseDate?.takeIf { it > 0 }?.let { formatDate(it) },
        ).joinToString(" · ")
        if (byline.isNotEmpty()) {
            Text(byline, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ProtonPill(game.protonTier, linuxNative = false, prefix = "ProtonDB ")
            if (game.linuxNative) ProtonPill(null, linuxNative = true, prefix = "Linux ")
            DeckPill(game.deck)
            val store = game.store
            val reviewLabel = store?.reviewLabel
            if (store != null && reviewLabel != null && (store.reviewCount ?: 0) > 0) {
                Pill(
                    "$reviewLabel · ${store.reviewPercent ?: 0}%",
                    MaterialTheme.colorScheme.surfaceContainerHighest,
                    MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun PlaytimeCard(game: GameItem) {
    SectionCard("Your playtime") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile(formatMinutes(game.game.playtimeForever).replace("Never played", "0 h"), "Total", Modifier.weight(1f))
            StatTile(formatHours(game.game.playtime2Weeks / 60.0), "Last 2 weeks", Modifier.weight(1f))
            StatTile(shortRelativeDate(game.game.lastPlayed), "Last played", Modifier.weight(1f))
        }
        if (game.game.playtimeDeck > 0 || game.game.playtimeLinux > 0) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile(formatHours(game.game.playtimeDeck / 60.0), "On Steam Deck", Modifier.weight(1f))
                StatTile(formatHours(game.game.playtimeLinux / 60.0), "On Linux", Modifier.weight(1f))
            }
        }
        val progress = game.progress
        val beat = game.beatHours
        if (progress != null && beat != null) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                LinearProgressIndicator(
                    progress = { progress },
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                )
                val remaining = game.remainingHours ?: 0.0
                Text(
                    if (remaining <= 0.0) "You've played longer than a typical main-story run (${formatHours(beat)})."
                    else "${(progress * 100).roundToInt()}% of a typical ${formatHours(beat)} run · about ${formatHours(remaining)} to go",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun HltbCard(game: GameItem, loading: Boolean, onChangeMatch: () -> Unit, onOpen: (Int) -> Unit) {
    val info = game.hltb
    SectionCard("HowLongToBeat") {
        when {
            info == null && loading -> LoadingRow("Searching HowLongToBeat…")
            info == null -> Text("Not fetched yet. Pull to refresh the library, or search manually.", style = MaterialTheme.typography.bodyMedium)
            info.hltbId == null -> Text(
                if (info.manual) "Marked as not on HowLongToBeat." else "No match found on HowLongToBeat.",
                style = MaterialTheme.typography.bodyMedium,
            )
            else -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatTile(formatSeconds(info.mainSeconds) ?: "–", "Main story", Modifier.weight(1f), polled(info.mainCount))
                    StatTile(formatSeconds(info.plusSeconds) ?: "–", "Main + extras", Modifier.weight(1f), polled(info.plusCount))
                    StatTile(formatSeconds(info.completionistSeconds) ?: "–", "Completionist", Modifier.weight(1f), polled(info.completionistCount))
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val fuzzy = !info.manual && info.similarity < HltbMatcher.CONFIDENT_SIMILARITY
                    if (fuzzy) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = null,
                            tint = Palette.deck(DeckCompat.PLAYABLE).first,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    Text(
                        buildString {
                            append(if (info.manual) "Matched by you: " else if (fuzzy) "Possible match: " else "Matched: ")
                            append(info.name.orEmpty())
                            if (info.releaseYear > 0) append(" (${info.releaseYear})")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onChangeMatch) {
                Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.size(6.dp))
                Text(if (info?.hltbId != null) "Wrong game?" else "Search HLTB")
            }
            info?.hltbId?.let { id ->
                TextButton(onClick = { onOpen(id) }) {
                    Text("Open on HLTB")
                    Spacer(Modifier.size(4.dp))
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

private fun polled(count: Int): String? = count.takeIf { it > 0 }?.let { "${formatCount(it)} polled" }

@Composable
private fun ProtonCard(game: GameItem, loading: Boolean, onOpen: () -> Unit) {
    val info = game.proton
    val tier = info?.tier
    SectionCard("ProtonDB") {
        when {
            info == null && loading -> LoadingRow("Loading ProtonDB rating…")
            info == null || tier == null -> Text(
                if (info == null) "Not fetched yet." else "No ProtonDB reports for this game yet.",
                style = MaterialTheme.typography.bodyMedium,
            )
            else -> {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    val (bg, fg) = Palette.proton(tier)
                    Pill(tier.label.uppercase(), bg, fg)
                    Text(
                        "${formatCount(info.total)} reports" + (info.confidence?.let { " · $it confidence" } ?: ""),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                val extra = listOfNotNull(
                    info.trendingTier?.let { "Trending: ${it.label}" },
                    info.bestReportedTier?.let { "Best reported: ${it.label}" },
                ).joinToString(" · ")
                if (extra.isNotEmpty()) {
                    Text(extra, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (game.linuxNative) {
            Text(
                "This game has a native Linux version.",
                style = MaterialTheme.typography.bodySmall,
                color = Palette.nativeLinux.first,
            )
        }
        TextButton(onClick = onOpen) {
            Text("Open on ProtonDB")
            Spacer(Modifier.size(4.dp))
            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
        }
    }
}

private val PLAYER_CATEGORY_NAMES = mapOf(
    2 to "Single-player",
    1 to "Multiplayer",
    9 to "Co-op",
    38 to "Online co-op",
    48 to "LAN co-op",
    39 to "Split-screen co-op",
    24 to "Shared/split screen",
    49 to "PvP",
    36 to "Online PvP",
    47 to "LAN PvP",
    37 to "Split-screen PvP",
    20 to "MMO",
    27 to "Cross-platform multiplayer",
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StoreCard(game: GameItem, tagNames: Map<Int, String>) {
    val store = game.store ?: return
    if (!store.found) return
    SectionCard("Steam") {
        val platforms = listOfNotNull(
            "Windows".takeIf { store.windows },
            "macOS".takeIf { store.mac },
            "Linux".takeIf { store.linux },
        ).joinToString(", ")
        val players = store.playerCategoryIds.mapNotNull { PLAYER_CATEGORY_NAMES[it] }.joinToString(", ")
        val controller = when {
            FULL_CONTROLLER_CATEGORY in store.controllerCategoryIds -> "Full support"
            PARTIAL_CONTROLLER_CATEGORY in store.controllerCategoryIds -> "Partial support"
            else -> null
        }
        InfoLine("Steam Deck", game.deck.label)
        if (platforms.isNotEmpty()) InfoLine("Platforms", platforms)
        if (players.isNotEmpty()) InfoLine("Players", players)
        controller?.let { InfoLine("Controller", it) }
        if (store.reviewCount != null && store.reviewCount > 0) {
            InfoLine("Reviews", "${store.reviewLabel ?: ""} (${store.reviewPercent ?: 0}% of ${formatCount(store.reviewCount)})")
        }
        if (store.publishers.isNotEmpty()) InfoLine("Publisher", store.publishers.joinToString(", "))

        val tags = store.tagIds.mapNotNull { tagNames[it] }
        if (tags.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                tags.forEach {
                    Pill(it, MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        store.shortDescription?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.35f),
        )
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(0.65f))
    }
}

@Composable
private fun LoadingRow(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Links(onStore: () -> Unit, onSteamDb: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = onStore, modifier = Modifier.weight(1f)) { Text("Steam store") }
        OutlinedButton(onClick = onSteamDb, modifier = Modifier.weight(1f)) { Text("SteamDB") }
    }
}
