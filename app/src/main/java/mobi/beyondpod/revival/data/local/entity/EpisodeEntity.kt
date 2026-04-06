package mobi.beyondpod.revival.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "episodes",
    foreignKeys = [ForeignKey(
        entity = FeedEntity::class,
        parentColumns = ["id"],
        childColumns = ["feedId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [
        Index("feedId"),
        Index("guid"),
        Index("pubDate"),
        Index("playState"),
        // Compound indices for efficient feed-scoped queries (§13 mandatory)
        Index(value = ["feedId", "pubDate"]),
        Index(value = ["feedId", "playState"]),
        Index(value = ["feedId", "downloadState"])
    ]
)
data class EpisodeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val feedId: Long,
    val guid: String,                         // RSS GUID — uniqueness key
    val title: String,
    val description: String = "",
    val htmlDescription: String = "",         // Raw HTML show notes
    val pubDate: Long = 0L,                   // epoch millis
    val url: String,                          // Enclosure / stream URL
    val mimeType: String = "audio/mpeg",
    val fileSizeBytes: Long = 0L,
    val duration: Long = 0L,                  // millis, 0 = unknown
    val imageUrl: String? = null,             // Episode artwork (overrides feed art)
    val author: String = "",
    val chapterUrl: String? = null,           // Podcast namespace chapters URL
    val transcriptUrl: String? = null,

    // Playback state
    // ARCHIVED = episode no longer appears in feed XML but was previously downloaded/known.
    val playState: PlayState = PlayState.NEW,
    val playPosition: Long = 0L,              // millis resume position (saved every 5s + on pause)
    val playedFraction: Float = 0f,           // 0.0–1.0 — used for "Played Portion" sort order
    val playCount: Int = 0,
    val lastPlayed: Long? = null,
    val isStarred: Boolean = false,           // "Favourite" in v4+
    // isProtected = the original BeyondPod "locked" flag.
    // TRUE = never auto-delete under any circumstances, regardless of cleanup rules or episode age.
    // Overrides ALL cleanup policies. No exceptions.
    val isProtected: Boolean = false,
    val isArchived: Boolean = false,          // Episode no longer in feed; local copy still exists
    val lastAccessed: Long? = null,

    // Download state
    val downloadState: DownloadStateEnum = DownloadStateEnum.NOT_DOWNLOADED,
    val localFilePath: String? = null,
    val downloadedAt: Long? = null,
    val downloadId: Long? = null,             // WorkManager job ID
    val downloadProgress: Int = 0,            // 0–100
    // Partial download tracking — enables resume via HTTP Range header.
    // downloadBytesDownloaded: exact bytes written to disk so far.
    // On resume: send `Range: bytes={downloadBytesDownloaded}-` header.
    val downloadBytesDownloaded: Long = 0L,
    val downloadTotalBytes: Long = 0L,        // 0 if unknown (server didn't provide Content-Length)

    // INTENTIONALLY NO isInQueue / queuePosition FIELDS ON THIS ENTITY.
    // The active queue is owned exclusively by QueueSnapshotEntity + QueueSnapshotItemEntity.
    // To determine whether an episode is in the current queue, query the active snapshot.

    // Metadata
    val isInMyEpisodes: Boolean = false,
    val addedToMyEpisodes: Long? = null,
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * Episode play states. All transitions are reversible.
 *   NEW → IN_PROGRESS (playback starts)
 *   IN_PROGRESS → PLAYED (playback reaches ≥90% or user marks played)
 *   IN_PROGRESS → NEW (user marks unplayed)
 *   PLAYED → NEW (user marks unplayed)
 *   ANY → SKIPPED (user explicitly skips without playing)
 */
enum class PlayState { NEW, IN_PROGRESS, PLAYED, SKIPPED }

enum class DownloadStateEnum {
    NOT_DOWNLOADED,
    QUEUED,           // In WorkManager queue, not started
    DOWNLOADING,      // Actively downloading (progress tracked)
    DOWNLOADED,       // Complete, localFilePath is valid
    FAILED,           // Last attempt failed; retryCount tracked in DownloadQueueEntity
    DELETED           // Was downloaded, file explicitly deleted; record kept for history
}
