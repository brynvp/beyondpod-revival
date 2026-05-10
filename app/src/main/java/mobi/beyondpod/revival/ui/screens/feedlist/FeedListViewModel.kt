package mobi.beyondpod.revival.ui.screens.feedlist

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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import mobi.beyondpod.revival.data.local.entity.CategoryEntity
import mobi.beyondpod.revival.data.local.entity.FeedEntity
import mobi.beyondpod.revival.data.repository.CategoryRepository
import mobi.beyondpod.revival.data.repository.FeedRepository
import mobi.beyondpod.revival.service.FeedUpdateWorker
import javax.inject.Inject

data class CategoryWithFeeds(
    val category: CategoryEntity?,   // null = Uncategorized group
    val feeds: List<FeedEntity>,
    val isExpanded: Boolean = true
)

sealed interface FeedListUiState {
    data object Loading : FeedListUiState
    data class Success(val groups: List<CategoryWithFeeds>) : FeedListUiState
    data class Error(val message: String) : FeedListUiState
}

/**
 * ViewModel for FeedListScreen.
 *
 * Groups feeds by primary category. Feeds with no primary category (or whose
 * primary category no longer exists) appear under the implicit "Uncategorized" group.
 *
 * Collapsible state is tracked in [collapsedIds] — a set of category IDs (Long) that
 * are currently collapsed. Null in the set = Uncategorized group is collapsed.
 */
@HiltViewModel
class FeedListViewModel @Inject constructor(
    private val feedRepository: FeedRepository,
    private val categoryRepository: CategoryRepository,
    private val workManager: WorkManager
) : ViewModel() {

    private val collapsedIds    = MutableStateFlow<Set<Long>>(emptySet())
    private val _isRefreshing   = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing
    private val UNCATEGORIZED_SENTINEL = -1L

    init {
        // Clear any stale lastUpdateFailed flags left by the pre-fix background worker.
        // This is a one-shot repair that runs whenever the Feeds screen is first shown.
        viewModelScope.launch { feedRepository.clearStaleUpdateFailedFlags() }
    }

    val uiState: StateFlow<FeedListUiState> = combine(
        categoryRepository.getAllCategories(),
        feedRepository.getAllFeeds(),
        collapsedIds
    ) { categories, feeds, collapsed ->
        buildGroups(categories, feeds, collapsed)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = FeedListUiState.Loading
    )

    fun refresh() {
        // Route through FeedUpdateWorker (ALL_FEEDS) so every feed goes through the full
        // pipeline: fetch → parse → upsert → autoDownloadNewEpisodes.
        // The old direct refreshAllFeeds() call ran 30 feeds serially in the ViewModel coroutine
        // (30–150 second spinner) and never called autoDownloadNewEpisodes, so no downloads
        // ever triggered from this screen. REPLACE = a second pull while a refresh is already
        // running cancels the old job and starts fresh (user intent is "refresh now").
        workManager.enqueueUniqueWork(
            "refresh_all_feeds",
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<FeedUpdateWorker>()
                .setInputData(workDataOf(
                    FeedUpdateWorker.KEY_FEED_ID  to FeedUpdateWorker.ALL_FEEDS,
                    FeedUpdateWorker.KEY_IS_MANUAL to true
                ))
                .build()
        )
        // Spinner: show briefly, Room Flows update the UI as each feed completes.
        viewModelScope.launch {
            _isRefreshing.value = true
            delay(1_500)
            _isRefreshing.value = false
        }
    }

    fun toggleCategory(categoryId: Long?) {
        val key = categoryId ?: UNCATEGORIZED_SENTINEL
        collapsedIds.value = collapsedIds.value.toMutableSet().apply {
            if (contains(key)) remove(key) else add(key)
        }
    }

    private fun buildGroups(
        categories: List<CategoryEntity>,
        feeds: List<FeedEntity>,
        collapsed: Set<Long>
    ): FeedListUiState {
        val categoryIdSet = categories.map { it.id }.toSet()
        val groups = mutableListOf<CategoryWithFeeds>()

        // One group per category, in sortOrder
        for (category in categories) {
            val catFeeds = feeds.filter { it.primaryCategoryId == category.id }
            if (catFeeds.isNotEmpty()) {
                groups += CategoryWithFeeds(
                    category = category,
                    feeds = catFeeds,
                    isExpanded = category.id !in collapsed
                )
            }
        }

        // Uncategorized = feeds whose primaryCategoryId is null or points to a deleted category
        val uncategorized = feeds.filter {
            it.primaryCategoryId == null || it.primaryCategoryId !in categoryIdSet
        }
        if (uncategorized.isNotEmpty()) {
            groups += CategoryWithFeeds(
                category = null,
                feeds = uncategorized,
                isExpanded = UNCATEGORIZED_SENTINEL !in collapsed
            )
        }

        return FeedListUiState.Success(groups)
    }
}
