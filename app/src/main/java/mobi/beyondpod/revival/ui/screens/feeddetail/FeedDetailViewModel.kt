package mobi.beyondpod.revival.ui.screens.feeddetail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mobi.beyondpod.revival.data.local.entity.CategoryEntity
import mobi.beyondpod.revival.data.local.entity.DownloadStrategy
import mobi.beyondpod.revival.data.local.entity.EpisodeEntity
import mobi.beyondpod.revival.data.local.entity.FeedEntity
import mobi.beyondpod.revival.data.repository.CategoryRepository
import mobi.beyondpod.revival.data.repository.EpisodeRepository
import mobi.beyondpod.revival.data.repository.FeedRepository
import mobi.beyondpod.revival.data.repository.DownloadRepository
import mobi.beyondpod.revival.domain.usecase.download.EnqueueDownloadUseCase
import mobi.beyondpod.revival.domain.usecase.feed.DeleteFeedUseCase
import mobi.beyondpod.revival.domain.usecase.feed.MoveFeedToCategoryUseCase
import mobi.beyondpod.revival.service.FeedUpdateWorker
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
    private val moveFeedToCategoryUseCase: MoveFeedToCategoryUseCase,
    private val workManager: WorkManager
) : ViewModel() {

    val feedId: Long = checkNotNull(savedStateHandle[Screen.FeedEpisodes.ARG_FEED_ID])

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing

    private val _showMobileWarning = MutableStateFlow(false)
    val showMobileWarning: StateFlow<Boolean> = _showMobileWarning

    /**
     * When the user taps the download button while on mobile for a WiFi-only feed,
     * we store the episode ID here and raise the warning dialog. On confirm, this ID
     * is downloaded directly. Null = bulk-refresh path (autoDownloadNewEpisodes).
     */
    private var pendingMobileEpisodeId: Long? = null

    val categories: StateFlow<List<CategoryEntity>> = categoryRepository.getAllCategories()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList()
        )

    val uiState: StateFlow<FeedDetailUiState> = combine(
        feedRepository.getFeedByIdFlow(feedId),   // live Room Flow — re-emits on every DB write
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
        // Route through WorkManager so this never runs concurrently with the periodic
        // FeedUpdateWorker for the same feed. KEEP = if a worker is already running for
        // this feed, drop the new request rather than cancelling the in-progress one.
        // Previously this called feedRepository.refreshFeed() directly on viewModelScope,
        // which raced with the WorkManager job: concurrent upserts caused duplicate episode
        // rows and concurrent archiveRemovedEpisodes calls incorrectly archived valid episodes.
        workManager.enqueueUniqueWork(
            "refresh_feed_$feedId",
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<FeedUpdateWorker>()
                .setInputData(workDataOf(
                    FeedUpdateWorker.KEY_FEED_ID  to feedId,
                    FeedUpdateWorker.KEY_IS_MANUAL to true
                ))
                .build()
        )
        // Spinner: show briefly, reactive Room flows update the UI automatically when done.
        viewModelScope.launch {
            _isRefreshing.value = true
            kotlinx.coroutines.delay(1_500)
            _isRefreshing.value = false
        }
    }

    /**
     * User approved downloading over mobile data.
     * - Single-episode path: pendingMobileEpisodeId is set → download just that episode.
     * - Bulk path: pendingMobileEpisodeId is null → run autoDownloadNewEpisodes (pull-to-refresh).
     */
    fun confirmMobileDownload() {
        viewModelScope.launch {
            _showMobileWarning.value = false
            val episodeId = pendingMobileEpisodeId
            pendingMobileEpisodeId = null
            if (episodeId != null) {
                enqueueDownloadUseCase(episodeId)
            } else {
                downloadRepository.autoDownloadNewEpisodes(feedId, isManualRefresh = true, mobileAllowed = true)
            }
        }
    }

    fun dismissMobileWarning() {
        _showMobileWarning.value = false
        pendingMobileEpisodeId = null
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
            // If switching TO MANUAL — cancel all in-flight downloads for this feed.
            // MANUAL = the user owns all download decisions; no automated activity should continue.
            if (existing != null &&
                existing.downloadStrategy != DownloadStrategy.MANUAL &&
                feed.downloadStrategy == DownloadStrategy.MANUAL) {
                withContext(Dispatchers.IO) {
                    downloadRepository.cancelFeedDownloads(feed.id)
                }
            }
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

    /**
     * Download a single episode. If the feed is WiFi-only and we are on mobile,
     * raises the warning dialog instead of silently doing nothing (#14).
     */
    fun downloadEpisode(episodeId: Long) {
        viewModelScope.launch {
            if (downloadRepository.checkMobileDownloadBlocked(feedId)) {
                pendingMobileEpisodeId = episodeId
                _showMobileWarning.value = true
            } else {
                enqueueDownloadUseCase(episodeId)
            }
        }
    }

    /**
     * Delete the downloaded file for an episode and reset its state to NOT_DOWNLOADED.
     * isProtected episodes are silently ignored (rule #4 — absolute veto).
     */
    fun deleteEpisodeDownload(episodeId: Long) {
        viewModelScope.launch {
            downloadRepository.deleteDownload(episodeId)
        }
    }

    /**
     * Toggle the favourite (starred + protected) state for an episode.
     * Starred  = heart icon shown in the episode row.
     * Protected = absolute veto on all auto-deletion (rule #4).
     * Both flags are toggled together so a favourite is always deletion-safe.
     */
    fun toggleFavourite(episodeId: Long) {
        viewModelScope.launch {
            // toggleStar and toggleProtected each read current state and flip it.
            // Calling them together keeps both flags in sync.
            episodeRepository.toggleStar(episodeId)
            episodeRepository.toggleProtected(episodeId)
        }
    }

    /** Share — stub; full implementation deferred to PHASE 4. */
    fun shareEpisode(episodeId: Long) {
        // TODO: launch share sheet with episode URL + title
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
