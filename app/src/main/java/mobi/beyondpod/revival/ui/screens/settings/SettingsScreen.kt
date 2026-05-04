package mobi.beyondpod.revival.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import mobi.beyondpod.revival.ui.navigation.Screen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    navController: NavController,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {

            // ── Playback ──────────────────────────────────────────────────────
            item { SectionHeader("Playback", Icons.AutoMirrored.Filled.VolumeUp) }
            item {
                StepperPref(
                    title   = "Skip back",
                    subtitle = "${state.skipBackSeconds}s",
                    onDecrement = { if (state.skipBackSeconds > 5) viewModel.setSkipBackSeconds(state.skipBackSeconds - 5) },
                    onIncrement = { if (state.skipBackSeconds < 120) viewModel.setSkipBackSeconds(state.skipBackSeconds + 5) }
                )
            }
            item {
                StepperPref(
                    title    = "Skip forward",
                    subtitle = "${state.skipForwardSeconds}s",
                    onDecrement = { if (state.skipForwardSeconds > 5) viewModel.setSkipForwardSeconds(state.skipForwardSeconds - 5) },
                    onIncrement = { if (state.skipForwardSeconds < 120) viewModel.setSkipForwardSeconds(state.skipForwardSeconds + 5) }
                )
            }
            item {
                SwitchPref(
                    title   = "Skip silence",
                    checked = state.skipSilence,
                    onCheckedChange = viewModel::setSkipSilence
                )
            }
            item {
                SwitchPref(
                    title   = "Pause on headphone unplug",
                    checked = state.pauseOnHeadphoneUnplug,
                    onCheckedChange = viewModel::setPauseOnHeadphoneUnplug
                )
            }
            item {
                SwitchPref(
                    title   = "Pause on incoming notification",
                    checked = state.pauseOnNotification,
                    onCheckedChange = viewModel::setPauseOnNotification
                )
            }
            item {
                SwitchPref(
                    title   = "Continue playback after episode ends",
                    checked = state.continuousPlayback,
                    onCheckedChange = viewModel::setContinuousPlayback
                )
            }
            item {
                SwitchPref(
                    title    = "Play next episode",
                    subtitle = if (state.autoplayNewerNext) "Advances to newer episode" else "Advances to older episode",
                    checked  = state.autoplayNewerNext,
                    onCheckedChange = viewModel::setAutoplayNewerNext,
                    enabled  = state.continuousPlayback
                )
            }
            item { HorizontalDivider() }

            // ── Updates ───────────────────────────────────────────────────────
            item { SectionHeader("Feed Updates", Icons.Default.Refresh) }
            item {
                SwitchPref(
                    title   = "Auto-update feeds",
                    checked = state.autoUpdateEnabled,
                    onCheckedChange = viewModel::setAutoUpdateEnabled
                )
            }
            item {
                ListPref(
                    title    = "Update interval",
                    subtitle = "${state.updateIntervalHours}h",
                    options  = listOf(1, 2, 4, 6, 12, 24),
                    current  = state.updateIntervalHours,
                    label    = { "${it}h" },
                    onSelect = viewModel::setUpdateIntervalHours
                )
            }
            item {
                SwitchPref(
                    title   = "Update on Wi-Fi only",
                    checked = state.updateOnWifiOnly,
                    onCheckedChange = viewModel::setUpdateOnWifiOnly
                )
            }
            item { HorizontalDivider() }

            // ── Downloads ─────────────────────────────────────────────────────
            item { SectionHeader("Downloads", Icons.Default.Download) }
            item {
                SwitchPref(
                    title   = "Download on Wi-Fi only",
                    checked = state.downloadOnWifiOnly,
                    onCheckedChange = viewModel::setDownloadOnWifiOnly
                )
            }
            item {
                ListPref(
                    title    = "Auto-download count (per feed)",
                    subtitle = if (state.globalDownloadCount == 0) "Disabled" else state.globalDownloadCount.toString(),
                    options  = listOf(0, 1, 3, 5, 10, 20),
                    current  = state.globalDownloadCount,
                    label    = { if (it == 0) "Disabled" else it.toString() },
                    onSelect = viewModel::setGlobalDownloadCount
                )
            }
            item {
                ListPref(
                    title    = "Keep downloaded episodes",
                    subtitle = if (state.globalMaxKeep == 0) "Keep all" else state.globalMaxKeep.toString(),
                    options  = listOf(0, 1, 3, 5, 10, 20, 50),
                    current  = state.globalMaxKeep,
                    label    = { if (it == 0) "Keep all" else it.toString() },
                    onSelect = viewModel::setGlobalMaxKeep
                )
            }
            item {
                ListPref(
                    title    = "Delete episodes older than",
                    subtitle = deleteOlderThanLabel(state.globalDeleteOlderThanDays),
                    options  = listOf(7, 30, 90, 180, 365, 99999),
                    current  = state.globalDeleteOlderThanDays,
                    label    = ::deleteOlderThanLabel,
                    onSelect = viewModel::setGlobalDeleteOlderThanDays
                )
            }
            item {
                SwitchPref(
                    title   = "Auto-delete played episodes",
                    checked = state.autoDeletePlayed,
                    onCheckedChange = viewModel::setAutoDeletePlayed
                )
            }
            item {
                PrefItem(
                    title    = "Clean up downloads now",
                    subtitle = "Delete episodes beyond your keep limit across all feeds",
                    onClick  = viewModel::cleanUpNow
                )
            }
            item { HorizontalDivider() }

            // ── Notifications ─────────────────────────────────────────────────
            item { SectionHeader("Notifications", Icons.Default.Notifications) }
            item {
                SwitchPref("New episodes",         state.notifNewEpisodes,      viewModel::setNotifNewEpisodes)
            }
            item {
                SwitchPref("Download complete",    state.notifDownloadComplete, viewModel::setNotifDownloadComplete)
            }
            item {
                SwitchPref("Download failed",      state.notifDownloadFailed,   viewModel::setNotifDownloadFailed)
            }
            item {
                SwitchPref("Playback controls",    state.notifPlaybackControls, viewModel::setNotifPlaybackControls)
            }
            item {
                SwitchPref("Feed update failed",   state.notifFeedUpdateFailed, viewModel::setNotifFeedUpdateFailed)
            }
            item {
                SwitchPref("Episode finished",     state.notifEpisodeFinished,  viewModel::setNotifEpisodeFinished)
            }
            item {
                SwitchPref("Queue empty",          state.notifQueueEmpty,       viewModel::setNotifQueueEmpty)
            }
            item {
                SwitchPref("New subscription",     state.notifSubscriptionNew,  viewModel::setNotifSubscriptionNew)
            }
            item {
                SwitchPref("Storage low",          state.notifStorageLow,       viewModel::setNotifStorageLow)
            }
            item {
                SwitchPref("Auto-add episodes",    state.notifAutoAdd,          viewModel::setNotifAutoAdd)
            }
            item {
                SwitchPref("Sleep timer",          state.notifSleepTimer,       viewModel::setNotifSleepTimer)
            }
            item {
                SwitchPref("Rewind reminder",      state.notifRewindReminder,   viewModel::setNotifRewindReminder)
            }
            item {
                SwitchPref("Auto-cleanup",         state.notifCleanup,          viewModel::setNotifCleanup)
            }
            item {
                SwitchPref("Update check",         state.notifUpdateCheck,      viewModel::setNotifUpdateCheck)
            }
            item {
                SwitchPref("Wi-Fi required",       state.notifWifiRequired,     viewModel::setNotifWifiRequired)
            }
            item {
                SwitchPref("Scrobble error",       state.notifScrobbleError,    viewModel::setNotifScrobbleError)
            }
            item {
                SwitchPref("Gpodder sync",         state.notifGpodderSync,      viewModel::setNotifGpodderSync)
            }
            item {
                SwitchPref("Download on charge",   state.notifChargeDownload,   viewModel::setNotifChargeDownload)
            }
            item {
                SwitchPref("Playlist rebuild",     state.notifPlaylistRebuild,  viewModel::setNotifPlaylistRebuild)
            }
            item {
                SwitchPref("General errors",       state.notifErrorGeneric,     viewModel::setNotifErrorGeneric)
            }
            item { HorizontalDivider() }

            // ── Interface ─────────────────────────────────────────────────────
            item { SectionHeader("Interface", Icons.Default.Palette) }
            item {
                ListPref(
                    title    = "Theme",
                    subtitle = state.theme.replaceFirstChar { it.uppercase() },
                    options  = listOf("system", "light", "dark"),
                    current  = state.theme,
                    label    = { it.replaceFirstChar { c -> c.uppercase() } },
                    onSelect = viewModel::setTheme
                )
            }
            item { HorizontalDivider() }

            // ── Scrobbling ────────────────────────────────────────────────────
            item { SectionHeader("Scrobbling", Icons.Default.Star) }
            item {
                SwitchPref(
                    title   = "Enable scrobbling",
                    checked = state.scrobbleEnabled,
                    onCheckedChange = viewModel::setScrobbleEnabled
                )
            }
            item { HorizontalDivider() }

            // ── Backup & Restore ──────────────────────────────────────────────
            item { SectionHeader("Backup & Restore", Icons.Default.CloudSync) }
            item {
                PrefItem(
                    title = "Backup & Restore",
                    subtitle = "Import/export feeds (OPML) and app settings",
                    onClick = { navController.navigate(Screen.BackupRestore.route) }
                )
            }
            item { HorizontalDivider() }

            // ── About ─────────────────────────────────────────────────────────
            item { SectionHeader("About", Icons.Default.Info) }
            item {
                PrefItem(title = "BeyondPod Revival", subtitle = "Version 5.0.0 — Open source, MIT License")
            }

            item { Spacer(Modifier.height(32.dp)) }
        }
    }
}

