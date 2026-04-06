package mobi.beyondpod.revival.ui.player

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import mobi.beyondpod.revival.data.repository.EpisodeRepository
import mobi.beyondpod.revival.data.settings.AppSettings
import mobi.beyondpod.revival.service.PlaybackService
import javax.inject.Inject

/**
 * Exposes current playback state for both [MiniPlayer] and [PlayerScreen].
 *
 * Connects to [PlaybackService] via [MediaController] once. Registers a [Player.Listener]
 * to push real-time state updates. Gracefully no-ops if the service is not running.
 *
 * Position polling runs every 500 ms while playing to keep the scrubber smooth.
 */
@HiltViewModel
class PlaybackViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dataStore: DataStore<Preferences>,
    private val episodeRepository: EpisodeRepository
) : ViewModel() {

    // ── Core playback state (MiniPlayer) ──────────────────────────────────────

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying

    private val _hasActiveEpisode = MutableStateFlow(false)
    val hasActiveEpisode: StateFlow<Boolean> = _hasActiveEpisode

    private val _currentTitle = MutableStateFlow<String?>(null)
    val currentTitle: StateFlow<String?> = _currentTitle

    private val _currentArtist = MutableStateFlow<String?>(null)
    val currentArtist: StateFlow<String?> = _currentArtist

    private val _artworkUri = MutableStateFlow<Uri?>(null)
    val artworkUri: StateFlow<Uri?> = _artworkUri

    // ── Extended state (PlayerScreen) ─────────────────────────────────────────

    private val _currentPosition = MutableStateFlow(0L)
    val currentPosition: StateFlow<Long> = _currentPosition

    private val _duration = MutableStateFlow(0L)
    val duration: StateFlow<Long> = _duration

    private val _currentEpisodeId = MutableStateFlow<Long?>(-1L)
    val currentEpisodeId: StateFlow<Long?> = _currentEpisodeId

    private val _episodeDescription = MutableStateFlow("")
    val episodeDescription: StateFlow<String> = _episodeDescription

    private val _playbackSpeed = MutableStateFlow(1f)
    val playbackSpeed: StateFlow<Float> = _playbackSpeed

    private val _sleepTimerRemainingMs = MutableStateFlow(0L)
    val sleepTimerRemainingMs: StateFlow<Long> = _sleepTimerRemainingMs

    // ── Internal ──────────────────────────────────────────────────────────────

    private var controller: MediaController? = null
    private var positionPollingJob: Job? = null
    private var sleepTimerCountdownJob: Job? = null

    init {
        connectController()
    }

    private fun connectController() {
        val token = SessionToken(
            context,
            ComponentName(context, PlaybackService::class.java)
        )
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener(
            {
                runCatching {
                    val mc = future.get()
                    controller = mc
                    syncState(mc)
                    mc.addListener(object : Player.Listener {
                        override fun onIsPlayingChanged(isPlaying: Boolean) {
                            syncState(mc)
                            if (isPlaying) startPositionPolling() else stopPositionPolling()
                        }

                        override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                            syncState(mc)
                            val episodeId = item?.mediaId?.toLongOrNull()
                            _currentEpisodeId.value = episodeId
                            fetchEpisodeDescription(episodeId)
                        }

                        override fun onPlaybackStateChanged(state: Int) = syncState(mc)

                        override fun onPlaybackParametersChanged(params: PlaybackParameters) {
                            _playbackSpeed.value = params.speed
                        }
                    })
                }
                // If service is not running the Future.get() throws — expected; stay empty.
            },
            ContextCompat.getMainExecutor(context)
        )
    }

    private fun syncState(mc: MediaController) {
        val item = mc.currentMediaItem
        _isPlaying.value = mc.isPlaying
        _hasActiveEpisode.value = item != null
        _currentTitle.value = item?.mediaMetadata?.title?.toString()
        _currentArtist.value = item?.mediaMetadata?.artist?.toString()
        _artworkUri.value = item?.mediaMetadata?.artworkUri
        _duration.value = mc.duration.coerceAtLeast(0L)
        _currentPosition.value = mc.currentPosition.coerceAtLeast(0L)
        _playbackSpeed.value = mc.playbackParameters.speed
    }

    private fun fetchEpisodeDescription(episodeId: Long?) {
        viewModelScope.launch {
            val episode = episodeId?.let { episodeRepository.getEpisodeById(it) }
            _episodeDescription.value = when {
                episode == null              -> ""
                episode.htmlDescription.isNotBlank() -> episode.htmlDescription
                else                        -> episode.description
            }
        }
    }

    // ── Position polling ──────────────────────────────────────────────────────

    private fun startPositionPolling() {
        positionPollingJob?.cancel()
        positionPollingJob = viewModelScope.launch {
            while (isActive) {
                controller?.let {
                    _currentPosition.value = it.currentPosition.coerceAtLeast(0L)
                    _duration.value = it.duration.coerceAtLeast(0L)
                }
                delay(500L)
            }
        }
    }

    private fun stopPositionPolling() {
        positionPollingJob?.cancel()
        positionPollingJob = null
    }

    // ── Playback controls ─────────────────────────────────────────────────────

    fun togglePlayPause() {
        controller?.let { mc -> if (mc.isPlaying) mc.pause() else mc.play() }
    }

    fun skipForward() {
        controller?.seekForward()
    }

    fun seek(positionMs: Long) {
        controller?.seekTo(positionMs)
        _currentPosition.value = positionMs
    }

    fun rewind() {
        viewModelScope.launch {
            val skipSec = dataStore.data.map { it[AppSettings.SKIP_BACK_SECONDS] ?: 10 }.first()
            val pos = ((controller?.currentPosition ?: 0L) - skipSec * 1000L).coerceAtLeast(0L)
            controller?.seekTo(pos)
            _currentPosition.value = pos
        }
    }

    fun fastForward() {
        viewModelScope.launch {
            val skipSec = dataStore.data.map { it[AppSettings.SKIP_FORWARD_SECONDS] ?: 30 }.first()
            val pos = (controller?.currentPosition ?: 0L) + skipSec * 1000L
            controller?.seekTo(pos)
            _currentPosition.value = pos
        }
    }

    fun setPlaybackSpeed(speed: Float) {
        controller?.setPlaybackParameters(PlaybackParameters(speed))
        _playbackSpeed.value = speed
    }

    // ── Sleep timer ───────────────────────────────────────────────────────────

    fun setSleepTimer(durationMs: Long) {
        context.startService(
            Intent(context, PlaybackService::class.java).apply {
                action = PlaybackService.ACTION_SET_SLEEP_TIMER
                putExtra(PlaybackService.EXTRA_SLEEP_DURATION_MS, durationMs)
            }
        )
        startSleepCountdown(durationMs)
    }

    fun cancelSleepTimer() {
        context.startService(
            Intent(context, PlaybackService::class.java).apply {
                action = PlaybackService.ACTION_CANCEL_SLEEP_TIMER
            }
        )
        sleepTimerCountdownJob?.cancel()
        _sleepTimerRemainingMs.value = 0L
    }

    private fun startSleepCountdown(durationMs: Long) {
        sleepTimerCountdownJob?.cancel()
        val endTime = System.currentTimeMillis() + durationMs
        sleepTimerCountdownJob = viewModelScope.launch {
            while (isActive) {
                val remaining = endTime - System.currentTimeMillis()
                if (remaining <= 0L) {
                    _sleepTimerRemainingMs.value = 0L
                    break
                }
                _sleepTimerRemainingMs.value = remaining
                delay(1_000L)
            }
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCleared() {
        stopPositionPolling()
        sleepTimerCountdownJob?.cancel()
        controller?.release()
        super.onCleared()
    }
}
