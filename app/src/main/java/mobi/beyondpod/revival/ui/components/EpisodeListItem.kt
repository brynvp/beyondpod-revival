package mobi.beyondpod.revival.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import java.util.Calendar
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
    onClick: () -> Unit,
    onDownloadClick: (() -> Unit)? = null,
    onDeleteClick: (() -> Unit)? = null,
    onShareClick: (() -> Unit)? = null,
    onFavouriteClick: (() -> Unit)? = null,
    feedImageUrl: String? = null,
    feedTitle: String? = null,
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
            .clickable(onClickLabel = "Play ${episode.title}", onClick = onClick)
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

        // Artwork thumbnail — episode art preferred, falls back to feed art
        val artUrl = episode.imageUrl ?: feedImageUrl
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            if (artUrl != null) {
                AsyncImage(
                    model = artUrl,
                    contentDescription = "${episode.title} artwork",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxHeight()
                )
            }
        }

        Spacer(Modifier.width(12.dp))

        // Text content
        Column(modifier = Modifier.weight(1f)) {

            // Podcast name — above the episode title
            if (!feedTitle.isNullOrBlank()) {
                Text(
                    text = feedTitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
            }

            Text(
                text = episode.title,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontWeight = if (episode.playState == PlayState.NEW) FontWeight.Bold else FontWeight.Normal
                ),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            // One-line description preview
            if (episode.description.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = episode.description.trim(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(Modifier.height(2.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = formatDate(episode.pubDate),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                // G5: Archived badge — episode no longer in RSS feed.
                // Shown in muted secondary colour so it's visible but not alarming.
                if (episode.isArchived) {
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        imageVector = Icons.Default.Archive,
                        contentDescription = "No longer in feed",
                        tint = MaterialTheme.colorScheme.secondary.copy(alpha = 0.6f),
                        modifier = Modifier.size(12.dp)
                    )
                    Spacer(Modifier.width(2.dp))
                    Text(
                        text = "Archived",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.6f)
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

            // Bottom action bar — download status inline below description
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = "Play",
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    modifier = Modifier
                        .size(18.dp)
                        .clickable(onClickLabel = "Play", onClick = onClick)
                )
                Spacer(Modifier.width(4.dp))
                if (episode.duration > 0) {
                    Text(
                        text = formatDuration(episode.duration),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }

                Spacer(Modifier.weight(1f))

                when (episode.downloadState) {
                    DownloadStateEnum.NOT_DOWNLOADED, DownloadStateEnum.FAILED -> {
                        if (onDownloadClick != null) {
                            Row(
                                modifier = Modifier.clickable(onClick = onDownloadClick),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Download,
                                    contentDescription = "Download",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(Modifier.width(3.dp))
                                Text(
                                    text = if (episode.downloadState == DownloadStateEnum.FAILED) "RETRY" else "DOWNLOAD",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                    DownloadStateEnum.DELETED -> {
                        if (onDownloadClick != null) {
                            Row(
                                modifier = Modifier.clickable(onClick = onDownloadClick),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = "Re-download",
                                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(Modifier.width(3.dp))
                                Text(
                                    text = "RE-DOWNLOAD",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                                )
                            }
                        }
                    }
                    DownloadStateEnum.DOWNLOADING, DownloadStateEnum.QUEUED -> {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    DownloadStateEnum.DOWNLOADED -> { /* nothing shown — already on disk */ }
                }

                Spacer(Modifier.weight(1f))
            }
        }

        Spacer(Modifier.width(4.dp))

        // Right-side action column: starred indicator + download state icon + three-dot menu
        Column(horizontalAlignment = Alignment.End) {

            // Favourite heart (shows only when starred)
            if (episode.isStarred) {
                Icon(
                    imageVector = Icons.Default.Favorite,
                    contentDescription = "Favourite",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(14.dp)
                )
            }

            // Download state indicator — compact icon only, no tap action here
            val isDark = isSystemInDarkTheme()
            when (episode.downloadState) {
                DownloadStateEnum.DOWNLOADED -> Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "Downloaded",
                    tint = if (isDark)
                        MaterialTheme.colorScheme.tertiary.copy(alpha = 0.85f)
                    else
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                    modifier = Modifier.size(16.dp)
                )
                DownloadStateEnum.DOWNLOADING,
                DownloadStateEnum.QUEUED -> CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary
                )
                DownloadStateEnum.FAILED -> Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = "Download failed",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(16.dp)
                )
                DownloadStateEnum.NOT_DOWNLOADED -> Icon(
                    imageVector = Icons.Default.Circle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f),
                    modifier = Modifier.size(8.dp)
                )
                DownloadStateEnum.DELETED -> { /* nothing — RE-DOWNLOAD in bottom bar is enough */ }
            }

            Spacer(Modifier.height(2.dp))

            // Three-dot overflow menu: Delete / Share / Favourite
            // Only shown when at least one action callback is provided.
            if (onDeleteClick != null || onShareClick != null || onFavouriteClick != null) {
                var menuExpanded by remember { mutableStateOf(false) }
                Box {
                    IconButton(
                        onClick = { menuExpanded = true },
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "More options",
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false }
                    ) {
                        // Delete — only relevant when a file is downloaded
                        if (onDeleteClick != null) {
                            DropdownMenuItem(
                                text = { Text("Delete download") },
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                },
                                enabled = episode.downloadState == DownloadStateEnum.DOWNLOADED,
                                onClick = {
                                    menuExpanded = false
                                    onDeleteClick()
                                }
                            )
                        }
                        // Favourite toggle
                        if (onFavouriteClick != null) {
                            DropdownMenuItem(
                                text = {
                                    Text(if (episode.isStarred) "Remove favourite" else "Add favourite")
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = if (episode.isStarred)
                                            Icons.Default.Favorite
                                        else
                                            Icons.Default.FavoriteBorder,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                },
                                onClick = {
                                    menuExpanded = false
                                    onFavouriteClick()
                                }
                            )
                        }
                        // Share — stub
                        if (onShareClick != null) {
                            DropdownMenuItem(
                                text = { Text("Share") },
                                leadingIcon = {
                                    Icon(Icons.Default.Share, contentDescription = null)
                                },
                                onClick = {
                                    menuExpanded = false
                                    onShareClick()
                                }
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.width(4.dp))
    }
}

private val EPISODE_DATE_FMT_SAME_YEAR = SimpleDateFormat("MMM d",      Locale.getDefault())
private val EPISODE_DATE_FMT_PAST_YEAR = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())

private fun formatDate(epochMs: Long): String {
    if (epochMs == 0L) return ""
    val date = Date(epochMs)
    val currentYear = Calendar.getInstance().get(Calendar.YEAR)
    val epCal = Calendar.getInstance().apply { time = date }
    return if (epCal.get(Calendar.YEAR) == currentYear) {
        EPISODE_DATE_FMT_SAME_YEAR.format(date)
    } else {
        EPISODE_DATE_FMT_PAST_YEAR.format(date)
    }
}

private fun formatDuration(ms: Long): String {
    val hours = TimeUnit.MILLISECONDS.toHours(ms)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(ms) % 60
    return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
}
