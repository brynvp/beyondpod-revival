package mobi.beyondpod.revival.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import mobi.beyondpod.revival.data.local.entity.QueueSnapshotEntity
import mobi.beyondpod.revival.data.local.entity.QueueSnapshotItemEntity

/**
 * All queue mutations go through this DAO. EpisodeDao must never write queue state.
 * The queue is ALWAYS a snapshot — never a live query over episodes.
 */
@Dao
interface QueueSnapshotDao {
    // Get the active snapshot (reactive)
    @Query("SELECT * FROM queue_snapshots WHERE isActive = 1 LIMIT 1")
    fun getActiveSnapshot(): Flow<QueueSnapshotEntity?>

    // One-shot read for backup/export
    @Query("SELECT * FROM queue_snapshots WHERE isActive = 1 LIMIT 1")
    suspend fun getActiveSnapshotOnce(): QueueSnapshotEntity?

    @Query("""
        SELECT * FROM queue_snapshot_items
        WHERE snapshotId = :snapshotId
        ORDER BY position ASC
    """)
    fun getSnapshotItems(snapshotId: Long): Flow<List<QueueSnapshotItemEntity>>

    @Query("""
        SELECT * FROM queue_snapshot_items
        WHERE snapshotId = :snapshotId
        ORDER BY position ASC
    """)
    suspend fun getSnapshotItemsList(snapshotId: Long): List<QueueSnapshotItemEntity>

    // Atomic snapshot replacement — deactivate all old snapshots, insert new one + items
    @Transaction
    suspend fun replaceActiveSnapshot(
        snapshot: QueueSnapshotEntity,
        items: List<QueueSnapshotItemEntity>
    ) {
        deactivateAllSnapshots()
        val newId = insertSnapshot(snapshot)
        insertItems(items.map { it.copy(snapshotId = newId) })
    }

    @Query("UPDATE queue_snapshots SET isActive = 0")
    suspend fun deactivateAllSnapshots()

    @Insert
    suspend fun insertSnapshot(snapshot: QueueSnapshotEntity): Long

    @Insert
    suspend fun insertItems(items: List<QueueSnapshotItemEntity>)

    // Update current position pointer (called every 5s during playback)
    @Query("""
        UPDATE queue_snapshots
        SET currentItemIndex = :index, currentItemPositionMs = :positionMs
        WHERE isActive = 1
    """)
    suspend fun updatePlaybackPosition(index: Int, positionMs: Long)

    // Remove specific episodes from the active snapshot (e.g., user swipes to remove)
    @Query("""
        DELETE FROM queue_snapshot_items
        WHERE snapshotId = (SELECT id FROM queue_snapshots WHERE isActive = 1 LIMIT 1)
        AND episodeId IN (:episodeIds)
    """)
    suspend fun removeItemsFromActiveSnapshot(episodeIds: List<Long>)

    // Q6: Renumber positions after a removal so they are contiguous (0, 1, 2, …).
    // The correlated subquery counts items with a lower position in the same snapshot —
    // that count is exactly the new 0-based position for each surviving item.
    @Query("""
        UPDATE queue_snapshot_items
        SET position = (
            SELECT COUNT(*) FROM queue_snapshot_items i2
            WHERE i2.snapshotId = queue_snapshot_items.snapshotId
              AND i2.position < queue_snapshot_items.position
        )
        WHERE snapshotId = (SELECT id FROM queue_snapshots WHERE isActive = 1 LIMIT 1)
    """)
    suspend fun compactPositions()

    // Q7: Update the current-item cursor in the active snapshot.
    // Called after a removal shifts the playing item's effective index.
    @Query("UPDATE queue_snapshots SET currentItemIndex = :index WHERE isActive = 1")
    suspend fun updateCurrentIndex(index: Int)

    /**
     * Q6+Q7: Atomic remove → compact → index-fix.
     *
     * [newCurrentIndex] is pre-computed by the caller (QueueViewModel), which has full
     * knowledge of the pre-removal snapshot state:
     * - removed item was before current  → decrement index by 1
     * - removed item was the current     → clamp to min(old, remaining count - 1)
     * - removed item was after current   → unchanged
     */
    @Transaction
    suspend fun removeAndCompact(episodeIds: List<Long>, newCurrentIndex: Int) {
        removeItemsFromActiveSnapshot(episodeIds)
        compactPositions()
        updateCurrentIndex(newCurrentIndex)
    }

    // Reorder: update position value for a single item
    @Query("UPDATE queue_snapshot_items SET position = :position WHERE id = :itemId")
    suspend fun updateItemPosition(itemId: Long, position: Int)
}
