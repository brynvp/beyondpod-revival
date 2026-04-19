package mobi.beyondpod.revival.ui.screens.myepisodes

import android.content.Intent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import mobi.beyondpod.revival.service.PlaybackService
import mobi.beyondpod.revival.ui.components.EpisodeListItem
import mobi.beyondpod.revival.ui.navigation.Screen

/**
 * "What to Play" landing screen.
 *
 * Shows three auto-populated sections:
 *   Latest Downloads  — 5 most recently downloaded episodes (ready to play immediately)
 *   Recently Played   — 5 episodes in IN_PROGRESS or PLAYED state
 *   Starred           — up to 50 starred/favourite episodes
 *
 * No manual curation required. Content appears automatically once the user
 * has subscriptions and has downloaded or played episodes.
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
                title = {
                    Text("What to Play", fontWeight = FontWeight.SemiBold)
                },
                actions = {
                    // Play All
                    FilledTonalIconButton(onClick = {
                        viewModel.buildQueueAndPlay()
                        context.startService(Intent(context, PlaybackService::class.java))
                    }) {
                        Icon(Icons.Default.PlayArrow, contentDescription = "Play All")
                    }
                    // Shuffle
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
                    if (state.sections.isEmpty()) {
                        EmptyWhatToPlay(
                            onAddFeed = { navController.navigate(Screen.AddFeed.route) }
                        )
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            state.sections.forEach { section ->
                                // Section header
                                item(key = "header_${section.title}") {
                                    SectionHeader(title = section.title)
                                }

                                // Episode rows
                                items(
                                    items = section.episodes,
                                    key = { "${section.title}_${it.id}" }
                                ) { episode ->
                                    EpisodeListItem(
                                        episode = episode,
                                        onClick = {
                                            context.startService(
                                                PlaybackService.playEpisodeIntent(context, episode.id)
                                            )
                                            navController.navigate(Screen.FullPlayer.route)
                                        },
                                        onDownloadClick = { viewModel.downloadEpisode(episode.id) },
                                        feedImageUrl = feedImageUrls[episode.feedId],
                                        feedTitle = feedTitles[episode.feedId],
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
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title.uppercase(),
        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp)
    )
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.width(4.dp))
                    Text("Subscribe to a podcast to get started")
                }
            }
        }
    }
}
