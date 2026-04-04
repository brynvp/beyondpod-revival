package mobi.beyondpod.revival.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Audit log of play events. One row per play/pause/complete event.
 * Maps to the original BeyondPod `episode_history` table.
 * Used for:
 *   - "Recently Played" lists
 *   - gPodder sync episode actions (play/pause/complete must be timestamped)
 *
 * entryType values:
 *   0 = PLAY_START   — user started playback
 *   1 = PLAY_PAUSE   — user paused
 *   2 = PLAY_END     — reached end of episode (PLAYED state transition)
 *   3 = PLAY_SEEK    — user seeked
 *
 * NOTE: episodeId is NOT a FK — history entries must survive episode deletion.
 */
@Entity(
    tableName = "episode_history",
    indices = [Index("episodeId"), Index("eventTimestamp")]
)
data class EpisodePlayHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val episodeId: Long,                      // References episodes.id (NOT a FK)
    val episodeGuid: String,                  // Cached GUID for gPodder sync matching
    val feedUrl: String,                      // Cached feed URL for gPodder sync
    val episodeUrl: String,                   // Cached episode URL for gPodder sync
    val eventTimestamp: Long = System.currentTimeMillis(),
    val entryType: Int = 0,                   // 0=START, 1=PAUSE, 2=END, 3=SEEK
    val positionMs: Long = 0L                 // Playback position at time of event (millis)
)
