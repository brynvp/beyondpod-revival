package mobi.beyondpod.revival.service

import android.app.PendingIntent
import android.content.Intent
import android.media.audiofx.LoudnessEnhancer
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import mobi.beyondpod.revival.data.repository.EpisodeRepository
import mobi.beyondpod.revival.widget.PlaybackWidgetState
import javax.inject.Inject

/**
 * Background playback service. Extends [MediaSessionService] to provide:
 * - Background playback via foreground service
 * - Lock screen + notification controls (Media3 DefaultMediaNotificationProvider)
 * - Android Auto / Bluetooth AVRCP (via MediaSession)
 * - Audio focus (ExoPlayer built-in, setHandleAudioBecomingNoisy)
 * - LoudnessEnhancer volume boost (NEVER player.volume > 1.0f — §7.6 rule)
 * - Sleep timer (coroutine-based countdown)
 * - Position saved every 5 s + on every pause (§9 spec)
 *
 * Episode episodes are identified by their DB id stored as [MediaItem.mediaId].
 */
@AndroidEntryPoint
class PlaybackService : MediaSessionService() {

    @Inject lateinit var episodeRepository: EpisodeRepository

    private lateinit var player: ExoPlayer
    private lateinit var mediaSession: MediaSession
    private var loudnessEnhancer: LoudnessEnhancer? = null

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var positionSaveJob: Job? = null
    private var sleepTimerJob: Job? = null

    /** DB id of the episode currently loaded in the player, or -1 if none. */
    private var currentEpisodeId: Long = -1L

    companion object {
        const val ACTION_SET_SLEEP_TIMER    = "mobi.beyondpod.revival.SET_SLEEP_TIMER"
        const val ACTION_CANCEL_SLEEP_TIMER = "mobi.beyondpod.revival.CANCEL_SLEEP_TIMER"
        const val EXTRA_SLEEP_DURATION_MS   = "sleep_duration_ms"
        /** Use this key when building MediaItems: set mediaId = episodeId.toString() */
        const val MEDIA_ID_KEY              = "episode_id"

        // Widget control actions (received from PlayerWidgetProvider button taps)
        const val ACTION_TOGGLE_PLAYBACK = "mobi.beyondpod.revival.TOGGLE_PLAYBACK"
        const val ACTION_SKIP_BACK       = "mobi.beyondpod.revival.SKIP_BACK"
        const val ACTION_SKIP_FORWARD    = "mobi.beyondpod.revival.SKIP_FORWARD"

        /** Load and play a single episode. Required extra: [EXTRA_EPISODE_ID]. */
        const val ACTION_PLAY_EPISODE  = "mobi.beyondpod.revival.PLAY_EPISODE"
        const val EXTRA_EPISODE_ID     = "episode_id"

        /** Convenience — build and fire the play-episode intent. */
        fun playEpisodeIntent(context: android.content.Context, episodeId: Long): Intent =
            Intent(context, PlaybackService::class.java).apply {
                action = ACTION_PLAY_EPISODE
                putExtra(EXTRA_EPISODE_ID, episodeId)
            }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()

        player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                /* handleAudioFocus= */ true
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
            .also { it.addListener(playerListener) }

        val sessionActivity = packageManager
            .getLaunchIntentForPackage(packageName)
            ?.let { PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE) }

        mediaSession = MediaSession.Builder(this, player)
            .apply { sessionActivity?.let { setSessionActivity(it) } }
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession = mediaSession

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_SET_SLEEP_TIMER -> {
                val ms = intent.getLongExtra(EXTRA_SLEEP_DURATION_MS, -1L)
                if (ms > 0) startSleepTimer(ms)
            }
            ACTION_CANCEL_SLEEP_TIMER -> cancelSleepTimer()
            ACTION_TOGGLE_PLAYBACK -> if (player.isPlaying) player.pause() else player.play()
            ACTION_SKIP_BACK       -> player.seekTo((player.currentPosition - 10_000L).coerceAtLeast(0))
            ACTION_SKIP_FORWARD    -> player.seekTo(player.currentPosition + 30_000L)
            ACTION_PLAY_EPISODE    -> {
                val episodeId = intent.getLongExtra(EXTRA_EPISODE_ID, -1L)
                if (episodeId >= 0) loadAndPlay(episodeId)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        serviceScope.cancel()
        loudnessEnhancer?.release()
        loudnessEnhancer = null
        mediaSession.release()
        player.removeListener(playerListener)
        player.release()
        super.onDestroy()
    }

    // ── Single episode playback ───────────────────────────────────────────────

