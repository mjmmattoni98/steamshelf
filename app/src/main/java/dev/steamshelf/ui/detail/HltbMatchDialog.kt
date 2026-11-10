package dev.steamshelf.ui.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.steamshelf.data.hltb.HltbGame
import dev.steamshelf.ui.common.SteamImage
import dev.steamshelf.ui.common.formatSeconds

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HltbMatchDialog(
    gameName: String,
    state: MatchSearchState,
    onQueryChange: (String) -> Unit,
    onSearch: (String) -> Unit,
    onPick: (HltbGame) -> Unit,
    onNoMatch: () -> Unit,
    onAutoMatch: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .imePadding()
                    .navigationBarsPadding(),
            ) {
                TopAppBar(
                    title = {
                        Column {
                            Text("Match on HowLongToBeat")
                            Text(
                                gameName,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = "Close") }
                    },
                )
                OutlinedTextField(
                    value = state.query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    label = { Text("Search HowLongToBeat") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onSearch(state.query) }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                )
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                ) {
                    when {
                        state.searching && state.results.isEmpty() ->
                            CircularProgressIndicator(Modifier.align(Alignment.Center))
                        state.error != null -> Text(
                            state.error,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier
                                .align(Alignment.Center)
                                .padding(24.dp),
                        )
                        state.results.isEmpty() -> Text(
                            "No results. Try a shorter or different name.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .align(Alignment.Center)
                                .padding(24.dp),
                        )
                        else -> LazyColumn(Modifier.fillMaxSize()) {
                            items(state.results, key = { it.id }) { result ->
                                ResultRow(result, onClick = { onPick(result) })
                                HorizontalDivider()
                            }
                        }
                    }
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(onClick = onNoMatch, modifier = Modifier.weight(1f)) { Text("Not on HLTB") }
                    OutlinedButton(onClick = onAutoMatch, modifier = Modifier.weight(1f)) { Text("Auto-match") }
                }
            }
        }
    }
}

@Composable
private fun ResultRow(game: HltbGame, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SteamImage(
            urls = listOfNotNull(game.imageUrl),
            contentDescription = null,
            modifier = Modifier
                .width(44.dp)
                .size(width = 44.dp, height = 58.dp)
                .clip(RoundedCornerShape(4.dp)),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(game.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 2)
            val meta = listOfNotNull(
                game.releaseYear.takeIf { it > 0 }?.toString(),
                game.type?.takeIf { it.isNotBlank() && it != "game" }?.uppercase(),
                game.platforms?.takeIf { it.isNotBlank() },
            ).joinToString(" · ")
            if (meta.isNotEmpty()) {
                Text(
                    meta,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val times = listOfNotNull(
                formatSeconds(game.main)?.let { "Main $it" },
                formatSeconds(game.plus)?.let { "+Extras $it" },
                formatSeconds(game.completionist)?.let { "100% $it" },
            ).joinToString(" · ")
            Text(
                times.ifEmpty { "No times yet" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
