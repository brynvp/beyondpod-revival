package mobi.beyondpod.revival.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "feeds",
    indices = [
        Index("primaryCategoryId"),   // §13 mandatory — feeds by category look-up
        Index("downloadStrategy")     // §13 mandatory — batch download strategy scans
    ]
)
data class FeedEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val url: String,                          // Canonical RSS/Atom URL
    val title: String,
    val description: String = "",
    val imageUrl: String? = null,
    val author: String = "",
    val website: String = "",
    val language: String = "",

    // IMPORTANT: Each feed can belong to UP TO 2 categories (BeyondPod original behaviour).
    // Primary category drives display position in navigator. Secondary is additive only.
    // Stored in FeedCategoryCrossRef join table — these fields are denormalised caches only.
    val primaryCategoryId: Long? = null,      // Drives navigator placement
    val secondaryCategoryId: Long? = null,    // Optional second category membership

    val isVirtualFeed: Boolean = false,       // True = virtual folder feed
    val virtualFeedFolderPath: String? = null,// Folder path for virtual feeds
    val sortOrder: Int = 0,                   // User-defined sort position within category
    val priority: Int = 0,                    // 0=Normal, 1=High — affects SmartPlay ordering tiebreaks

    // HTTP Authentication (per-feed for premium/paywalled feeds)
    // authPassword is NOT stored in FeedEntity — it lives in EncryptedSharedPreferences.
    val authUsername: String? = null,
    val hasAuthPassword: Boolean = false,

    // Update settings (per-feed, can override global)
    val autoUpdate: Boolean? = null,          // null = use global setting
    val updateIntervalMinutes: Int? = null,   // null = use category/global
    val updateSchedule: String? = null,       // JSON: scheduled update times

    // Download settings (per-feed)
    val downloadStrategy: DownloadStrategy = DownloadStrategy.GLOBAL,
    val downloadCount: Int? = null,           // null = use global setting
    val maxEpisodesToKeep: Int? = null,       // null = use global; 0 = keep all
    val downloadOnlyOnWifi: Boolean? = null,  // null = use global
    val allowCleanupForManual: Boolean = false,

    // Playback settings (per-feed override)
    val playbackSpeed: Float? = null,         // null = use global; range 0.5x–4.0x
    val skipIntroSeconds: Int = 0,            // Skip first N seconds of every episode
    val skipOutroSeconds: Int = 0,            // Skip last N seconds
    // Volume boost — implemented via Android LoudnessEnhancer (NOT player.volume > 1.0f).
    // 0 = use global setting, 1 = no boost (0dB), 2–10 = increasing gain (~1dB per step, max 10dB).
    val playbackVolumeBoost: Int = 0,

    // Episode sort order override (null = use global preference)
    val episodeSortOrder: EpisodeSortOrder? = null,

    // Display
    val displayType: Int = 0,                 // 0=Card Type 1, 1=Card Type 2, 2=Card Type 3
    val showDescription: Boolean = true,
    val useCustomImage: Boolean = false,
    val customImagePath: String? = null,

    // Feed state
    val lastUpdated: Long = 0L,               // epoch millis
    val lastUpdateFailed: Boolean = false,
    val lastUpdateError: String? = null,
    val useGoogleProxy: Boolean = false,

    // Episode retention / age limits (per-feed, override global)
    // 99999 = keep forever (BeyondPod's sentinel value — confirmed in real DB).
    val maxTrackAgeDays: Int = 99999,

    // Feed fingerprinting / duplicate detection method
    //   -1 = virtual folder feed
    //    1 = hash-based fingerprinting
    //    2 = GUID-based fingerprinting (default for RSS feeds)
    val fingerprintType: Int = 2,

    // speedIndex: 0-based index into speed lookup table; -1 = use global
    val audioSettingsSpeedIndex: Int = -1,

    // Tracking
    val createdAt: Long = System.currentTimeMillis(),
    val isHidden: Boolean = false,

    // gpodder sync
    val gpodderSubscribed: Boolean = true
)

/**
 * Download strategy controls how this feed's episodes are auto-downloaded.
 *
 * GLOBAL            — inherit from global/category settings
 * DOWNLOAD_NEWEST   — always get the N most recent episodes (good for news feeds).
 * DOWNLOAD_IN_ORDER — start with OLDEST episodes and work forward (good for serial content).
 * STREAM_NEWEST     — create streamable references instead of downloading files.
 * MANUAL            — no automatic downloading; user triggers all downloads manually.
 */
enum class DownloadStrategy {
    GLOBAL, DOWNLOAD_NEWEST, DOWNLOAD_IN_ORDER, STREAM_NEWEST, MANUAL
}
