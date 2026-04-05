package mobi.beyondpod.revival.ui.screens.addfeed

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import mobi.beyondpod.revival.data.local.entity.FeedEntity
import mobi.beyondpod.revival.domain.usecase.feed.SubscribeToFeedUseCase
import javax.inject.Inject

sealed interface AddFeedUiState {
    data object Idle : AddFeedUiState
    data object Loading : AddFeedUiState
    /** Feed created (minimal record — RSS parsed in Phase 3 by FeedUpdateWorker). */
    data class Preview(val feed: FeedEntity) : AddFeedUiState
    data class Error(val message: String) : AddFeedUiState
    /** Subscription confirmed; [feedId] can be used to navigate to FeedDetailScreen. */
    data class Subscribed(val feedId: Long) : AddFeedUiState
}

/**
 * ViewModel for AddFeedScreen.
 *
 * The subscribe flow:
 *   1. User enters a feed URL.
 *   2. [fetchPreview] creates a minimal [FeedEntity] (RSS will be parsed by
 *      FeedUpdateWorker in Phase 3) and transitions to [AddFeedUiState.Preview].
 *   3. [confirmSubscribe] accepts the preview and transitions to [AddFeedUiState.Subscribed].
 *   4. The UI navigates to FeedDetailScreen on Subscribed.
 */
@HiltViewModel
class AddFeedViewModel @Inject constructor(
    private val subscribeToFeedUseCase: SubscribeToFeedUseCase
) : ViewModel() {

    var urlInput by mutableStateOf("")
        private set

    private val _uiState = MutableStateFlow<AddFeedUiState>(AddFeedUiState.Idle)
    val uiState: StateFlow<AddFeedUiState> = _uiState

    fun onUrlChange(value: String) {
        urlInput = value
        // Clear error/preview when the user edits the URL
        if (_uiState.value is AddFeedUiState.Error || _uiState.value is AddFeedUiState.Preview) {
            _uiState.value = AddFeedUiState.Idle
        }
    }

    /**
     * Fetch a preview of the feed at [urlInput].
     *
     * Calls [SubscribeToFeedUseCase] which creates a minimal DB record. Full RSS parsing
     * (title, artwork, episodes) happens in Phase 3 via FeedUpdateWorker.
     */
    fun fetchPreview() {
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

    /** Confirm the subscription shown in the preview card. */
    fun confirmSubscribe() {
        val state = _uiState.value as? AddFeedUiState.Preview ?: return
        _uiState.value = AddFeedUiState.Subscribed(state.feed.id)
    }

    fun reset() {
        urlInput = ""
        _uiState.value = AddFeedUiState.Idle
    }
}
