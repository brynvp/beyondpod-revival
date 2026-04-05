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
 * - [removeItem] → [QueueSnapshotDao.removeItemsFromActiveSnapshot]
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
                    QueueUiState.Active(snapshot, queueItems)
                }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = QueueUiState.Empty
        )

    /**
     * Remove an episode from the active queue snapshot.
     * Calls [QueueSnapshotDao.removeItemsFromActiveSnapshot] — never touches EpisodeEntity.
     */
    fun removeItem(episodeId: Long) {
        viewModelScope.launch {
            queueSnapshotDao.removeItemsFromActiveSnapshot(listOf(episodeId))
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
            val snapshot = state.snapshot.copy(id = 0)  // replaceActiveSnapshot assigns a new ID
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
