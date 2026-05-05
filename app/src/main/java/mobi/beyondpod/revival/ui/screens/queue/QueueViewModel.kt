package mobi.beyondpod.revival.ui.screens.queue

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import mobi.beyondpod.revival.data.local.dao.QueueSnapshotDao
import mobi.beyondpod.revival.data.local.entity.EpisodeEntity
import mobi.beyondpod.revival.data.local.entity.QueueSnapshotEntity
import mobi.beyondpod.revival.data.local.entity.QueueSnapshotItemEntity
import mobi.beyondpod.revival.data.repository.EpisodeRepository
import mobi.beyondpod.revival.service.PlaybackStateHolder
import javax.inject.Inject

data class QueueItem(
    val snapshotItem: QueueSnapshotItemEntity,
    val episode: EpisodeEntity?  // null = episode was deleted from DB; use snapshotItem fields as fallback
)

sealed interface QueueUiState {
    data object Empty : QueueUiState
    data class Active(
        val snapshot: QueueSnapshotEntity,
        val items: List<QueueItem>
    ) : QueueUiState
}

/**
 * ViewModel for QueueScreen.
 *
 * **Queue immutability rule (CLAUDE.md rule #1):**
 * ALL queue mutations go through [QueueSnapshotDao]. EpisodeEntity is NEVER modified
 * to reflect queue position or membership.
 *
 * - [removeItem] → [QueueSnapshotDao.removeAndCompact] (remove + compact positions + fix cursor)
 * - [reorderItems] → [QueueSnapshotDao.replaceActiveSnapshot] with reordered items
 * - [clearQueue] → [QueueSnapshotDao.deactivateAllSnapshots]
 *
 * The UI observes [uiState] which combines the active snapshot with its items and the
 * corresponding [EpisodeEntity] records (via [EpisodeRepository.getEpisodeById]).
 */
@HiltViewModel
class QueueViewModel @Inject constructor(
    private val queueSnapshotDao: QueueSnapshotDao,
    private val episodeRepository: EpisodeRepository
) : ViewModel() {

    val uiState: StateFlow<QueueUiState> = episodeRepository.getActiveQueueSnapshot()
        .flatMapLatest { snapshot ->
            if (snapshot == null) return@flatMapLatest flowOf(QueueUiState.Empty)
            queueSnapshotDao.getSnapshotItems(snapshot.id)
                .map { items ->
                    val queueItems = items.map { item ->
                        QueueItem(
                            snapshotItem = item,
                            episode = episodeRepository.getEpisodeById(item.episodeId)
                        )
                    }
                    // QE6: an active snapshot with 0 items should show Empty, not an empty Active state.
                    if (queueItems.isEmpty()) QueueUiState.Empty
                    else QueueUiState.Active(snapshot, queueItems)
                }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = QueueUiState.Empty
        )

    /**
     * Remove an episode from the active queue snapshot.
     *
     * Q6: positions are compacted after removal so no sparse gaps remain.
     * Q7: [QueueSnapshotDao.updateCurrentIndex] is called with the corrected cursor:
     * - removed item was before the playing item → decrement index by 1
     * - removed item IS the playing item        → clamp to min(old, remaining count - 1)
     * - removed item was after the playing item → index unchanged
     *
     * All three steps run atomically in [QueueSnapshotDao.removeAndCompact].
     */
    fun removeItem(episodeId: Long) {
        viewModelScope.launch {
            val state = uiState.value as? QueueUiState.Active
            val newCurrentIndex = if (state != null) {
                val items = state.items
                val removedPosition = items.indexOfFirst { it.snapshotItem.episodeId == episodeId }
                val currentIndex = state.snapshot.currentItemIndex
                val remainingCount = items.size - 1  // count after removal
                when {
                    removedPosition < 0 -> currentIndex  // not in list — no change
                    removedPosition < currentIndex -> currentIndex - 1
                    removedPosition == currentIndex -> minOf(currentIndex, remainingCount - 1).coerceAtLeast(0)
                    else -> currentIndex  // removed item was after current — unchanged
                }
            } else {
                0
            }
            queueSnapshotDao.removeAndCompact(listOf(episodeId), newCurrentIndex)
        }
    }

    /**
     * Reorder the queue after a drag-to-reorder gesture.
     *
     * Rebuilds the active snapshot atomically via [QueueSnapshotDao.replaceActiveSnapshot]
     * with the items in the new [reorderedItems] order. Positions are re-assigned 0-based.
     * NEVER writes to EpisodeEntity (CLAUDE.md rule #1).
     */
    fun reorderItems(reorderedItems: List<QueueItem>) {
        viewModelScope.launch {
            val state = uiState.value as? QueueUiState.Active ?: return@launch
            // Q5 (red-team verified): find the NEW index of the currently-playing episode
            // in the reordered list. Simply preserving the old index is wrong — the playing
            // episode may have moved position during the drag, so old index → wrong episode.
            val playingId = PlaybackStateHolder.currentlyPlayingEpisodeId
            val newIndex = if (playingId > 0) {
                reorderedItems.indexOfFirst { it.snapshotItem.episodeId == playingId }
                    .takeIf { it >= 0 } ?: state.snapshot.currentItemIndex
            } else {
                state.snapshot.currentItemIndex
            }
            val snapshot = state.snapshot.copy(
                id = 0,  // replaceActiveSnapshot assigns a new ID
                currentItemIndex = newIndex,
                currentItemPositionMs = state.snapshot.currentItemPositionMs
            )
            val items = reorderedItems.mapIndexed { index, qi ->
                qi.snapshotItem.copy(id = 0, snapshotId = 0, position = index)
            }
            queueSnapshotDao.replaceActiveSnapshot(snapshot, items)
        }
    }

    /**
     * Clear the entire queue. Deactivates all snapshots.
     * NEVER deletes episodes or modifies EpisodeEntity queue fields.
     */
    fun clearQueue() {
        viewModelScope.launch {
            queueSnapshotDao.deactivateAllSnapshots()
        }
    }
}
