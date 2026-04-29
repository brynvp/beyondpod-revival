package mobi.beyondpod.revival.service

import android.app.PendingIntent
import android.content.Intent
import android.media.audiofx.LoudnessEnhancer
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.media3.cast.CastPlayer
import androidx.media3.cast.SessionAvailabilityListener
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.android.gms.cast.framework.CastContext
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import mobi.beyondpod.revival.data.repository.EpisodeRepository
import mobi.beyondpod.revival.data.repository.FeedRepository
import mobi.beyondpod.revival.data.settings.AppSettings
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
 * - Chromecast via [CastPlayer] — drops in as a [Player] replacement when a Cast session starts.
 *   Local file URIs cannot be cast; [switchToCast] refuses if the current source is a local file.
 *
 * Episode episodes are identified by their DB id stored as [MediaItem.mediaId].
 */
@AndroidEntryPoint
class PlaybackService : MediaSessionService() {

    @Inject lateinit var episodeRepository: EpisodeRepository
    @Inject lateinit var feedRepository: FeedRepository
    @Inject lateinit var dataStore: DataStore<Preferences>

    private lateinit var exoPlayer: ExoPlayer
    private var castPlayer: CastPlayer? = null
    private lateinit var mediaSession: MediaSession
    private var loudnessEnhancer: LoudnessEnhancer? = null

    /** True while a Cast session is active and CastPlayer is the active player. */
    private var isCasting = false

    /** The [Player] currently backing [mediaSession] — either [exoPlayer] or [castPlayer]. */
    private val activePlayer: Player get() = if (isCasting) castPlayer!! else exoPlayer

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var positionSaveJob: Job? = null
    private var sleepTimerJob: Job? = null

    /** DB id of the episode currently loaded in the player, or -1 if none. */
    private var currentEpisodeId: Long = -1L

    /**
     * The stream URL of the currently loaded episode (never a local file path).
     * Kept so [switchToCast] can hand off to CastPlayer without re-querying the DB.
     */
    private var currentStreamUrl: String? = null

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

        // Settings-aware rewind / fast-forward (reads SKIP_BACK_SECONDS / SKIP_FORWARD_SECONDS)
        const val ACTION_REWIND          = "mobi.beyondpod.revival.REWIND"
        const val ACTION_FAST_FORWARD    = "mobi.beyondpod.revival.FAST_FORWARD"

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

        exoPlayer = ExoPlayer.Builder(this)
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

        // CastContext may be unavailable on devices without Play Services (e.g. emulators without GMS).
        // Wrap in try/catch so the app continues to work in that environment.
        try {
            val castContext = CastContext.getSharedInstance(this)
            castPlayer = CastPlayer(castContext).also { cp ->
                cp.addListener(playerListener)
                cp.setSessionAvailabilityListener(castSessionListener)
            }
        } catch (e: Exception) {
            // Play Services not available — Cast silently disabled
            castPlayer = null
        }

