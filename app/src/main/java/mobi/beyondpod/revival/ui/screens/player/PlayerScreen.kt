package mobi.beyondpod.revival.ui.screens.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import mobi.beyondpod.revival.ui.navigation.Screen
import mobi.beyondpod.revival.ui.player.PlaybackViewModel
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    navController: NavController,
    viewModel: PlaybackViewModel
) {
    val isPlaying        by viewModel.isPlaying.collectAsState()
    val title            by viewModel.currentTitle.collectAsState()
    val artist           by viewModel.currentArtist.collectAsState()
    val artworkUri       by viewModel.artworkUri.collectAsState()
    val currentPosition  by viewModel.currentPosition.collectAsState()
    val duration         by viewModel.duration.collectAsState()
    val speed            by viewModel.playbackSpeed.collectAsState()
    val sleepTimerMs     by viewModel.sleepTimerRemainingMs.collectAsState()
    val description      by viewModel.episodeDescription.collectAsState()
    val currentEpisodeId by viewModel.currentEpisodeId.collectAsState()

    var showSleepDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Now Playing") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // Sleep timer button — shows remaining time if active
                    if (sleepTimerMs > 0L) {
                        TextButton(onClick = { showSleepDialog = true }) {
                            Icon(Icons.Default.Bedtime, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text(formatDuration(sleepTimerMs))
                        }
                    } else {
                        IconButton(
                            onClick = { showSleepDialog = true },
                            modifier = Modifier.semantics { contentDescription = "Set sleep timer" }
                        ) {
                            Icon(Icons.Default.Bedtime, contentDescription = "Sleep timer")
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(16.dp))

            // ── Artwork ───────────────────────────────────────────────────────
            if (artworkUri != null) {
                AsyncImage(
                    model = artworkUri,
                    contentDescription = "Episode artwork",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(280.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(280.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "♪",
                        style = MaterialTheme.typography.displayLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            // ── Title + Artist ────────────────────────────────────────────────
            Text(
                text = title ?: "No episode playing",
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth()
            )
            if (!artist.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = artist!!,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(Modifier.height(24.dp))

            // ── Progress scrubber ─────────────────────────────────────────────
            val progress = if (duration > 0L) currentPosition.toFloat() / duration.toFloat() else 0f
            Slider(
                value = progress,
                onValueChange = { fraction ->
                    if (duration > 0L) viewModel.seek((fraction * duration).toLong())
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "Seek bar" }
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = formatDuration(currentPosition),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                Text(
                    text = if (duration > 0L) formatDuration(duration) else "--:--",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }

            Spacer(Modifier.height(16.dp))

            // ── Playback controls ─────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Rewind
                IconButton(
                    onClick = { viewModel.rewind() },
                    modifier = Modifier
                        .size(56.dp)
                        .semantics { contentDescription = "Rewind" }
                ) {
                    Icon(
                        imageVector = Icons.Default.Replay10,
                        contentDescription = null,
                        modifier = Modifier.size(36.dp)
                    )
                }

                Spacer(Modifier.width(16.dp))

                // Play / Pause (large)
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                        .clickable { viewModel.togglePlayPause() }
                        .semantics { contentDescription = if (isPlaying) "Pause" else "Play" },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(40.dp)
                    )
                }

                Spacer(Modifier.width(16.dp))

                // Fast-forward
                IconButton(
                    onClick = { viewModel.fastForward() },
                    modifier = Modifier
                        .size(56.dp)
                        .semantics { contentDescription = "Fast forward" }
                ) {
                    Icon(
                        imageVector = Icons.Default.Forward30,
                        contentDescription = null,
                        modifier = Modifier.size(36.dp)
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            // ── Speed selector ────────────────────────────────────────────────
            SpeedSelector(
                currentSpeed = speed,
                onSpeedSelected = { viewModel.setPlaybackSpeed(it) }
            )

            Spacer(Modifier.height(24.dp))

            // ── Episode description (tap → full notes) ────────────────────────
            if (description.isNotBlank() && currentEpisodeId != null && currentEpisodeId!! > 0) {
                Text(
                    text = "Episode Notes",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            navController.navigate(
                                Screen.EpisodeNotes.createRoute(currentEpisodeId!!)
                            )
                        }
                        .semantics { contentDescription = "Episode notes — tap to read full description" }
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Read more…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clickable {
                            navController.navigate(
                                Screen.EpisodeNotes.createRoute(currentEpisodeId!!)
                            )
                        }
                )
            }

            Spacer(Modifier.height(32.dp))
        }
    }

    // ── Sleep timer dialog ────────────────────────────────────────────────────
    if (showSleepDialog) {
        SleepTimerDialog(
            isActive = sleepTimerMs > 0L,
            onDismiss = { showSleepDialog = false },
            onSelect = { durationMs ->
                viewModel.setSleepTimer(durationMs)
                showSleepDialog = false
            },
            onCancel = {
                viewModel.cancelSleepTimer()
                showSleepDialog = false
            }
        )
    }
}

// ── Speed selector chips ──────────────────────────────────────────────────────

private val SPEED_OPTIONS = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)

@Composable
private fun SpeedSelector(
    currentSpeed: Float,
    onSpeedSelected: (Float) -> Unit
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SPEED_OPTIONS.forEach { s ->
            FilterChip(
                selected = currentSpeed == s,
                onClick = { onSpeedSelected(s) },
                label = {
                    Text(
                        text = if (s == 1.0f) "1×" else "${s}×",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            )
        }
    }
}

// ── Sleep timer dialog ────────────────────────────────────────────────────────

private val SLEEP_OPTIONS = listOf(
    "15 min"  to  15 * 60_000L,
    "30 min"  to  30 * 60_000L,
    "45 min"  to  45 * 60_000L,
    "1 hour"  to  60 * 60_000L,
    "2 hours" to 120 * 60_000L
)

@Composable
private fun SleepTimerDialog(
    isActive: Boolean,
    onDismiss: () -> Unit,
    onSelect: (Long) -> Unit,
    onCancel: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sleep Timer") },
        text = {
            Column {
                SLEEP_OPTIONS.forEach { (label, ms) ->
                    TextButton(
                        onClick = { onSelect(ms) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(label)
                    }
                }
                if (isActive) {
                    TextButton(
                        onClick = onCancel,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Cancel timer", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

// ── Helpers ───────────────────────────────────────────────────────────────────

private fun formatDuration(ms: Long): String {
    val totalSec = ms / 1000L
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
