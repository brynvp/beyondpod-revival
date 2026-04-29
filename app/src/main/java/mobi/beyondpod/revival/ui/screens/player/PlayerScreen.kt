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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import android.content.ContextWrapper
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.mediarouter.app.MediaRouteButton
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.google.android.gms.cast.framework.CastButtonFactory
import mobi.beyondpod.revival.R
import mobi.beyondpod.revival.ui.navigation.Screen
import mobi.beyondpod.revival.ui.player.PlaybackViewModel
import java.util.concurrent.TimeUnit

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
    val sleepTimerMs     by viewModel.sleepTimerRemainingMs.collectAsState()
    val description      by viewModel.episodeDescription.collectAsState()
    val currentEpisodeId by viewModel.currentEpisodeId.collectAsState()
    val pubDate          by viewModel.episodePubDate.collectAsState()

    var showSleepDialog  by remember { mutableStateOf(false) }

    // Scrubber drag state — seek fires only on finger-up (onValueChangeFinished), not every frame
    var isScrubbing      by remember { mutableStateOf(false) }
    var scrubPosition    by remember { mutableFloatStateOf(0f) }
    val displayProgress  = if (isScrubbing) scrubPosition
                           else if (duration > 0L) currentPosition.toFloat() / duration.toFloat()
                           else 0f

    // Context for MediaRouteButton — must apply Theme_BeyondPod with force=true.
    //
    // Root cause of the translucent-background crash:
    //   Compose wraps its composition context with android:colorBackground explicitly set to 0
    //   (transparent). ContextThemeWrapper copies the base theme first, then applies our style
    //   with force=false on first init (see ContextThemeWrapper.initializeTheme). Because the
    //   existing 0 was set explicitly, force=false leaves it intact and MediaRouterThemeHelper
    //   sees #0 → crash.
    //
    // Fix: build a fresh Resources.Theme that (a) copies the base context's attrs so the button
    //   looks correct, then (b) applies Theme_BeyondPod with force=true so our solid
    //   colorBackground definitively overrides the 0.
    val baseCtx = LocalContext.current
    val castCtx = remember(baseCtx) {
        object : ContextWrapper(baseCtx) {
            private val _theme = baseCtx.resources.newTheme().also { t ->
                t.setTo(baseCtx.theme)                          // inherit base colours/styles
                t.applyStyle(R.style.Theme_BeyondPod, true)     // force=true wins over the 0
            }
            override fun getTheme() = _theme
        }
    }

    // No Scaffold here — AppShell hides its own topBar/bottomBar on the FullPlayer route
    // so this Column owns the entire screen real-estate.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {

        // ── Custom header row ─────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Back — pops FullPlayer; playback continues, MiniPlayer appears at bottom
            IconButton(onClick = { navController.popBackStack() }) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Minimise player"
                )
            }
            Text(
                text = "Now Playing",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f)
            )
            // Chromecast button — Cast SDK auto-shows/hides based on available devices.
            // MediaRouteButton reads colorBackground from its context's theme to determine
            // icon contrast. Compose's AndroidView context has no window background (#0),
            // so we must wrap with the app theme before constructing the button.
            AndroidView(
                factory = { _ ->
                    // castCtx is pre-built above with force=true theme — see comment there.
                    MediaRouteButton(castCtx).also { button ->
                        CastButtonFactory.setUpMediaRouteButton(castCtx, button)
                    }
                },
                modifier = Modifier.size(48.dp)
            )

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

        Spacer(Modifier.height(12.dp))

        // ── Artwork ───────────────────────────────────────────────────────────
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

        // ── Title + Podcast name + Date ───────────────────────────────────────
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
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
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (pubDate != null && pubDate!! > 0L) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = formatDate(pubDate!!),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        Spacer(Modifier.height(24.dp))

        // ── Progress scrubber — seeks on finger-up only to avoid jank ─────────
        Column(modifier = Modifier.padding(horizontal = 24.dp)) {
            Slider(
                value = displayProgress,
                onValueChange = { fraction ->
                    isScrubbing = true
                    scrubPosition = fraction
                },
                onValueChangeFinished = {
                    if (duration > 0L) viewModel.seek((scrubPosition * duration).toLong())
                    isScrubbing = false
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "Seek bar" }
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                val displayMs = if (isScrubbing) (scrubPosition * duration).toLong() else currentPosition
                Text(
                    text = formatDuration(displayMs),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                Text(
                    text = if (duration > 0L) formatDuration(duration) else "--:--",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // ── Playback controls ─────────────────────────────────────────────────
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

        Spacer(Modifier.height(24.dp))

        // ── Episode description (tap → full notes) ────────────────────────────
        if (description.isNotBlank() && currentEpisodeId != null && currentEpisodeId!! > 0) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
            ) {
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
                    modifier = Modifier.clickable {
                        navController.navigate(
                            Screen.EpisodeNotes.createRoute(currentEpisodeId!!)
                        )
                    }
                )
            }
        }

        Spacer(Modifier.height(32.dp))
    }

    // ── Sleep timer dialog (outside Column so it overlays correctly) ──────────
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

private fun formatDate(ms: Long): String {
    val sdf = java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.getDefault())
    return sdf.format(java.util.Date(ms))
}
