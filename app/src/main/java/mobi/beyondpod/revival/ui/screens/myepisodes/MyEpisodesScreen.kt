package mobi.beyondpod.revival.ui.screens.myepisodes

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import mobi.beyondpod.revival.data.local.entity.DownloadStateEnum
import mobi.beyondpod.revival.data.local.entity.EpisodeEntity
import mobi.beyondpod.revival.data.local.entity.PlayState
import mobi.beyondpod.revival.service.PlaybackService
import mobi.beyondpod.revival.ui.navigation.Screen
import mobi.beyondpod.revival.ui.theme.EpisodeInProgress
import mobi.beyondpod.revival.ui.theme.EpisodeStarred
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * "What to Play" landing screen — flat list of the 50 most recent episodes across all feeds,
 * ordered by publish date descending. Matches original BeyondPod layout.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyEpisodesScreen(
    navController: NavController,
    viewModel: MyEpisodesViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val feedImageUrls by viewModel.feedImageUrls.collectAsState()
    val feedTitles by viewModel.feedTitles.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("What to Play", fontWeight = FontWeight.SemiBold) },
                actions = {
                    FilledTonalIconButton(onClick = {
                        viewModel.buildQueueAndPlay()
                        context.startService(Intent(context, PlaybackService::class.java))
                    }) {
                        Icon(Icons.Default.PlayArrow, contentDescription = "Play All")
                    }
                    IconButton(onClick = {
                        viewModel.shuffleAndPlay()
                        context.startService(Intent(context, PlaybackService::class.java))
                    }) {
                        Icon(Icons.Default.Shuffle, contentDescription = "Shuffle")
                    }
                }
            )
        }
    ) { innerPadding ->
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = { viewModel.refresh() },
            modifier = Modifier.padding(innerPadding)
        ) {
            when (val state = uiState) {
                is MyEpisodesUiState.Loading -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }

                is MyEpisodesUiState.Error -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(state.message)
                    }
                }

                is MyEpisodesUiState.Success -> {
                    if (state.episodes.isEmpty()) {
                        EmptyWhatToPlay(
                            onAddFeed = { navController.navigate(Screen.AddFeed.route) }
                        )
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(
                                items = state.episodes,
                                key = { it.id }
                            ) { episode ->
                                WhatToPlayRow(
                                    episode = episode,
                                    feedTitle = feedTitles[episode.feedId],
                                    feedImageUrl = feedImageUrls[episode.feedId],
                                    onClick = {
                                        context.startService(
                                            PlaybackService.playEpisodeIntent(context, episode.id)
                                        )
                                        navController.navigate(Screen.FullPlayer.route)
                                    },
                                    onDownloadClick = { viewModel.downloadEpisode(episode.id) }
                                )
                                HorizontalDivider(
                                    modifier = Modifier.padding(start = 88.dp),
                                    thickness = 0.5.dp
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Episode row styled to match original BeyondPod "What to Play":
 *   [artwork]  DATE • FEED NAME
 *              Episode Title (bold, 2 lines)
 *              description preview (2 lines, muted)
 *              ▶  duration          [download state]
 */
@Composable
private fun WhatToPlayRow(
    episode: EpisodeEntity,
    feedTitle: String?,
    feedImageUrl: String?,
    onClick: () -> Unit,
    onDownloadClick: () -> Unit
) {
    val alpha = if (episode.playState == PlayState.PLAYED) 0.45f else 1f

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = "Play ${episode.title}", onClick = onClick)
            .alpha(alpha)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top
    ) {
        // Square artwork
        val artUrl = episode.imageUrl ?: feedImageUrl
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            if (artUrl != null) {
                AsyncImage(
                    model = artUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxHeight()
                )
            }
        }

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {

            // "19 APR  •  FEED NAME"
            val datePart = formatDateShort(episode.pubDate)
            val feedPart = feedTitle?.uppercase(Locale.getDefault())
            val metaLine = if (!feedPart.isNullOrBlank()) "$datePart  •  $feedPart" else datePart
            Text(
                text = metaLine,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(Modifier.height(3.dp))

            // Episode title — bold for new, normal for played
            Text(
                text = episode.title,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontWeight = if (episode.playState == PlayState.NEW) FontWeight.Bold else FontWeight.Normal
                ),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            // Description (2 lines)
            if (episode.description.isNotBlank()) {
                Spacer(Modifier.height(3.dp))
                Text(
                    text = episode.description.trim(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Progress bar for in-progress episodes
            if (episode.playState == PlayState.IN_PROGRESS && episode.playedFraction > 0f) {
                Spacer(Modifier.height(5.dp))
                LinearProgressIndicator(
                    progress = { episode.playedFraction },
                    modifier = Modifier.fillMaxWidth(),
                    color = EpisodeInProgress,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            }

            Spacer(Modifier.height(6.dp))

            // Action row: ▶  duration  ·  [download icon]
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(4.dp))
                if (episode.duration > 0L) {
                    Text(
                        text = formatDurationBp(episode.duration),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }

                Spacer(Modifier.weight(1f))

                // Download state indicator
                when (episode.downloadState) {
                    DownloadStateEnum.DOWNLOADED -> Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = "Downloaded",
                        tint = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.8f),
                        modifier = Modifier.size(18.dp)
                    )
                    DownloadStateEnum.QUEUED,
                    DownloadStateEnum.DOWNLOADING -> Icon(
                        imageVector = Icons.Default.Download,
                        contentDescription = "Downloading",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    DownloadStateEnum.FAILED -> IconButton(
                        onClick = onDownloadClick, // tap to retry
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = "Download failed — tap to retry",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    else -> IconButton(
                        onClick = onDownloadClick,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = "Download episode",
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyWhatToPlay(onAddFeed: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "Nothing to play yet",
                style = MaterialTheme.typography.titleMedium
            )
            androidx.compose.material3.TextButton(onClick = onAddFeed) {
                Text("Subscribe to a podcast to get started")
            }
        }
    }
}

// Top-level singleton — SimpleDateFormat is not thread-safe but Compose renders on the main
// thread so a single shared instance is safe here and avoids 50+ allocations per frame.
private val DATE_FMT = SimpleDateFormat("d MMM", Locale.getDefault())

/** "19 APR" style — day + abbreviated month, uppercase */
private fun formatDateShort(epochMs: Long): String {
    if (epochMs == 0L) return ""
    return DATE_FMT.format(Date(epochMs)).uppercase(Locale.getDefault())
}

/** "65 min" for under an hour, "1h 5m" for longer */
private fun formatDurationBp(ms: Long): String {
    val hours = TimeUnit.MILLISECONDS.toHours(ms)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(ms) % 60
    return if (hours > 0) "${hours}h ${minutes}m" else "$minutes min"
}
