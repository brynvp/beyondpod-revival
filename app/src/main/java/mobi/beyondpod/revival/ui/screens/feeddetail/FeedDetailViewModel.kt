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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mobi.beyondpod.revival.data.local.entity.CategoryEntity
import mobi.beyondpod.revival.data.local.entity.EpisodeEntity
import mobi.beyondpod.revival.data.local.entity.FeedEntity
import mobi.beyondpod.revival.data.repository.CategoryRepository
import mobi.beyondpod.revival.data.repository.EpisodeRepository
import mobi.beyondpod.revival.data.repository.FeedRepository
import mobi.beyondpod.revival.data.repository.DownloadRepository
import mobi.beyondpod.revival.domain.usecase.download.EnqueueDownloadUseCase
import mobi.beyondpod.revival.domain.usecase.feed.DeleteFeedUseCase
import mobi.beyondpod.revival.domain.usecase.feed.MoveFeedToCategoryUseCase
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
    private val categoryRepository: CategoryRepository,
    private val downloadRepository: DownloadRepository,
    private val deleteFeedUseCase: DeleteFeedUseCase,
    private val enqueueDownloadUseCase: EnqueueDownloadUseCase,
    private val moveFeedToCategoryUseCase: MoveFeedToCategoryUseCase
) : ViewModel() {

    val feedId: Long = checkNotNull(savedStateHandle[Screen.FeedEpisodes.ARG_FEED_ID])

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing

    private val _showMobileWarning = MutableStateFlow(false)
    val showMobileWarning: StateFlow<Boolean> = _showMobileWarning

    val categories: StateFlow<List<CategoryEntity>> = categoryRepository.getAllCategories()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList()
        )

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
            val refreshResult = feedRepository.refreshFeed(feedId)
            // Only run download/cleanup logic when the RSS fetch actually succeeded.
            // If it failed, episodes haven't changed — no point running cleanup, and
            // running it on a stale snapshot risks deleting valid downloads.
            if (refreshResult.isSuccess) {
                if (downloadRepository.checkMobileDownloadBlocked(feedId)) {
                    _showMobileWarning.value = true
                } else {
                    downloadRepository.autoDownloadNewEpisodes(feedId, isManualRefresh = true)
                }
            }
            _isRefreshing.value = false
        }
    }

    /** User approved downloading over mobile data — proceed with downloads. */
    fun confirmMobileDownload() {
        viewModelScope.launch {
            _showMobileWarning.value = false
            downloadRepository.autoDownloadNewEpisodes(feedId, isManualRefresh = true, mobileAllowed = true)
        }
    }

    fun dismissMobileWarning() {
        _showMobileWarning.value = false
    }

    /** Mark every episode in this feed as played. */
    fun markAllPlayed() {
        viewModelScope.launch {
            val state = uiState.value as? FeedDetailUiState.Success ?: return@launch
            episodeRepository.batchMarkPlayed(state.episodes.map { it.id })
        }
    }

    /** Persist changes to feed properties (title, category, settings, etc.).
     *  G19: if maxEpisodesToKeep was reduced, trigger immediate cleanup for this feed so
     *  the user sees the effect right away rather than waiting for the next refresh. */
    fun updateFeedProperties(feed: FeedEntity) {
        viewModelScope.launch {
            val existing = feedRepository.getFeedById(feedId)
            feedRepository.updateFeedProperties(feed)
            // Immediate cleanup when keepCount is reduced (or newly set from unlimited)
            val oldKeep = existing?.maxEpisodesToKeep
            val newKeep = feed.maxEpisodesToKeep
            val keepReduced = newKeep != null && (oldKeep == null || newKeep < oldKeep)
            if (keepReduced) {
                withContext(Dispatchers.IO) {
                    downloadRepository.autoDownloadNewEpisodes(feedId, isManualRefresh = false)
                }
            }
        }
    }

    fun assignCategory(categoryId: Long?) {
        viewModelScope.launch {
            moveFeedToCategoryUseCase(feedId, categoryId)
        }
    }

    fun downloadEpisode(episodeId: Long) {
        viewModelScope.launch { enqueueDownloadUseCase(episodeId) }
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
