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
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import kotlinx.coroutines.launch
import mobi.beyondpod.revival.service.PlaybackService
import mobi.beyondpod.revival.ui.components.EpisodeListItem
import mobi.beyondpod.revival.ui.navigation.Screen

/**
 * My Episodes screen — the default landing screen.
 *
 * Displays the user's manually-curated episode queue. Reflects all 5 §7.5 behavioural
 * rules via [MyEpisodesViewModel]. My Episodes is NOT a regular SmartPlaylist view.
 *
 * Header actions:
 *   ▶ Play All — builds QueueSnapshotEntity from current order, starts PlaybackService
 *   ⇄ Shuffle — builds snapshot in random order (does NOT reorder My Episodes itself)
 *   ✕ Clear   — clears ManualPlaylistEpisodeCrossRef + deactivates active snapshot
 *
 * Swipe-to-dismiss: removes episode from My Episodes (sets isInMyEpisodes = false).
 * Drag-to-reorder: wired in Phase 6.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyEpisodesScreen(
    navController: NavController,
    viewModel: MyEpisodesViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    androidx.compose.material3.Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("My Episodes", fontWeight = FontWeight.SemiBold)
                        if (uiState is MyEpisodesUiState.Success) {
                            val count = (uiState as MyEpisodesUiState.Success).episodes.size
                            Text(
                                text = "$count episode${if (count == 1) "" else "s"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }
                    }
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
                    // Clear Queue
                    IconButton(onClick = {
                        scope.launch {
                            val result = snackbarHostState.showSnackbar(
                                message = "Clear My Episodes?",
                                actionLabel = "Clear"
                            )
                            if (result == SnackbarResult.ActionPerformed) viewModel.clearQueue()
                        }
                    }) {
                        Icon(Icons.Default.ClearAll, contentDescription = "Clear Queue")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding)) {
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
                        EmptyMyEpisodes(
                            onAddMore = { navController.navigate(Screen.AddFeed.route) }
                        )
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(
                                items = state.episodes,
                                key = { it.id }
                            ) { episode ->
                                val dismissState = rememberSwipeToDismissBoxState(
                                    confirmValueChange = { value ->
                                        if (value == SwipeToDismissBoxValue.EndToStart ||
                                            value == SwipeToDismissBoxValue.StartToEnd
                                        ) {
                                            viewModel.removeEpisode(episode.id)
                                            true
                                        } else false
                                    }
                                )

                                SwipeToDismissBox(
                                    state = dismissState,
                                    backgroundContent = { /* tinted background — Phase 6 */ },
                                    modifier = Modifier.animateItem()
                                ) {
                                    EpisodeListItem(
                                        episode = episode,
                                        onClick = {
                                            context.startService(
                                                PlaybackService.playEpisodeIntent(context, episode.id)
                                            )
                                        },
                                        onDownloadClick = { viewModel.downloadEpisode(episode.id) },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 12.dp)
                                    )
                                }
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

@Composable
private fun EmptyMyEpisodes(onAddMore: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "My Episodes is empty",
                style = MaterialTheme.typography.titleMedium
            )
            androidx.compose.material3.TextButton(onClick = onAddMore) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.width(4.dp))
                    Text("Add a podcast to get started")
                }
            }
        }
    }
}
