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
    // Get the active snapshot
    @Query("SELECT * FROM queue_snapshots WHERE isActive = 1 LIMIT 1")
    fun getActiveSnapshot(): Flow<QueueSnapshotEntity?>

    @Query("""
        SELECT * FROM queue_snapshot_items
        WHERE snapshotId = :snapshotId
        ORDER BY position ASC
    """)
    fun getSnapshotItems(snapshotId: Long): Flow<List<QueueSnapshotItemEntity>>

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

    // Reorder: update position value for a single item
    @Query("UPDATE queue_snapshot_items SET position = :position WHERE id = :itemId")
    suspend fun updateItemPosition(itemId: Long, position: Int)
}