// ── Helpers ───────────────────────────────────────────────────────────────────

private fun deleteOlderThanLabel(days: Int): String = when (days) {
    7     -> "1 week"
    30    -> "1 month"
    90    -> "3 months"
    180   -> "6 months"
    365   -> "1 year"
    else  -> "Never"
}

// ── Pref composables ──────────────────────────────────────────────────────────

@Composable
private fun SectionHeader(title: String, icon: ImageVector) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(end = 8.dp)
        )
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun SwitchPref(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    subtitle: String? = null,
    enabled: Boolean = true
) {
    val alpha = if (enabled) 1f else 0.38f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha)
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f * alpha)
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

@Composable
private fun StepperPref(
    title: String,
    subtitle: String,
    onDecrement: () -> Unit,
    onIncrement: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
        IconButton(onClick = onDecrement) {
            Text("−", style = MaterialTheme.typography.titleMedium)
        }
        IconButton(onClick = onIncrement) {
            Text("+", style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun <T> ListPref(
    title: String,
    subtitle: String,
    options: List<T>,
    current: T,
    label: (T) -> String,
    onSelect: (T) -> Unit
) {
    // Simple cycling preference — tap to cycle through options
    val currentIdx = options.indexOf(current).coerceAtLeast(0)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect(options[(currentIdx + 1) % options.size]) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
    }
}

@Composable
private fun PrefItem(
    title: String,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null
) {
    val modifier = if (onClick != null) {
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    } else {
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    }
    Column(modifier = modifier) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        if (subtitle != null) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
    }
}
