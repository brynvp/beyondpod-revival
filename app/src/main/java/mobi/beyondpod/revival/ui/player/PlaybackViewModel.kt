package mobi.beyondpod.revival.ui.player

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import mobi.beyondpod.revival.service.PlaybackService
import javax.inject.Inject

/**
 * Exposes current playback state for the persistent [MiniPlayer] bar.
 *
 * Connects to [PlaybackService] via [MediaController] once and registers a [Player.Listener]
 * to push real-time state updates. Gracefully no-ops if the service is not running.
 */
@HiltViewModel
class PlaybackViewModel @Inject constructor(
    @ApplicationContext private val context: Context
) : ViewModel() {

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

    private var controller: MediaController? = null

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
                        override fun onIsPlayingChanged(isPlaying: Boolean) = syncState(mc)
                        override fun onMediaItemTransition(item: MediaItem?, reason: Int) = syncState(mc)
                        override fun onPlaybackStateChanged(state: Int) = syncState(mc)
                    })
                }
                // If service is not running the Future.get() throws — that's expected; stay empty.
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
    }

    fun togglePlayPause() {
        controller?.let { mc -> if (mc.isPlaying) mc.pause() else mc.play() }
    }

    fun skipForward() {
        controller?.seekForward()
    }

    override fun onCleared() {
        controller?.release()
        super.onCleared()
    }
}
