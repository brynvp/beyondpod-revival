package mobi.beyondpod.revival.ui.screens.myepisodes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import mobi.beyondpod.revival.data.local.entity.EpisodeEntity
import mobi.beyondpod.revival.data.repository.EpisodeRepository
import mobi.beyondpod.revival.domain.usecase.episode.ClearMyEpisodesUseCase
import mobi.beyondpod.revival.domain.usecase.episode.GetMyEpisodesUseCase
import mobi.beyondpod.revival.domain.usecase.episode.RemoveFromMyEpisodesUseCase
import mobi.beyondpod.revival.domain.usecase.playlist.BuildMyEpisodesQueueUseCase
import javax.inject.Inject

sealed interface MyEpisodesUiState {
    data object Loading : MyEpisodesUiState
    data class Success(val episodes: List<EpisodeEntity>) : MyEpisodesUiState
    data class Error(val message: String) : MyEpisodesUiState
}

/**
 * ViewModel for the My Episodes screen.
 *
 * The 5 §7.5 behavioural rules are enforced here:
 *
 * Rule 1 — Manual curation: episodes come from [GetMyEpisodesUseCase] which reads
 *   EpisodeEntity.isInMyEpisodes / ManualPlaylistEpisodeCrossRef, NOT SmartPlaylist
 *   rule evaluation. [EvaluateSmartPlaylistUseCase] is intentionally NOT used here.
 *
 * Rule 2 — Active playback queue: [buildQueueAndPlay] calls [BuildMyEpisodesQueueUseCase]
 *   which generates a frozen QueueSnapshotEntity from My Episodes order, then starts
 *   PlaybackService.
 *
 * Rule 3 — Indestructible: no deletePlaylist() method is exposed. The UI shows no
 *   delete-playlist action for My Episodes.
 *
 * Rule 4 — Persists across queue regeneration: uiState drives from ManualPlaylistEpisodeCrossRef
 *   (the source of truth), not from QueueSnapshotEntity (the derived execution copy).
 *
 * Rule 5 — Auto-population: FeedUpdateWorker adds new downloads automatically (Phase 3).
 *   This ViewModel simply reflects the current state via a live Flow.
 */
@HiltViewModel
class MyEpisodesViewModel @Inject constructor(
    private val getMyEpisodesUseCase: GetMyEpisodesUseCase,
    private val removeFromMyEpisodesUseCase: RemoveFromMyEpisodesUseCase,
    private val clearMyEpisodesUseCase: ClearMyEpisodesUseCase,
    private val buildMyEpisodesQueueUseCase: BuildMyEpisodesQueueUseCase,
    private val episodeRepository: EpisodeRepository
) : ViewModel() {

    // ── Rule 1 + 4: live from ManualPlaylistEpisodeCrossRef, not from SmartPlaylist eval ──
    val uiState: StateFlow<MyEpisodesUiState> = getMyEpisodesUseCase()
        .map<List<EpisodeEntity>, MyEpisodesUiState> { MyEpisodesUiState.Success(it) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = MyEpisodesUiState.Loading
        )

    // ── Rule 2: generates QueueSnapshotEntity from My Episodes order ─────────
    fun buildQueueAndPlay(): Result<Unit>? {
        var result: Result<Unit>? = null
        viewModelScope.launch {
            result = buildMyEpisodesQueueUseCase()
            // PlaybackService is started by the UI layer via Context.startService()
            // after this use case returns successfully.
        }
        return result
    }

    /**
     * Shuffle play — builds a queue snapshot with episodes in random order.
     * Does NOT reorder My Episodes itself (§7.5 PlaylistHeaderActionBar).
     */
    fun shuffleAndPlay() {
        viewModelScope.launch {
            val episodes = (uiState.value as? MyEpisodesUiState.Success)?.episodes ?: return@launch
            val shuffledIds = episodes.map { it.id }.shuffled()
            // sourcePlaylistId = null for shuffle (not a rule-driven snapshot)
            episodeRepository.buildQueueSnapshot(null, shuffledIds)
        }
    }

    /** Remove one episode from My Episodes (swipe-to-dismiss / context action). */
    fun removeEpisode(episodeId: Long) {
        viewModelScope.launch {
            removeFromMyEpisodesUseCase(episodeId)
        }
    }

    /**
     * Reorder My Episodes. Updates ManualPlaylistEpisodeCrossRef positions and
     * syncs the active QueueSnapshotEntity atomically (§7.5 drag-to-reorder).
     * Full drag UI wired in Phase 6.
     */
    fun reorderEpisodes(orderedIds: List<Long>) {
        viewModelScope.launch {
            episodeRepository.reorderMyEpisodes(orderedIds)
        }
    }

    /** Clear Queue — removes all episodes from My Episodes + deactivates active snapshot. */
    fun clearQueue() {
        viewModelScope.launch {
            clearMyEpisodesUseCase()
        }
    }

    // ── Rule 3: NO deletePlaylist() method exposed — My Episodes is indestructible ──
}
