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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import mobi.beyondpod.revival.data.local.entity.EpisodeEntity
import mobi.beyondpod.revival.data.repository.EpisodeRepository
import mobi.beyondpod.revival.data.repository.FeedRepository
import mobi.beyondpod.revival.domain.usecase.download.EnqueueDownloadUseCase
import mobi.beyondpod.revival.service.FeedUpdateWorker
import javax.inject.Inject

data class EpisodeSection(val title: String, val episodes: List<EpisodeEntity>)

sealed interface MyEpisodesUiState {
    data object Loading : MyEpisodesUiState
    data class Success(
        val sections: List<EpisodeSection>,
        val allEpisodes: List<EpisodeEntity>
    ) : MyEpisodesUiState
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

    // Episode sections — 3-flow combine, proven stable
    val uiState: StateFlow<MyEpisodesUiState> = combine(
        episodeRepository.getRecentDownloads(5),
        episodeRepository.getRecentlyPlayed(5),
        episodeRepository.getStarredEpisodes(50)
    ) { downloads, recentPlayed, starred ->
        val sections = buildList {
            if (downloads.isNotEmpty()) add(EpisodeSection("Latest Downloads", downloads))
            if (recentPlayed.isNotEmpty()) add(EpisodeSection("Recently Played", recentPlayed))
            if (starred.isNotEmpty()) add(EpisodeSection("Starred", starred))
        }
        MyEpisodesUiState.Success(sections, (downloads + recentPlayed + starred).distinctBy { it.id })
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = MyEpisodesUiState.Loading
    )

    // Feed artwork — separate flow, never touches the episode combine
    val feedImageUrls: StateFlow<Map<Long, String?>> = feedRepository.getAllFeeds()
        .map { list -> list.associate { it.id to it.imageUrl } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap<Long, String?>())

    // Feed titles — separate flow
    val feedTitles: StateFlow<Map<Long, String>> = feedRepository.getAllFeeds()
        .map { list -> list.associate { it.id to it.title } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap<Long, String>())

    fun buildQueueAndPlay() {
        viewModelScope.launch {
            val episodes = (uiState.value as? MyEpisodesUiState.Success)?.allEpisodes ?: return@launch
            episodeRepository.buildQueueSnapshot(null, episodes.map { it.id })
        }
    }

    fun shuffleAndPlay() {
        viewModelScope.launch {
            val episodes = (uiState.value as? MyEpisodesUiState.Success)?.allEpisodes ?: return@launch
            episodeRepository.buildQueueSnapshot(null, episodes.map { it.id }.shuffled())
        }
    }

    fun downloadEpisode(episodeId: Long) {
        viewModelScope.launch { enqueueDownloadUseCase(episodeId) }
    }

    /**
     * Pull-to-refresh: enqueue a one-time FeedUpdateWorker for ALL feeds.
     * The spinner is shown briefly then dismissed — reactive flows in the
     * sections update automatically as downloads and DB changes come in.
     */
    fun refresh() {
        viewModelScope.launch {
            _isRefreshing.value = true
            workManager.enqueueUniqueWork(
                "manual_refresh_all",
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<FeedUpdateWorker>()
                    .setInputData(workDataOf(FeedUpdateWorker.KEY_FEED_ID to FeedUpdateWorker.ALL_FEEDS))
                    .build()
            )
            // Brief delay so user sees the indicator, then let reactive flows handle updates
            delay(1_500)
            _isRefreshing.value = false
        }
    }
}
