package mobi.beyondpod.revival.ui.screens.feeddetail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.platform.LocalContext
import mobi.beyondpod.revival.service.PlaybackService
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import mobi.beyondpod.revival.data.local.entity.DownloadStrategy
import mobi.beyondpod.revival.data.local.entity.FeedEntity
import mobi.beyondpod.revival.ui.components.EpisodeListItem

/**
 * Feed detail screen — episode list (tab 0) + feed settings (tab 1).
 *
 * Tab 0 (Episodes):
 *   - Feed header with artwork, title, description, last-updated
 *   - LazyColumn of episodes reusing [EpisodeListItem]
 *
 * Tab 1 (Settings):
 *   - Key feed properties from §7.1 FeedPropertiesView (General + Advanced tabs)
 *   - Read-only for now; edit actions are Phase 7
 *
 * Overflow menu: Mark All Played, Unsubscribe (with confirmation dialog)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedDetailScreen(
    navController: NavController,
    viewModel: FeedDetailViewModel = hiltViewModel()
) {
    val uiState      by viewModel.uiState.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val context      = LocalContext.current
    var selectedTab by remember { mutableIntStateOf(0) }
    var showMenu by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    val title = (uiState as? FeedDetailUiState.Success)?.feed?.title ?: "Feed"
                    Text(title, fontWeight = FontWeight.SemiBold, maxLines = 1)
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "More")
                    }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("Mark All Played") },
                            onClick = {
                                viewModel.markAllPlayed()
                                showMenu = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Unsubscribe") },
                            onClick = {
                                showDeleteDialog = true
                                showMenu = false
                            }
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding)) {
            when (val state = uiState) {
                is FeedDetailUiState.Loading -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }

                is FeedDetailUiState.Error -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(state.message)
                    }
                }

                is FeedDetailUiState.Success -> {
                    Column(modifier = Modifier.fillMaxSize()) {
                        TabRow(selectedTabIndex = selectedTab) {
                            Tab(
                                selected = selectedTab == 0,
                                onClick = { selectedTab = 0 },
                                text = { Text("Episodes (${state.episodes.size})") }
                            )
                            Tab(
                                selected = selectedTab == 1,
                                onClick = { selectedTab = 1 },
                                text = { Text("Settings") }
                            )
                        }

                        when (selectedTab) {
                            0 -> PullToRefreshBox(
                                isRefreshing = isRefreshing,
                                onRefresh    = viewModel::refresh,
                                modifier     = Modifier.weight(1f)
                            ) {
                                EpisodesTab(
                                    state = state,
                                    onEpisodeClick = { episodeId ->
                                        context.startService(
                                            PlaybackService.playEpisodeIntent(context, episodeId)
                                        )
                                    },
                                    onDownloadClick = viewModel::downloadEpisode
                                )
                            }
                            1 -> SettingsTab(feed = state.feed)
                        }
                    }
                }
            }
        }
    }

    // Unsubscribe confirmation dialog
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Unsubscribe") },
            text = {
                val feedTitle = (uiState as? FeedDetailUiState.Success)?.feed?.title ?: "this feed"
                Text("Remove $feedTitle from your subscriptions? Downloaded episodes will also be deleted.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteFeed { navController.popBackStack() }
                        showDeleteDialog = false
                    }
                ) {
                    Text("Unsubscribe", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun EpisodesTab(
    state: FeedDetailUiState.Success,
    onEpisodeClick: (Long) -> Unit,
    onDownloadClick: (Long) -> Unit
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        // Feed header
        item {
            FeedHeader(feed = state.feed)
            HorizontalDivider()
        }

        if (state.episodes.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("No episodes yet", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                }
            }
        } else {
            items(items = state.episodes, key = { it.id }) { episode ->
                EpisodeListItem(
                    episode = episode,
                    onClick = { onEpisodeClick(episode.id) },
                    onDownloadClick = { onDownloadClick(episode.id) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                )
                HorizontalDivider(
                    modifier = Modifier.padding(start = 76.dp),
                    thickness = 0.5.dp
                )
            }
        }
    }
}

@Composable
private fun FeedHeader(feed: FeedEntity) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .size(80.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            if (feed.imageUrl != null) {
                AsyncImage(
                    model = feed.imageUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = feed.title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
            )
            if (feed.author.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = feed.author,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
            if (feed.lastUpdated > 0) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "Updated ${formatTimestamp(feed.lastUpdated)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }
        }
    }

    if (feed.description.isNotEmpty()) {
        Text(
            text = feed.description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp),
            maxLines = 4
        )
    }
}

@Composable
private fun SettingsTab(feed: FeedEntity) {
    LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        item {
            SettingsSectionHeader("General")
            SettingsRow(label = "Feed URL", value = feed.url)
            SettingsRow(label = "Priority", value = if (feed.priority == 0) "Normal" else "High")
            SettingsRow(
                label = "Feed authentication",
                value = if (feed.hasAuthPassword) "Configured" else "None"
            )
        }

        item {
            Spacer(Modifier.height(16.dp))
            SettingsSectionHeader("Download")
            SettingsRow(
                label = "Download strategy",
                value = feed.downloadStrategy.toDisplayName()
            )
            SettingsRow(
                label = "Episodes to download",
                value = feed.downloadCount?.toString() ?: "Global default"
            )
            SettingsRow(
                label = "Episodes to keep",
                value = when (feed.maxEpisodesToKeep) {
                    null -> "Global default"
                    0    -> "Keep all"
                    else -> feed.maxEpisodesToKeep.toString()
                }
            )
            SettingsRow(
                label = "Download only on WiFi",
                value = when (feed.downloadOnlyOnWifi) {
                    true  -> "Yes"
                    false -> "No"
                    null  -> "Global default"
                }
            )
        }

        item {
            Spacer(Modifier.height(16.dp))
            SettingsSectionHeader("Playback")
            SettingsRow(
                label = "Playback speed",
                value = feed.playbackSpeed?.let { "${it}x" } ?: "Global default"
            )
            SettingsRow(label = "Skip intro", value = "${feed.skipIntroSeconds}s")
            SettingsRow(label = "Skip outro", value = "${feed.skipOutroSeconds}s")
            SettingsRow(
                label = "Volume boost",
                value = if (feed.playbackVolumeBoost == 0) "Global default"
                        else "${feed.playbackVolumeBoost}/10"
            )
        }

        item {
            Spacer(Modifier.height(16.dp))
            SettingsSectionHeader("Advanced")
            SettingsRow(
                label = "Use Google proxy",
                value = if (feed.useGoogleProxy) "Yes" else "No"
            )
        }
    }
}

@Composable
private fun SettingsSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 4.dp)
    )
}

@Composable
private fun SettingsRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
    }
    HorizontalDivider(thickness = 0.5.dp)
}

private fun DownloadStrategy.toDisplayName(): String = when (this) {
    DownloadStrategy.GLOBAL           -> "Global default"
    DownloadStrategy.DOWNLOAD_NEWEST  -> "Download newest"
    DownloadStrategy.DOWNLOAD_IN_ORDER -> "Download in order"
    DownloadStrategy.STREAM_NEWEST    -> "Stream newest"
    DownloadStrategy.MANUAL           -> "Manual"
}

private fun formatTimestamp(epochMs: Long): String {
    val sdf = java.text.SimpleDateFormat("MMM d, yyyy", java.util.Locale.getDefault())
    return sdf.format(java.util.Date(epochMs))
}
