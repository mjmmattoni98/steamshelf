package dev.steamshelf.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.steamshelf.model.GameItem
import dev.steamshelf.model.SortField
import dev.steamshelf.ui.common.DeckPill
import dev.steamshelf.ui.common.HltbPill
import dev.steamshelf.ui.common.ProtonPill
import dev.steamshelf.ui.common.SteamImage
import dev.steamshelf.ui.common.formatHours
import dev.steamshelf.ui.common.formatMinutes
import dev.steamshelf.ui.common.relativeDate

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GameRow(item: GameItem, sortField: SortField, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            SteamImage(
                urls = item.headerUrls,
                contentDescription = null,
                modifier = Modifier
                    .width(124.dp)
                    .aspectRatio(460f / 215f)
                    .clip(RoundedCornerShape(6.dp)),
            )
            Column(
                Modifier
                    .weight(1f)
                    .padding(start = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    item.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    subtitle(item, sortField),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    ProtonPill(item.protonTier, item.linuxNative)
                    DeckPill(item.deck, showLabelPrefix = false)
                    HltbPill(item.beatHours)
                }
                item.progress?.takeIf { item.game.playtimeForever > 0 }?.let { progress ->
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp)
                            .clip(RoundedCornerShape(2.dp)),
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        drawStopIndicator = {},
                        gapSize = 0.dp,
                    )
                }
            }
        }
    }
}

/** What the second line shows depends on the sort, so the number you're sorting by is visible. */
private fun subtitle(item: GameItem, sortField: SortField): String {
    val played = formatMinutes(item.game.playtimeForever)
    return when (sortField) {
        SortField.LAST_PLAYED -> if (item.game.lastPlayed > 0) "Last played ${relativeDate(item.game.lastPlayed)}" else played
        SortField.TIME_LEFT -> item.remainingHours?.let { "$played · ${formatHours(it)} left" } ?: played
        SortField.REVIEWS -> item.store?.reviewPercent?.let { "$played · $it% positive" } ?: played
        SortField.RELEASE -> item.releaseYear?.let { "$played · $it" } ?: played
        else -> if (item.game.playtime2Weeks > 0) "$played · ${formatHours(item.game.playtime2Weeks / 60.0)} last 2 weeks" else played
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GameGridCell(item: GameItem, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f),
        ) {
            SteamImage(urls = item.capsuleUrls, contentDescription = item.name, modifier = Modifier.matchParentSize())
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000))))
                    .padding(6.dp),
            ) {
                // Cells can be narrow, so let the badges wrap instead of clipping.
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    ProtonPill(item.protonTier, item.linuxNative)
                    HltbPill(item.beatHours)
                    DeckPill(item.deck, showLabelPrefix = false)
                }
            }
        }
        Text(
            item.name,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
        )
    }
}