        val sessionActivity = packageManager
            .getLaunchIntentForPackage(packageName)
            ?.let { PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE) }

        mediaSession = MediaSession.Builder(this, exoPlayer)
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
            ACTION_TOGGLE_PLAYBACK -> if (activePlayer.isPlaying) activePlayer.pause() else activePlayer.play()
            ACTION_SKIP_BACK       -> activePlayer.seekTo((activePlayer.currentPosition - 10_000L).coerceAtLeast(0))
            ACTION_SKIP_FORWARD    -> activePlayer.seekTo(activePlayer.currentPosition + 30_000L)
            ACTION_REWIND          -> serviceScope.launch {
                val skipSec = dataStore.data.first()[AppSettings.SKIP_BACK_SECONDS] ?: 10
                activePlayer.seekTo((activePlayer.currentPosition - skipSec * 1000L).coerceAtLeast(0))
            }
            ACTION_FAST_FORWARD    -> serviceScope.launch {
                val skipSec = dataStore.data.first()[AppSettings.SKIP_FORWARD_SECONDS] ?: 30
                activePlayer.seekTo(activePlayer.currentPosition + skipSec * 1000L)
            }
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
        exoPlayer.removeListener(playerListener)
        exoPlayer.release()
        castPlayer?.setSessionAvailabilityListener(null)
        castPlayer?.removeListener(playerListener)
        castPlayer?.release()
        super.onDestroy()
    }

    // ── Single episode playback ───────────────────────────────────────────────

    /**
     * Look up [episodeId] in the DB, resolve the playback URI, and start playing.
     *
     * If a Cast session is active **and** the episode has a stream URL (not local-only),
     * playback is routed to [castPlayer]; otherwise [exoPlayer] is used. Local files cannot
     * be cast — Chromecast requires a publicly reachable HTTP URL.
     */
    private fun loadAndPlay(episodeId: Long) {
        serviceScope.launch {
            val episode = episodeRepository.getEpisodeById(episodeId) ?: return@launch
            val feed    = feedRepository.getFeedById(episode.feedId)

            currentEpisodeId  = episodeId
            currentStreamUrl  = episode.url  // always the remote stream URL, never local path

            // Artwork: episode image preferred, fall back to feed image
            val artworkUriStr = episode.imageUrl ?: feed?.imageUrl

            val metadata = MediaMetadata.Builder()
                .setTitle(episode.title)
                .setArtist(feed?.title)
                .setArtworkUri(artworkUriStr?.let { Uri.parse(it) })
                .build()

            if (isCasting && currentStreamUrl != null) {
                // Cast path — stream URL only, local files cannot be served to Chromecast
                val castItem = MediaItem.Builder()
                    .setMediaId(episodeId.toString())
                    .setUri(currentStreamUrl!!)
                    .setMimeType(MimeTypes.AUDIO_MPEG)
                    .setMediaMetadata(metadata)
                    .build()
                castPlayer!!.setMediaItem(castItem)
                if (episode.playPosition > 0) castPlayer!!.seekTo(episode.playPosition)
                castPlayer!!.prepare()
                castPlayer!!.play()
            } else {
                // Local path — prefer downloaded file, fall back to stream
                val uri = episode.localFilePath
                    ?.takeIf { File(it).exists() }
                    ?: episode.url

                val exoItem = MediaItem.Builder()
                    .setMediaId(episodeId.toString())
                    .setUri(uri)
                    .setMediaMetadata(metadata)
                    .build()
                exoPlayer.setMediaItem(exoItem)
                if (episode.playPosition > 0) exoPlayer.seekTo(episode.playPosition)
                exoPlayer.prepare()
                exoPlayer.play()
            }
        }
    }

    // ── Cast session management ───────────────────────────────────────────────

    /**
     * Called by [castSessionListener] when a Cast session becomes available.
     * Saves the current ExoPlayer position, pauses it, then hands the stream off to CastPlayer.
     * If the current source is a local file (no stream URL available), casting is skipped.
     */
    private fun switchToCast() {
        val streamUrl = currentStreamUrl
        if (streamUrl == null) return  // nothing playing yet — Cast will handle next loadAndPlay

        val position = exoPlayer.currentPosition
        exoPlayer.pause()
        isCasting = true

        // Update MediaSession to use CastPlayer
        mediaSession.player = castPlayer!!

        serviceScope.launch {
            val episode = if (currentEpisodeId > 0) episodeRepository.getEpisodeById(currentEpisodeId) else null
            val feed    = if (episode != null) feedRepository.getFeedById(episode.feedId) else null
            val artworkUriStr = episode?.imageUrl ?: feed?.imageUrl

            val metadata = MediaMetadata.Builder()
                .setTitle(episode?.title)
                .setArtist(feed?.title)
                .setArtworkUri(artworkUriStr?.let { Uri.parse(it) })
                .build()

            val castItem = MediaItem.Builder()
                .setMediaId(currentEpisodeId.toString())
                .setUri(streamUrl)
                .setMimeType(MimeTypes.AUDIO_MPEG)
                .setMediaMetadata(metadata)
                .build()

            castPlayer!!.setMediaItem(castItem)
            castPlayer!!.seekTo(position)
            castPlayer!!.prepare()
            castPlayer!!.play()
        }
    }

    /**
     * Called by [castSessionListener] when the Cast session ends or is suspended.
     * Saves the CastPlayer position and resumes ExoPlayer from there.
     */
    private fun switchToLocal() {
        val position = castPlayer?.currentPosition ?: 0L
        castPlayer?.stop()
        isCasting = false

        // Update MediaSession to use ExoPlayer
        mediaSession.player = exoPlayer

        val episodeId = currentEpisodeId
        if (episodeId < 0) return

        serviceScope.launch {
            val episode = episodeRepository.getEpisodeById(episodeId) ?: return@launch
            val feed    = feedRepository.getFeedById(episode.feedId)
            val artworkUriStr = episode.imageUrl ?: feed?.imageUrl

            val metadata = MediaMetadata.Builder()
                .setTitle(episode.title)
                .setArtist(feed?.title)
                .setArtworkUri(artworkUriStr?.let { Uri.parse(it) })
                .build()

            // Resume with local file if available, otherwise stream
            val uri = episode.localFilePath
                ?.takeIf { File(it).exists() }
                ?: episode.url

            val exoItem = MediaItem.Builder()
                .setMediaId(episodeId.toString())
                .setUri(uri)
                .setMediaMetadata(metadata)
                .build()

            exoPlayer.setMediaItem(exoItem)
            exoPlayer.seekTo(position)
            exoPlayer.prepare()
            exoPlayer.play()
        }
    }

    private val castSessionListener = object : SessionAvailabilityListener {
        override fun onCastSessionAvailable() { switchToCast() }
        override fun onCastSessionUnavailable() { switchToLocal() }
    }

    // ── Volume boost (LoudnessEnhancer — §7.6) ────────────────────────────────

    /**
     * Apply software gain beyond 0 dB via [LoudnessEnhancer].
     * NEVER set player.volume > 1.0f.
     *
     * Only applies to ExoPlayer (LoudnessEnhancer requires a real audio session id).
     *
     * @param boostLevel 1 = no boost (0 dB), 2–10 = ~1 dB per step (max 10 dB)
     */
    fun applyVolumeBoost(boostLevel: Int) {
        if (exoPlayer.audioSessionId == C.AUDIO_SESSION_ID_UNSET) return
        val enhancer = loudnessEnhancer
            ?: LoudnessEnhancer(exoPlayer.audioSessionId).also { loudnessEnhancer = it }
        val gainMillibels = if (boostLevel <= 1) 0 else ((boostLevel - 1) * 111).coerceAtMost(1000)
        enhancer.setTargetGain(gainMillibels)
        enhancer.enabled = gainMillibels > 0
    }

    // ── Sleep timer ───────────────────────────────────────────────────────────

    private fun startSleepTimer(durationMs: Long) {
        sleepTimerJob?.cancel()
        sleepTimerJob = serviceScope.launch {
            delay(durationMs)
            activePlayer.pause()
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
                if (activePlayer.isPlaying) {
                    // Guard: stop the timer if the episode was deleted while playing
                    if (episodeRepository.getEpisodeById(episodeId) == null) {
                        stopPositionSaving()
                        return@launch
                    }
                    episodeRepository.savePlayPosition(episodeId, activePlayer.currentPosition)
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

    // ── Player event listener (shared by ExoPlayer and CastPlayer) ────────────

    private val playerListener = object : Player.Listener {

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                if (currentEpisodeId > 0) startPositionSaving(currentEpisodeId)
                // LoudnessEnhancer must be re-created when audioSessionId changes (after prepare)
                // Only applies to ExoPlayer; skip silently when casting
                if (!isCasting) applyVolumeBoost(1)
            } else {
                stopPositionSaving()
                // Save position immediately on pause
                if (currentEpisodeId > 0) {
                    val position = activePlayer.currentPosition
                    serviceScope.launch {
                        episodeRepository.savePlayPosition(currentEpisodeId, position)
                    }
                }
            }
            notifyWidget(isPlaying)
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            currentEpisodeId = mediaItem?.mediaId?.toLongOrNull() ?: -1L
            notifyWidget(activePlayer.isPlaying)
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

        override fun onPlayerError(error: PlaybackException) {
            // Stop unconditionally — leaves the player in STATE_IDLE with no automatic retry.
            activePlayer.stop()
        }
    }
}
