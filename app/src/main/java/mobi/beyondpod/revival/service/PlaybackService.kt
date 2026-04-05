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
import mobi.beyondpod.revival.data.repository.EpisodeRepository
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
                    episodeRepository.savePlayPosition(episodeId, player.currentPosition)
                }
            }
        }
    }

    private fun stopPositionSaving() {
        positionSaveJob?.cancel()
        positionSaveJob = null
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
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // Episode id is stored as the mediaId string when the MediaItem was built
            currentEpisodeId = mediaItem?.mediaId?.toLongOrNull() ?: -1L
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
