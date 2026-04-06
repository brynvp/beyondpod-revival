package mobi.beyondpod.revival.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One item in a queue snapshot.
 *
 * FILE-BACKED QUEUE RESILIENCE (QA finding #2):
 * localFilePathSnapshot caches the file path at snapshot generation time, replicating the
 * original BeyondPod queue durability. Playback fallback in PlaybackService:
 *   1. Resolve episodeId → EpisodeEntity → localFilePath (happy path)
 *   2. If episode not found OR localFilePath missing: try localFilePathSnapshot
 *   3. If both fail: mark item unplayable, skip to next, show "File not available" snackbar
 *
 * NOTE: episodeId is NOT a FK — the episode may have been deleted since the snapshot was taken.
 * The snapshot must survive episode/feed deletion to maintain queue integrity.
 */
@Entity(
    tableName = "queue_snapshot_items",
    foreignKeys = [ForeignKey(
        entity = QueueSnapshotEntity::class,
        parentColumns = ["id"],
        childColumns = ["snapshotId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [
        Index("snapshotId"),
        Index("position"),
        Index(value = ["snapshotId", "position"])   // §13: compound for ordered snapshot reads
    ]
)
data class QueueSnapshotItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val snapshotId: Long,
    val episodeId: Long,                      // References episodes.id (NOT a FK — episode may be deleted)
    val position: Int,                        // 0-based order in queue
    val episodeTitleSnapshot: String = "",    // Cached title — survives episode deletion/metadata change
    val feedTitleSnapshot: String = "",       // Cached feed title — survives feed deletion
    val localFilePathSnapshot: String? = null,// Cached file path at snapshot creation time.
                                              // null for stream-only (no local file) episodes.
    val episodeUrlSnapshot: String = ""       // Cached stream URL — fallback for stream-only episodes
)