    /**
     * Look up [episodeId] in the DB, resolve the playback URI (local file preferred),
     * seek to the saved position, and start playing. Must be called from [serviceScope]
     * (Dispatchers.Main) so player operations are on the correct thread.
     */
    private fun loadAndPlay(episodeId: Long) {
        serviceScope.launch {
            val episode = episodeRepository.getEpisodeById(episodeId) ?: return@launch
            // Prefer local file; fall back to streaming URL
            val uri = episode.localFilePath
                ?.takeIf { File(it).exists() }
                ?: episode.url
            val mediaItem = MediaItem.Builder()
                .setMediaId(episodeId.toString())
                .setUri(uri)
                .build()
            player.setMediaItem(mediaItem)
            if (episode.playPosition > 0) player.seekTo(episode.playPosition)
            player.prepare()
            player.play()
        }
    }

    // ── Volume boost (LoudnessEnhancer — §7.6) ────────────────────────────────

    /**
     * Apply software gain beyond 0 dB via [LoudnessEnhancer].
     * NEVER set player.volume > 1.0f.
     *
     * @param boostLevel 1 = no boost (0 dB), 2–10 = ~1 dB per step (max 10 dB)
     */
    fun applyVolumeBoost(boostLevel: Int) {
        if (player.audioSessionId == C.AUDIO_SESSION_ID_UNSET) return
        val enhancer = loudnessEnhancer
            ?: LoudnessEnhancer(player.audioSessionId).also { loudnessEnhancer = it }
        val gainMillibels = if (boostLevel <= 1) 0 else ((boostLevel - 1) * 111).coerceAtMost(1000)
        enhancer.setTargetGain(gainMillibels)
        enhancer.enabled = gainMillibels > 0
    }

    // ── Sleep timer ───────────────────────────────────────────────────────────

    private fun startSleepTimer(durationMs: Long) {
        sleepTimerJob?.cancel()
        sleepTimerJob = serviceScope.launch {
            delay(durationMs)
            player.pause()
        }
    }

    private fun cancelSleepTimer() {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
    }

    // ── Position saving ───────────────────────────────────────────────────────

    private fun startPositionSaving(episodeId: Long) {
        positionSaveJob?.cancel()
        positionSaveJob = serviceScope.launch {
            while (isActive) {
                delay(5_000L)
                if (player.isPlaying) {
                    // Guard: stop the timer if the episode was deleted while playing
                    if (episodeRepository.getEpisodeById(episodeId) == null) {
                        stopPositionSaving()
                        return@launch
                    }
                    episodeRepository.savePlayPosition(episodeId, player.currentPosition)
                }
            }
        }
    }

    private fun stopPositionSaving() {
        positionSaveJob?.cancel()
        positionSaveJob = null
    }

    // ── Widget state bridge ───────────────────────────────────────────────────

    /**
     * Write current playback state to SharedPreferences and broadcast a widget update.
     * Called on every play/pause toggle and on episode transitions.
     */
    private fun notifyWidget(isPlaying: Boolean) {
        serviceScope.launch(Dispatchers.IO) {
            val episode = if (currentEpisodeId > 0) {
                episodeRepository.getEpisodeById(currentEpisodeId)
            } else null
            PlaybackWidgetState.write(
                context      = this@PlaybackService,
                episodeId    = currentEpisodeId,
                episodeTitle = episode?.title ?: "",
                feedTitle    = "",  // feed title resolved lazily in the widget
                artUrl       = episode?.imageUrl,
                isPlaying    = isPlaying
            )
            sendBroadcast(Intent(PlaybackWidgetState.ACTION_WIDGET_UPDATE).apply {
                setPackage(packageName)
            })
        }
    }

    // ── Player event listener ─────────────────────────────────────────────────

    private val playerListener = object : Player.Listener {

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                if (currentEpisodeId > 0) startPositionSaving(currentEpisodeId)
                // LoudnessEnhancer must be re-created when audioSessionId changes (after prepare)
                applyVolumeBoost(1)
            } else {
                stopPositionSaving()
                // Save position immediately on pause
                if (currentEpisodeId > 0) {
                    val position = player.currentPosition
                    serviceScope.launch {
                        episodeRepository.savePlayPosition(currentEpisodeId, position)
                    }
                }
            }
            notifyWidget(isPlaying)
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            currentEpisodeId = mediaItem?.mediaId?.toLongOrNull() ?: -1L
            notifyWidget(player.isPlaying)
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            // Mark episode played when it finishes naturally
            if (playbackState == Player.STATE_ENDED && currentEpisodeId > 0) {
                val episodeId = currentEpisodeId
                serviceScope.launch {
                    episodeRepository.markPlayed(episodeId)
                }
            }
        }
    }
}
