package mobi.beyondpod.revival.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Stores user-defined scheduled update tasks separate from WorkManager's periodic constraints.
 * Revival delegates actual execution to WorkManager, but stores user intent here so it can be
 * preserved across WorkManager cancellations (e.g., app updates, device reboots).
 *
 * taskType: "FEED_UPDATE" | "CATEGORY_UPDATE" | "FULL_UPDATE"
 * cronExpression: standard 5-field cron (e.g., "0 6 * * *" = daily at 06:00)
 */
@Entity(tableName = "scheduled_tasks")
data class ScheduledTaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val taskType: String = "FULL_UPDATE",
    val targetId: Long? = null,               // feedId or categoryId; null = all feeds
    val cronExpression: String? = null,       // null = use global update interval from settings
    val isEnabled: Boolean = true,
    val lastExecutedAt: Long? = null,
    val workManagerTag: String? = null        // WorkManager unique work name for cancellation
)
