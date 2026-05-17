package mobi.beyondpod.revival.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil3.compose.AsyncImage
import mobi.beyondpod.revival.ui.player.PlaybackViewModel
import mobi.beyondpod.revival.ui.theme.OnSurfaceDark
import mobi.beyondpod.revival.ui.theme.SurfaceVariantDark

/**
 * Persistent mini-player bar docked above the navigation bar (§7.6).
 *
 * Visible only when an episode is loaded in [PlaybackService] (even if paused).
 * Shows: artwork (40dp), title, artist, play/pause, skip-forward.
 * Tap → navigates to full player. Swipe left or right → stops playback and dismisses.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MiniPlayer(
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PlaybackViewModel = hiltViewModel()
) {
    val hasActiveEpisode by viewModel.hasActiveEpisode.collectAsState()
    val isPlaying        by viewModel.isPlaying.collectAsState()
    val title            by viewModel.currentTitle.collectAsState()
    val artist           by viewModel.currentArtist.collectAsState()
    val artworkUri       by viewModel.artworkUri.collectAsState()

    var dismissed by remember { mutableStateOf(false) }
    val dismissState = rememberSwipeToDismissBoxState()

    LaunchedEffect(hasActiveEpisode) {
        if (hasActiveEpisode) {
            dismissed = false        // reset for next episode
            dismissState.reset()
        }
    }
    LaunchedEffect(dismissState.currentValue) {
        if (dismissState.currentValue != SwipeToDismissBoxValue.Settled) {
            dismissed = true         // collapse space immediately — don't wait for service
            viewModel.stopPlayback()
        }
    }

    AnimatedVisibility(
        visible = hasActiveEpisode && !dismissed,
        enter = slideInVertically { it },
        exit  = slideOutVertically { it }
    ) {
        SwipeToDismissBox(
            state = dismissState,
            // Allow swipe in both directions — either dismisses.
            enableDismissFromStartToEnd = true,
            enableDismissFromEndToStart = true,
            // No background decoration needed — the bar just slides away.
            backgroundContent = {}
        ) {
            Row(
                modifier = modifier
                    .fillMaxWidth()
                    .background(SurfaceVariantDark)
                    .navigationBarsPadding()
                    .clickable { onTap() }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Artwork (40dp)
                if (artworkUri != null) {
                    AsyncImage(
                        model = artworkUri,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                    )
                }

                Spacer(Modifier.width(12.dp))

                // Title + artist
                Column(modifier = Modifier.weight(1f), content = {
                    Text(
                        text = title ?: "",
                        style = MaterialTheme.typography.bodyMedium,
                        color = OnSurfaceDark,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (!artist.isNullOrBlank()) {
                        Text(
                            text = artist!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = OnSurfaceDark.copy(alpha = 0.65f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                })

                // Play / Pause
                IconButton(onClick = { viewModel.togglePlayPause() }) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Play",
                        tint = OnSurfaceDark
                    )
                }

                // Skip forward (30s)
                IconButton(onClick = { viewModel.fastForward() }) {
                    Icon(
                        imageVector = Icons.Default.Forward30,
                        contentDescription = "Fast forward",
                        tint = OnSurfaceDark
                    )
                }
            }
        }
    }
}

