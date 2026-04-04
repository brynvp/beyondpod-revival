package mobi.beyondpod.revival.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "categories")
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val color: Int = 0xFF2196F3.toInt(),      // ARGB
    val sortOrder: Int = 0,
    val isExpanded: Boolean = true,           // In nav drawer
    val parentCategoryId: Long? = null,       // For nested categories (future)

    // Per-category update schedule (overrides global)
    val autoUpdate: Boolean = true,
    val updateIntervalMinutes: Int? = null,   // null = use global
    val updateSchedule: String? = null,       // JSON schedule
    val downloadOnlyOnWifi: Boolean? = null,

    // Category-level auto-download defaults
    val autoDownload: Boolean? = null,
    val downloadCount: Int? = null,
    val maxEpisodesToKeep: Int? = null
)
