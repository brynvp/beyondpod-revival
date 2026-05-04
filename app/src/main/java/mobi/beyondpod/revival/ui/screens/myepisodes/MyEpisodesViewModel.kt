package mobi.beyondpod.revival.ui.screens.myepisodes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import mobi.beyondpod.revival.data.local.entity.FeedEntity
import kotlinx.coroutines.launch
import mobi.beyondpod.revival.data.local.entity.EpisodeEntity
import mobi.beyondpod.revival.data.repository.EpisodeRepository
import mobi.beyondpod.revival.data.repository.FeedRepository
import mobi.beyondpod.revival.domain.usecase.download.EnqueueDownloadUseCase
import mobi.beyondpod.revival.service.FeedUpdateWorker
import javax.inject.Inject

sealed interface MyEpisodesUiState {
    data object Loading : MyEpisodesUiState
    data class Success(val episodes: List<EpisodeEntity>) : MyEpisodesUiState
    data class Error(val message: String) : MyEpisodesUiState
}

@HiltViewModel
class MyEpisodesViewModel @Inject constructor(
    private val episodeRepository: EpisodeRepository,
    private val feedRepository: FeedRepository,
    private val enqueueDownloadUseCase: EnqueueDownloadUseCase,
    private val workManager: WorkManager
) : ViewModel() {

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    // Flat list — latest 50 episodes across all feeds, newest pubDate first
    val uiState: StateFlow<MyEpisodesUiState> = episodeRepository.getLatestEpisodes(50)
        .map { episodes -> MyEpisodesUiState.Success(episodes) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = MyEpisodesUiState.Loading
        )

    // Single shared Room observer for feeds — both maps derive from this, not from two
    // separate getAllFeeds() calls (which would create two DB observers for the same table).
    private val _feeds: StateFlow<List<FeedEntity>> = feedRepository.getAllFeeds()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val feedImageUrls: StateFlow<Map<Long, String?>> = _feeds
        .map { list -> list.associate { it.id to it.imageUrl } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val feedTitles: StateFlow<Map<Long, String>> = _feeds
        .map { list -> list.associate { it.id to it.title } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    fun buildQueueAndPlay() {
        viewModelScope.launch {
            val episodes = (uiState.value as? MyEpisodesUiState.Success)?.episodes ?: return@launch
            episodeRepository.buildQueueSnapshot(null, episodes.map { it.id })
        }
    }

    fun shuffleAndPlay() {
        viewModelScope.launch {
            val episodes = (uiState.value as? MyEpisodesUiState.Success)?.episodes ?: return@launch
            episodeRepository.buildQueueSnapshot(null, episodes.map { it.id }.shuffled())
        }
    }

    fun downloadEpisode(episodeId: Long) {
        viewModelScope.launch { enqueueDownloadUseCase(episodeId) }
    }

    /**
     * Pull-to-refresh: enqueue a one-time FeedUpdateWorker for ALL feeds.
     * The spinner dismisses after a brief delay — reactive flows update automatically.
     */
    fun refresh() {
        viewModelScope.launch {
            _isRefreshing.value = true
            workManager.enqueueUniqueWork(
                "manual_refresh_all",
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<FeedUpdateWorker>()
                    .setInputData(workDataOf(
                        FeedUpdateWorker.KEY_FEED_ID  to FeedUpdateWorker.ALL_FEEDS,
                        FeedUpdateWorker.KEY_IS_MANUAL to true
                    ))
                    .build()
            )
            delay(1_500)
            _isRefreshing.value = false
        }
    }
}
