package mobi.beyondpod.revival.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import mobi.beyondpod.revival.data.local.entity.DownloadStateEnum
import mobi.beyondpod.revival.data.local.entity.EpisodeEntity
import mobi.beyondpod.revival.data.local.entity.PlayState
import mobi.beyondpod.revival.ui.theme.EpisodeInProgress
import mobi.beyondpod.revival.ui.theme.EpisodeNew
import mobi.beyondpod.revival.ui.theme.EpisodePlayed
import mobi.beyondpod.revival.ui.theme.EpisodeStarred
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Episode row used in My Episodes and all episode list screens.
 *
 * Visual states per §12:
 *   NEW        → Blue (4dp) left border, bold title
 *   IN_PROGRESS → Orange left border + progress bar below title
 *   PLAYED     → 40% alpha (greyed out), no border
 *   STARRED    → Gold star icon
 *   DOWNLOADED → Green checkmark icon
 */
@Composable
fun EpisodeListItem(
    episode: EpisodeEntity,
    modifier: Modifier = Modifier
) {
    val borderColor = when (episode.playState) {
        PlayState.NEW         -> EpisodeNew
        PlayState.IN_PROGRESS -> EpisodeInProgress
        PlayState.PLAYED,
        PlayState.SKIPPED     -> null
    }
    val alpha = if (episode.playState == PlayState.PLAYED) 0.4f else 1f

    Row(
        modifier = modifier
            .fillMaxWidth()
            .alpha(alpha)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left state border
        if (borderColor != null) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(64.dp)
                    .background(borderColor, RoundedCornerShape(topEnd = 2.dp, bottomEnd = 2.dp))
            )
        } else {
            Spacer(Modifier.width(4.dp))
        }

        Spacer(Modifier.width(8.dp))

        // Artwork thumbnail
        val artUrl = episode.imageUrl
        Box(
            modifier = Modifier
                .size(56.dp)
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

        // Text content
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = episode.title,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontWeight = if (episode.playState == PlayState.NEW) FontWeight.Bold else FontWeight.Normal
                ),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(Modifier.height(2.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = formatDate(episode.pubDate),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                if (episode.duration > 0) {
                    Text(
                        text = " · ${formatDuration(episode.duration)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            }

            // Progress bar for IN_PROGRESS episodes
            if (episode.playState == PlayState.IN_PROGRESS && episode.playedFraction > 0f) {
                Spacer(Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { episode.playedFraction },
                    modifier = Modifier.fillMaxWidth(),
                    color = EpisodeInProgress,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            }
        }

        Spacer(Modifier.width(8.dp))

        // State icons (right side)
        Column(horizontalAlignment = Alignment.End) {
            if (episode.isStarred) {
                Icon(
                    imageVector = Icons.Default.Star,
                    contentDescription = "Starred",
                    tint = EpisodeStarred,
                    modifier = Modifier.size(16.dp)
                )
            }
            when (episode.downloadState) {
                DownloadStateEnum.DOWNLOADED -> Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "Downloaded",
                    tint = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.8f),
                    modifier = Modifier.size(16.dp)
                )
                DownloadStateEnum.QUEUED,
                DownloadStateEnum.DOWNLOADING -> Icon(
                    imageVector = Icons.Default.Download,
                    contentDescription = "Downloading",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
                else -> {}
            }
        }

        Spacer(Modifier.width(8.dp))
    }
}

private fun formatDate(epochMs: Long): String {
    if (epochMs == 0L) return ""
    return SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(epochMs))
}

private fun formatDuration(ms: Long): String {
    val hours = TimeUnit.MILLISECONDS.toHours(ms)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(ms) % 60
    return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
}
