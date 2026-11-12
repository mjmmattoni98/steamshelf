package dev.steamshelf.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.annotation.StringRes
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.steamshelf.R
import dev.steamshelf.model.DeckCompat
import dev.steamshelf.model.PlayStatus
import dev.steamshelf.model.ProtonTier

/** Loads the first image that exists from [urls] (Steam's newer games use hashed asset paths). */
@Composable
fun SteamImage(
    urls: List<String>,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    var index by remember(urls) { mutableIntStateOf(0) }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        val url = urls.getOrNull(index)
        if (url == null) {
            Icon(
                Icons.Default.SportsEsports,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            )
        } else {
            AsyncImage(
                model = url,
                contentDescription = contentDescription,
                contentScale = contentScale,
                modifier = Modifier.matchParentSize(),
                onError = { index++ },
            )
        }
    }
}

@Composable
fun Pill(
    text: String,
    container: Color,
    content: Color,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    iconDescription: String? = null,
) {
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(container)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (icon != null) Icon(icon, contentDescription = iconDescription, tint = content, modifier = Modifier.size(12.dp))
        Text(
            text,
            color = content,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

object Palette {
    val nativeLinux = Color(0xFF3FA34D) to Color.White

    /** Same amber as "Deck Playable": readable on light and dark surfaces and on artwork. */
    val warning = Color(0xFFFFC82C) to Color(0xFF2B2100)
    val finished = Color(0xFF59BF40) to Color(0xFF0F2A06)

    fun proton(tier: ProtonTier): Pair<Color, Color> = when (tier) {
        ProtonTier.PLATINUM -> Color(0xFFB4C7DC) to Color(0xFF1B2330)
        ProtonTier.GOLD -> Color(0xFFCFB53B) to Color(0xFF2A2200)
        ProtonTier.SILVER -> Color(0xFFA6A6A6) to Color(0xFF1E1E1E)
        ProtonTier.BRONZE -> Color(0xFFCD7F32) to Color(0xFF2B1600)
        ProtonTier.BORKED -> Color(0xFFE5484D) to Color.White
        ProtonTier.PENDING -> Color(0xFF44505D) to Color(0xFFE1E6EC)
    }

    fun deck(compat: DeckCompat): Pair<Color, Color> = when (compat) {
        DeckCompat.VERIFIED -> Color(0xFF59BF40) to Color(0xFF0F2A06)
        DeckCompat.PLAYABLE -> Color(0xFFFFC82C) to Color(0xFF2B2100)
        DeckCompat.UNSUPPORTED -> Color(0xFF8F98A0) to Color(0xFF15191D)
        DeckCompat.UNKNOWN -> Color(0xFF44505D) to Color(0xFFE1E6EC)
    }

    fun deckIcon(compat: DeckCompat): ImageVector = when (compat) {
        DeckCompat.VERIFIED -> Icons.Default.CheckCircle
        DeckCompat.PLAYABLE -> Icons.Default.Info
        DeckCompat.UNSUPPORTED -> Icons.Default.Block
        DeckCompat.UNKNOWN -> Icons.Default.Info
    }
}

/** ProtonDB tier or native Linux; [labelFormat] wraps the label, e.g. "ProtonDB %1$s". */
@Composable
fun ProtonPill(tier: ProtonTier?, linuxNative: Boolean, modifier: Modifier = Modifier, @StringRes labelFormat: Int? = null) {
    @Composable
    fun label(@StringRes text: Int): String {
        val plain = stringResource(text)
        return if (labelFormat == null) plain else stringResource(labelFormat, plain)
    }
    when {
        linuxNative -> Pill(
            label(R.string.proton_native), Palette.nativeLinux.first, Palette.nativeLinux.second, modifier, Icons.Default.Terminal,
        )
        tier != null -> {
            val (bg, fg) = Palette.proton(tier)
            Pill(label(tier.labelRes()), bg, fg, modifier)
        }
    }
}

@Composable
fun DeckPill(compat: DeckCompat, modifier: Modifier = Modifier, showLabelPrefix: Boolean = true) {
    if (compat == DeckCompat.UNKNOWN) return
    val (bg, fg) = Palette.deck(compat)
    val label = stringResource(compat.labelRes())
    Pill(
        if (showLabelPrefix) stringResource(R.string.deck_pill, label) else label,
        bg,
        fg,
        modifier,
        Palette.deckIcon(compat),
    )
}

/** HowLongToBeat time; [uncertain] marks an automatic match that is only a fuzzy fit. */
@Composable
fun HltbPill(hours: Double?, modifier: Modifier = Modifier, uncertain: Boolean = false) {
    if (hours == null) return
    if (uncertain) {
        Pill(
            formatHours(LocalResources.current, hours),
            Palette.warning.first,
            Palette.warning.second,
            modifier,
            Icons.Default.Warning,
            iconDescription = stringResource(R.string.hltb_uncertain_badge),
        )
    } else {
        Pill(
            formatHours(LocalResources.current, hours),
            MaterialTheme.colorScheme.surfaceContainerHighest,
            MaterialTheme.colorScheme.onSurface,
            modifier,
            Icons.Default.Schedule,
        )
    }
}

/** The user's own status for a game; nothing for automatic. */
@Composable
fun StatusPill(status: PlayStatus?, modifier: Modifier = Modifier) {
    when (status) {
        null -> Unit
        PlayStatus.FINISHED -> Pill(
            stringResource(status.labelRes()), Palette.finished.first, Palette.finished.second, modifier, Icons.Default.Flag,
        )
        PlayStatus.DROPPED -> Pill(
            stringResource(status.labelRes()),
            MaterialTheme.colorScheme.surfaceContainerHighest,
            MaterialTheme.colorScheme.onSurfaceVariant,
            modifier,
            Icons.Default.Block,
        )
        PlayStatus.HIDDEN -> Pill(
            stringResource(status.labelRes()),
            MaterialTheme.colorScheme.surfaceContainerHighest,
            MaterialTheme.colorScheme.onSurfaceVariant,
            modifier,
            Icons.Default.VisibilityOff,
        )
    }
}

@Composable
fun SectionCard(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

/** Big number with a caption, used for playtime and HLTB times. */
@Composable
fun StatTile(value: String, label: String, modifier: Modifier = Modifier, caption: String? = null) {
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1)
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (caption != null) {
            Text(
                caption,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                maxLines = 1,
            )
        }
    }
}
