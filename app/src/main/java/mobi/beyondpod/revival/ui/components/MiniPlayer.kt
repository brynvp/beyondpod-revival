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
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
 * Shows: artwork (32dp), title, artist, play/pause, skip-forward.
 * Tap → navigates to full player (Phase 6).
 */
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

    AnimatedVisibility(
        visible = hasActiveEpisode,
        enter = slideInVertically { it },
        exit  = slideOutVertically { it }
    ) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .background(SurfaceVariantDark)
                .navigationBarsPadding()      // keeps content above gesture/button nav bar
                .clickable { onTap() }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Artwork (32dp)
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

            // Title + artist — use OnSurfaceDark: background is always SurfaceVariantDark (near-black)
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

            // Play / Pause — matches original BP mini-player (no skip button)
            IconButton(onClick = { viewModel.togglePlayPause() }) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    tint = OnSurfaceDark
                )
            }
        }
    }
}

