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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import mobi.beyondpod.revival.data.local.entity.CategoryEntity
import mobi.beyondpod.revival.data.repository.CategoryRepository
import mobi.beyondpod.revival.data.repository.PodcastSearchRepository
import mobi.beyondpod.revival.data.repository.PodcastSearchResult
import mobi.beyondpod.revival.domain.usecase.category.CreateCategoryUseCase
import mobi.beyondpod.revival.domain.usecase.feed.MoveFeedToCategoryUseCase
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
    private val categoryRepository: CategoryRepository,
    private val createCategoryUseCase: CreateCategoryUseCase,
    private val moveFeedToCategoryUseCase: MoveFeedToCategoryUseCase,
    private val workManager: WorkManager
) : ViewModel() {

    var query by mutableStateOf("")
        private set

    private val _uiState = MutableStateFlow<PodcastSearchUiState>(PodcastSearchUiState.Idle)
    val uiState: StateFlow<PodcastSearchUiState> = _uiState

    /** feedUrls that have already been subscribed this session — drives button state. */
    private val _subscribedUrls = MutableStateFlow<Set<String>>(emptySet())
    val subscribedUrls: StateFlow<Set<String>> = _subscribedUrls

    /** feedUrls where a subscribe coroutine is currently in-flight — disables the button. */
    private val _subscribingUrls = MutableStateFlow<Set<String>>(emptySet())
    val subscribingUrls: StateFlow<Set<String>> = _subscribingUrls

    /** All user categories — drives the category picker dialog. */
    val categories: StateFlow<List<CategoryEntity>> =
        categoryRepository.getAllCategories()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Non-null while the category picker dialog should be shown.
     * Set after a successful subscribe; cleared after category is assigned or skipped.
     */
    private val _pendingCategoryFeedId = MutableStateFlow<Long?>(null)
    val pendingCategoryFeedId: StateFlow<Long?> = _pendingCategoryFeedId

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
        // Guard: ignore taps while this URL is already being subscribed.
        if (feedUrl in _subscribingUrls.value || feedUrl in _subscribedUrls.value) return
        viewModelScope.launch {
            _subscribingUrls.value = _subscribingUrls.value + feedUrl
            try {
                subscribeToFeedUseCase(feedUrl)
                    .onSuccess { feed ->
                        _subscribedUrls.value = _subscribedUrls.value + feedUrl
                        enqueueImmediateRefresh(feed.id)
                        // Trigger category dialog — same pattern as AddFeedViewModel.
                        _pendingCategoryFeedId.value = feed.id
                    }
                // silently ignore duplicate-subscribe (subscribeToFeed returns existing record)
            } finally {
                _subscribingUrls.value = _subscribingUrls.value - feedUrl
            }
        }
    }

    /** Skip category assignment — dismiss dialog without assigning. */
    fun skipCategory() {
        _pendingCategoryFeedId.value = null
    }

    /** Assign an existing category then dismiss dialog. */
    fun assignCategoryAndProceed(feedId: Long, categoryId: Long?) {
        viewModelScope.launch {
            moveFeedToCategoryUseCase(feedId, categoryId)
            _pendingCategoryFeedId.value = null
        }
    }

    /** Create a new category, assign it, then dismiss dialog. */
    fun createCategoryAndProceed(feedId: Long, name: String) {
        val trimmed = name.trim()
        if (trimmed.isBlank()) return
        viewModelScope.launch {
            val newId = createCategoryUseCase(CategoryEntity(name = trimmed))
            moveFeedToCategoryUseCase(feedId, newId)
            _pendingCategoryFeedId.value = null
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
