package mobi.beyondpod.revival.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Records subscription changes (subscribe/unsubscribe) for gPodder sync diff upload.
 * Changes accumulate until next sync, then are uploaded and cleared.
 *
 * changeType: "ADD" = subscribed, "REMOVE" = unsubscribed
 */
@Entity(tableName = "change_history")
data class ChangeHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val feedUrl: String,
    val changeType: String,                   // "ADD" | "REMOVE"
    val changedAt: Long = System.currentTimeMillis(),
    val syncedAt: Long? = null                // null = pending upload; set when successfully synced
)
