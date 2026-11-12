package dev.steamshelf.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import dev.steamshelf.BuildConfig
import dev.steamshelf.R
import androidx.compose.ui.res.stringResource
import dev.steamshelf.ui.common.labelRes
import dev.steamshelf.ui.common.SectionCard
import dev.steamshelf.ui.common.openUrl

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel, onBack: () -> Unit) {
    val account by viewModel.account.collectAsStateWithLifecycle()
    val prefs by viewModel.syncPrefs.collectAsStateWithLifecycle()
    val sync by viewModel.sync.collectAsStateWithLifecycle()
    val keyError by viewModel.keyError.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var editingKey by rememberSaveable { mutableStateOf(false) }
    var confirmSignOut by rememberSaveable { mutableStateOf(false) }

    val res = LocalResources.current
    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it.resolve(res))
            viewModel.messageShown()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back)) }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            account?.let { acc ->
                SectionCard(stringResource(R.string.settings_account)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (acc.avatarUrl != null) {
                            AsyncImage(
                                model = acc.avatarUrl,
                                contentDescription = null,
                                modifier = Modifier
                                    .size(56.dp)
                                    .clip(CircleShape),
                            )
                        } else {
                            Icon(
                                Icons.Default.AccountCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(56.dp),
                            )
                        }
                        Column(Modifier.weight(1f)) {
                            Text(acc.personaName.ifEmpty { stringResource(R.string.settings_steam_user) }, style = MaterialTheme.typography.titleMedium)
                            Text(
                                acc.steamId,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        acc.profileUrl?.let { url ->
                            OutlinedButton(onClick = { openUrl(context, url, externalApp = true) }) { Text(stringResource(R.string.settings_view_profile)) }
                        }
                        OutlinedButton(onClick = { confirmSignOut = true }) { Text(stringResource(R.string.settings_sign_out)) }
                    }
                    HorizontalDivider()
                    SettingRow(
                        title = stringResource(R.string.settings_api_key),
                        subtitle = "••••••••••••" + acc.apiKey.takeLast(4),
                        trailing = { TextButton(onClick = { editingKey = true }) { Text(stringResource(R.string.change)) } },
                    )
                }
            }

            SectionCard(stringResource(R.string.settings_data_sources)) {
                SwitchRow(
                    stringResource(R.string.settings_protondb),
                    stringResource(R.string.settings_protondb_hint),
                    prefs.autoProtonDb,
                    viewModel::setAutoProtonDb,
                )
                SwitchRow(
                    stringResource(R.string.settings_hltb),
                    stringResource(R.string.settings_hltb_hint),
                    prefs.autoHltb,
                    viewModel::setAutoHltb,
                )
                SwitchRow(
                    stringResource(R.string.settings_free_games),
                    stringResource(R.string.settings_free_games_hint),
                    prefs.includeFreeGames,
                    viewModel::setIncludeFreeGames,
                )
            }

            SectionCard(stringResource(R.string.settings_sync)) {
                val phase = stringResource(sync.phase?.labelRes() ?: R.string.sync_starting)
                Text(
                    when {
                        sync.running && sync.total > 0 -> stringResource(R.string.settings_sync_running_count, phase, sync.done, sync.total)
                        sync.running -> stringResource(R.string.settings_sync_running, phase)
                        prefs.lastLibrarySync > 0 -> stringResource(
                            R.string.settings_last_synced,
                            android.text.format.DateUtils.getRelativeTimeSpanString(prefs.lastLibrarySync).toString(),
                        )
                        else -> stringResource(R.string.settings_not_synced)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = viewModel::refreshEverything, enabled = !sync.running) { Text(stringResource(R.string.settings_refresh_everything)) }
                    OutlinedButton(onClick = { viewModel.clearCachedData() }) { Text(stringResource(R.string.settings_clear_cache)) }
                }
                Text(
                    stringResource(R.string.settings_sync_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SectionCard(stringResource(R.string.settings_about)) {
                Text(
                    stringResource(R.string.settings_about_text, BuildConfig.VERSION_NAME),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { openUrl(context, "https://www.protondb.com") }) { Text(stringResource(R.string.settings_link_protondb)) }
                    TextButton(onClick = { openUrl(context, "https://howlongtobeat.com") }) { Text(stringResource(R.string.settings_link_hltb)) }
                }
            }
        }
    }

    if (editingKey) {
        var key by rememberSaveable { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = {
                editingKey = false
                viewModel.clearKeyError()
            },
            title = { Text(stringResource(R.string.settings_new_key_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = key,
                        onValueChange = { key = it },
                        label = { Text(stringResource(R.string.settings_api_key)) },
                        singleLine = true,
                        isError = keyError != null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    keyError?.let { Text(it.resolve(res), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    TextButton(onClick = { openUrl(context, "https://steamcommunity.com/dev/apikey") }) { Text(stringResource(R.string.settings_open_key_page)) }
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.updateApiKey(key) { editingKey = false } }) { Text(stringResource(R.string.save)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    editingKey = false
                    viewModel.clearKeyError()
                }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text(stringResource(R.string.settings_sign_out_title)) },
            text = { Text(stringResource(R.string.settings_sign_out_message)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmSignOut = false
                    viewModel.signOut()
                }) { Text(stringResource(R.string.settings_sign_out), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun SettingRow(title: String, subtitle: String, trailing: @Composable () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle, fontFamily = FontFamily.Monospace) },
        trailingContent = trailing,
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
