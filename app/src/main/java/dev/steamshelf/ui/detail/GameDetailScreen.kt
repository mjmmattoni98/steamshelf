package dev.steamshelf.ui.detail

import android.content.res.Resources
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.steamshelf.data.hltb.HltbApi
import dev.steamshelf.R
import dev.steamshelf.data.protondb.ProtonDbApi
import dev.steamshelf.model.DeckCompat
import dev.steamshelf.model.FULL_CONTROLLER_CATEGORY
import dev.steamshelf.model.GameItem
import dev.steamshelf.model.PARTIAL_CONTROLLER_CATEGORY
import dev.steamshelf.model.PlayStatus
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
import dev.steamshelf.ui.common.formatSeconds
import dev.steamshelf.ui.common.labelRes
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
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back)) }
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
            PlaytimeCard(game, onSetStatus = viewModel::setStatus)
            HltbCard(
                game,
                loading,
                onChangeMatch = viewModel::openMatchDialog,
                onConfirmMatch = viewModel::confirmMatch,
                onOpen = { id -> openUrl(context, HltbApi.pageUrl(id)) },
            )
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
            ProtonPill(game.protonTier, linuxNative = false, labelFormat = R.string.proton_pill_protondb)
            if (game.linuxNative) ProtonPill(null, linuxNative = true, labelFormat = R.string.proton_pill_linux)
            DeckPill(game.deck)
            val store = game.store
            val reviewLabel = store?.reviewLabel
            if (store != null && reviewLabel != null && (store.reviewCount ?: 0) > 0) {
                Pill(
                    stringResource(R.string.store_review_pill, reviewLabel, store.reviewPercent ?: 0),
                    MaterialTheme.colorScheme.surfaceContainerHighest,
                    MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlaytimeCard(game: GameItem, onSetStatus: (PlayStatus?) -> Unit) {
    val res = LocalResources.current
    SectionCard(stringResource(R.string.playtime_title)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile(formatHours(res, game.playtimeHours), stringResource(R.string.playtime_total), Modifier.weight(1f))
            StatTile(formatHours(res, game.game.playtime2Weeks / 60.0), stringResource(R.string.playtime_two_weeks), Modifier.weight(1f))
            StatTile(shortRelativeDate(res, game.game.lastPlayed), stringResource(R.string.playtime_last_played), Modifier.weight(1f))
        }
        if (game.game.playtimeDeck > 0 || game.game.playtimeLinux > 0) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile(formatHours(res, game.game.playtimeDeck / 60.0), stringResource(R.string.playtime_deck), Modifier.weight(1f))
                StatTile(formatHours(res, game.game.playtimeLinux / 60.0), stringResource(R.string.playtime_linux), Modifier.weight(1f))
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
                    when {
                        game.status == PlayStatus.FINISHED -> stringResource(R.string.progress_marked_finished)
                        game.dropped -> stringResource(R.string.progress_marked_dropped)
                        remaining <= 0.0 -> stringResource(R.string.progress_past_main_story, formatHours(res, beat))
                        else -> stringResource(
                            R.string.progress_percent,
                            (progress * 100).roundToInt(),
                            formatHours(res, beat),
                            formatHours(res, remaining),
                        )
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        HorizontalDivider()
        Text(stringResource(R.string.status_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(null, PlayStatus.FINISHED, PlayStatus.DROPPED, PlayStatus.HIDDEN).forEach { option ->
                FilterChip(
                    selected = game.status == option,
                    onClick = { onSetStatus(option) },
                    label = { Text(stringResource(option?.labelRes() ?: R.string.status_automatic)) },
                )
            }
        }
        Text(
            stringResource(R.string.status_explanation),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HltbCard(
    game: GameItem,
    loading: Boolean,
    onChangeMatch: () -> Unit,
    onConfirmMatch: () -> Unit,
    onOpen: (Int) -> Unit,
) {
    val info = game.hltb
    val res = LocalResources.current
    SectionCard(stringResource(R.string.hltb_title)) {
        when {
            info == null && loading -> LoadingRow(stringResource(R.string.hltb_searching))
            info == null -> Text(stringResource(R.string.hltb_not_fetched), style = MaterialTheme.typography.bodyMedium)
            info.hltbId == null -> Text(
                stringResource(if (info.manual) R.string.hltb_marked_missing else R.string.hltb_no_match),
                style = MaterialTheme.typography.bodyMedium,
            )
            else -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatTile(formatSeconds(res, info.mainSeconds) ?: "–", stringResource(R.string.hltb_main), Modifier.weight(1f), polled(res, info.mainCount))
                    StatTile(formatSeconds(res, info.plusSeconds) ?: "–", stringResource(R.string.hltb_main_extras), Modifier.weight(1f), polled(res, info.plusCount))
                    StatTile(
                        formatSeconds(res, info.completionistSeconds) ?: "–",
                        stringResource(R.string.hltb_completionist),
                        Modifier.weight(1f),
                        polled(res, info.completionistCount),
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val fuzzy = info.needsReview
                    if (fuzzy) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = null,
                            tint = Palette.deck(DeckCompat.PLAYABLE).first,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    val name = info.name.orEmpty().let {
                        if (info.releaseYear > 0) stringResource(R.string.hltb_name_with_year, it, info.releaseYear) else it
                    }
                    Text(
                        stringResource(
                            when {
                                info.manual -> R.string.hltb_matched_manual
                                fuzzy -> R.string.hltb_matched_possible
                                else -> R.string.hltb_matched
                            },
                            name,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (info?.needsReview == true) {
                OutlinedButton(onClick = onConfirmMatch) {
                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.size(6.dp))
                    Text(stringResource(R.string.hltb_confirm_match))
                }
            }
            OutlinedButton(onClick = onChangeMatch) {
                Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.size(6.dp))
                Text(stringResource(if (info?.hltbId != null) R.string.hltb_wrong_game else R.string.hltb_search))
            }
            info?.hltbId?.let { id ->
                TextButton(onClick = { onOpen(id) }) {
                    Text(stringResource(R.string.hltb_open))
                    Spacer(Modifier.size(4.dp))
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

private fun polled(res: Resources, count: Int): String? =
    count.takeIf { it > 0 }?.let { res.getString(R.string.hltb_polled, formatCount(it)) }

@Composable
private fun ProtonCard(game: GameItem, loading: Boolean, onOpen: () -> Unit) {
    val info = game.proton
    val tier = info?.tier
    SectionCard(stringResource(R.string.proton_title)) {
        when {
            info == null && loading -> LoadingRow(stringResource(R.string.proton_loading))
            info == null || tier == null -> Text(
                stringResource(if (info == null) R.string.proton_not_fetched else R.string.proton_no_reports),
                style = MaterialTheme.typography.bodyMedium,
            )
            else -> {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    val (bg, fg) = Palette.proton(tier)
                    Pill(stringResource(tier.labelRes()).uppercase(LocalConfiguration.current.locales[0]), bg, fg)
                    Text(
                        info.confidence?.let { stringResource(R.string.proton_reports_confidence, formatCount(info.total), it) }
                            ?: stringResource(R.string.proton_reports, formatCount(info.total)),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                val trending = info.trendingTier?.let { stringResource(R.string.proton_trending, stringResource(it.labelRes())) }
                val best = info.bestReportedTier?.let { stringResource(R.string.proton_best_reported, stringResource(it.labelRes())) }
                val extra = listOfNotNull(trending, best).joinToString(" · ")
                if (extra.isNotEmpty()) {
                    Text(extra, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (game.linuxNative) {
            Text(
                stringResource(R.string.proton_native_note),
                style = MaterialTheme.typography.bodySmall,
                color = Palette.nativeLinux.first,
            )
        }
        TextButton(onClick = onOpen) {
            Text(stringResource(R.string.proton_open))
            Spacer(Modifier.size(4.dp))
            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
        }
    }
}

private val PLAYER_CATEGORY_NAMES = mapOf(
    2 to R.string.player_category_single,
    1 to R.string.player_category_multiplayer,
    9 to R.string.player_category_coop,
    38 to R.string.player_category_online_coop,
    48 to R.string.player_category_lan_coop,
    39 to R.string.player_category_split_coop,
    24 to R.string.player_category_shared_screen,
    49 to R.string.player_category_pvp,
    36 to R.string.player_category_online_pvp,
    47 to R.string.player_category_lan_pvp,
    37 to R.string.player_category_split_pvp,
    20 to R.string.player_category_mmo,
    27 to R.string.player_category_cross_platform,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StoreCard(game: GameItem, tagNames: Map<Int, String>) {
    val store = game.store ?: return
    if (!store.found) return
    val res = LocalResources.current
    SectionCard(stringResource(R.string.store_title)) {
        val platforms = listOfNotNull(
            R.string.platform_windows.takeIf { store.windows },
            R.string.platform_mac.takeIf { store.mac },
            R.string.platform_linux.takeIf { store.linux },
        ).joinToString(", ") { res.getString(it) }
        val players = store.playerCategoryIds.mapNotNull { PLAYER_CATEGORY_NAMES[it] }.joinToString(", ") { res.getString(it) }
        val controller = when {
            FULL_CONTROLLER_CATEGORY in store.controllerCategoryIds -> stringResource(R.string.controller_full)
            PARTIAL_CONTROLLER_CATEGORY in store.controllerCategoryIds -> stringResource(R.string.controller_partial)
            else -> null
        }
        InfoLine(stringResource(R.string.store_deck), stringResource(game.deck.labelRes()))
        if (platforms.isNotEmpty()) InfoLine(stringResource(R.string.store_platforms), platforms)
        if (players.isNotEmpty()) InfoLine(stringResource(R.string.store_players), players)
        controller?.let { InfoLine(stringResource(R.string.store_controller), it) }
        val reviewCount = store.reviewCount
        if (reviewCount != null && reviewCount > 0) {
            InfoLine(
                stringResource(R.string.store_reviews),
                stringResource(R.string.store_reviews_value, store.reviewLabel.orEmpty(), store.reviewPercent ?: 0, formatCount(reviewCount)).trim(),
            )
        }
        if (store.publishers.isNotEmpty()) InfoLine(stringResource(R.string.store_publisher), store.publishers.joinToString(", "))

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
        OutlinedButton(onClick = onStore, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.link_steam_store)) }
        OutlinedButton(onClick = onSteamDb, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.link_steamdb)) }
    }
}
