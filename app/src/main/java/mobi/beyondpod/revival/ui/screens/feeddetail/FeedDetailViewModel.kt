package mobi.beyondpod.revival.ui.screens.feeddetail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import mobi.beyondpod.revival.data.local.entity.EpisodeEntity
import mobi.beyondpod.revival.data.local.entity.FeedEntity
import mobi.beyondpod.revival.data.repository.EpisodeRepository
import mobi.beyondpod.revival.data.repository.FeedRepository
import mobi.beyondpod.revival.domain.usecase.feed.DeleteFeedUseCase
import mobi.beyondpod.revival.ui.navigation.Screen
import javax.inject.Inject

sealed interface FeedDetailUiState {
    data object Loading : FeedDetailUiState
    data class Success(
        val feed: FeedEntity,
        val episodes: List<EpisodeEntity>
    ) : FeedDetailUiState
    data class Error(val message: String) : FeedDetailUiState
}

/**
 * ViewModel for FeedDetailScreen.
 *
 * Loads a single feed by [feedId] (from [SavedStateHandle]) and its episodes via
 * [EpisodeRepository.getEpisodesForFeed]. Exposes actions: mark all played,
 * update feed properties, and unsubscribe (delete feed).
 */
@HiltViewModel
class FeedDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val feedRepository: FeedRepository,
    private val episodeRepository: EpisodeRepository,
    private val deleteFeedUseCase: DeleteFeedUseCase
) : ViewModel() {

    val feedId: Long = checkNotNull(savedStateHandle[Screen.FeedEpisodes.ARG_FEED_ID])

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing

    val uiState: StateFlow<FeedDetailUiState> = combine(
        flow { emit(feedRepository.getFeedById(feedId)) },
        episodeRepository.getEpisodesForFeed(feedId)
    ) { feed, episodes ->
        if (feed == null) {
            FeedDetailUiState.Error("Feed not found")
        } else {
            FeedDetailUiState.Success(feed, episodes)
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = FeedDetailUiState.Loading
    )

    fun refresh() {
        viewModelScope.launch {
            _isRefreshing.value = true
            feedRepository.refreshFeed(feedId)
            _isRefreshing.value = false
        }
    }

    /** Mark every episode in this feed as played. */
    fun markAllPlayed() {
        viewModelScope.launch {
            val state = uiState.value as? FeedDetailUiState.Success ?: return@launch
            episodeRepository.batchMarkPlayed(state.episodes.map { it.id })
        }
    }

    /** Persist changes to feed properties (title, category, settings, etc.). */
    fun updateFeedProperties(feed: FeedEntity) {
        viewModelScope.launch {
            feedRepository.updateFeedProperties(feed)
        }
    }

    /**
     * Unsubscribe from this feed. Deletes the FeedEntity and all downloaded files.
     * The [onDeleted] callback is invoked on success so the UI can pop back.
     */
    fun deleteFeed(deleteDownloads: Boolean = true, onDeleted: () -> Unit) {
        viewModelScope.launch {
            deleteFeedUseCase(feedId, deleteDownloads)
            onDeleted()
        }
    }
}
