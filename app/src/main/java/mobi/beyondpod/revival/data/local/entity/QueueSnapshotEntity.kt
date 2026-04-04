package mobi.beyondpod.revival.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A queue snapshot is an immutable ordered list of episode IDs generated at a point in time.
 *
 * DESIGN PRINCIPLE (§0.1): The active queue is NEVER a live database query. It is always
 * a frozen snapshot. Feed updates, new downloads, and rule changes DO NOT affect the
 * in-progress queue. The user must explicitly trigger "Regenerate Queue" to rebuild it.
 *
 * There is only ever ONE active snapshot (the currently playing queue).
 * Previous snapshots are deleted when a new one is generated.
 */
@Entity(tableName = "queue_snapshots")
data class QueueSnapshotEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourcePlaylistId: Long? = null,       // Which SmartPlaylist generated this (null = manual)
    val generatedAt: Long = System.currentTimeMillis(),
    val isActive: Boolean = true,             // Only one snapshot is active at a time
    val currentItemIndex: Int = 0,            // Index into QueueSnapshotItemEntity list
    val currentItemPositionMs: Long = 0L      // Playback position in current item
)
