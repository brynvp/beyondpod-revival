package mobi.beyondpod.revival.ui.screens.addfeed

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
import mobi.beyondpod.revival.data.local.entity.FeedEntity
import mobi.beyondpod.revival.data.repository.CategoryRepository
import mobi.beyondpod.revival.domain.usecase.category.CreateCategoryUseCase
import mobi.beyondpod.revival.domain.usecase.feed.MoveFeedToCategoryUseCase
import mobi.beyondpod.revival.domain.usecase.feed.SubscribeToFeedUseCase
import mobi.beyondpod.revival.service.FeedUpdateWorker
import javax.inject.Inject

sealed interface AddFeedUiState {
    data object Idle : AddFeedUiState
    data object Loading : AddFeedUiState
    /** Feed fetched — preview card shown. */
    data class Preview(val feed: FeedEntity) : AddFeedUiState
    data class Error(val message: String) : AddFeedUiState
    /**
     * Feed saved to DB. Category picker dialog is shown before navigating away.
     * Transitions to [Subscribed] after the user picks a category or taps Skip.
     */
    data class SubscribedPickCategory(val feedId: Long) : AddFeedUiState
    /** Category decided (or skipped). UI should navigate to FeedDetailScreen. */
    data class Subscribed(val feedId: Long) : AddFeedUiState
}

/**
 * ViewModel for AddFeedScreen.
 *
 * Subscribe flow:
 *   1. [fetchPreview] → RSS fetch → Preview state.
 *   2. [confirmSubscribe] → DB record created → SubscribedPickCategory state.
 *   3. User picks a category OR taps Skip:
 *      [assignCategoryAndProceed] / [createCategoryAndProceed] / [skipCategory]
 *      → category assigned (or not) → Subscribed state.
 *   4. UI navigates to FeedDetailScreen on Subscribed.
 */
@HiltViewModel
class AddFeedViewModel @Inject constructor(
    private val subscribeToFeedUseCase: SubscribeToFeedUseCase,
    private val categoryRepository: CategoryRepository,
    private val createCategoryUseCase: CreateCategoryUseCase,
    private val moveFeedToCategoryUseCase: MoveFeedToCategoryUseCase,
    private val workManager: WorkManager
) : ViewModel() {

    var urlInput by mutableStateOf("")
        private set

    private val _uiState = MutableStateFlow<AddFeedUiState>(AddFeedUiState.Idle)
    val uiState: StateFlow<AddFeedUiState> = _uiState

    /** All existing categories — drives the picker dialog list. */
    val categories: StateFlow<List<CategoryEntity>> = categoryRepository.getAllCategories()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList()
        )

    fun onUrlChange(value: String) {
        urlInput = value
        if (_uiState.value is AddFeedUiState.Error || _uiState.value is AddFeedUiState.Preview) {
            _uiState.value = AddFeedUiState.Idle
        }
    }

    fun fetchPreview() {
        if (_uiState.value is AddFeedUiState.Loading) return
        val url = urlInput.trim()
        if (url.isBlank()) {
            _uiState.value = AddFeedUiState.Error("Please enter a feed URL")
            return
        }
        viewModelScope.launch {
            _uiState.value = AddFeedUiState.Loading
            subscribeToFeedUseCase(url)
                .onSuccess { feed -> _uiState.value = AddFeedUiState.Preview(feed) }
                .onFailure { e -> _uiState.value = AddFeedUiState.Error(e.message ?: "Failed to load feed") }
        }
    }

    /** Confirm subscription — transitions to SubscribedPickCategory to show category dialog. */
    fun confirmSubscribe() {
        val state = _uiState.value as? AddFeedUiState.Preview ?: return
        _uiState.value = AddFeedUiState.SubscribedPickCategory(state.feed.id)
        enqueueImmediateRefresh(state.feed.id)
    }

    /** Skip category assignment — proceed straight to navigation. */
    fun skipCategory() {
        val feedId = (_uiState.value as? AddFeedUiState.SubscribedPickCategory)?.feedId ?: return
        _uiState.value = AddFeedUiState.Subscribed(feedId)
    }

    /** Assign an existing category, then proceed to navigation. */
    fun assignCategoryAndProceed(feedId: Long, categoryId: Long?) {
        viewModelScope.launch {
            moveFeedToCategoryUseCase(feedId, categoryId)
            _uiState.value = AddFeedUiState.Subscribed(feedId)
        }
    }

    /** Create a new category, assign it, then proceed to navigation. */
    fun createCategoryAndProceed(feedId: Long, name: String) {
        val trimmed = name.trim()
        if (trimmed.isBlank()) return
        viewModelScope.launch {
            val newId = createCategoryUseCase(CategoryEntity(name = trimmed))
            moveFeedToCategoryUseCase(feedId, newId)
            _uiState.value = AddFeedUiState.Subscribed(feedId)
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

    fun reset() {
        urlInput = ""
        _uiState.value = AddFeedUiState.Idle
    }
}
