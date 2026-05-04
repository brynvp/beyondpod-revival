package mobi.beyondpod.revival.ui.screens.search

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.workDataOf
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import mobi.beyondpod.revival.data.repository.PodcastSearchRepository
import mobi.beyondpod.revival.data.repository.PodcastSearchResult
import mobi.beyondpod.revival.domain.usecase.feed.SubscribeToFeedUseCase
import mobi.beyondpod.revival.service.FeedUpdateWorker
import javax.inject.Inject

sealed interface PodcastSearchUiState {
    data object Idle                                        : PodcastSearchUiState
    data object Loading                                    : PodcastSearchUiState
    data class  Results(val items: List<PodcastSearchResult>) : PodcastSearchUiState
    data class  Error(val message: String)                 : PodcastSearchUiState
}

@HiltViewModel
class PodcastSearchViewModel @Inject constructor(
    private val searchRepository: PodcastSearchRepository,
    private val subscribeToFeedUseCase: SubscribeToFeedUseCase,
    private val workManager: WorkManager
) : ViewModel() {

    var query by mutableStateOf("")
        private set

    private val _uiState = MutableStateFlow<PodcastSearchUiState>(PodcastSearchUiState.Idle)
    val uiState: StateFlow<PodcastSearchUiState> = _uiState

    /** feedUrls that have already been subscribed this session — drives button state. */
    private val _subscribedUrls = MutableStateFlow<Set<String>>(emptySet())
    val subscribedUrls: StateFlow<Set<String>> = _subscribedUrls

    fun onQueryChange(value: String) {
        query = value
    }

    fun search() {
        val term = query.trim()
        if (term.isBlank()) return
        viewModelScope.launch {
            _uiState.value = PodcastSearchUiState.Loading
            searchRepository.search(term)
                .onSuccess { results ->
                    _uiState.value = if (results.isEmpty())
                        PodcastSearchUiState.Error("No podcasts found for \"$term\"")
                    else
                        PodcastSearchUiState.Results(results)
                }
                .onFailure { e ->
                    _uiState.value = PodcastSearchUiState.Error(
                        e.message ?: "Search failed — check your connection"
                    )
                }
        }
    }

    fun subscribe(feedUrl: String) {
        viewModelScope.launch {
            subscribeToFeedUseCase(feedUrl)
                .onSuccess { feed ->
                    _subscribedUrls.value = _subscribedUrls.value + feedUrl
                    enqueueImmediateRefresh(feed.id)
                }
                // silently ignore duplicate-subscribe (subscribeToFeed returns existing record)
        }
    }

    private fun enqueueImmediateRefresh(feedId: Long) {
        val request = OneTimeWorkRequestBuilder<FeedUpdateWorker>()
            .setInputData(workDataOf(
                FeedUpdateWorker.KEY_FEED_ID  to feedId,
                FeedUpdateWorker.KEY_IS_MANUAL to true
            ))
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        workManager.enqueueUniqueWork(
            "subscribe_refresh_$feedId",
            ExistingWorkPolicy.KEEP,
            request
        )
    }
}
