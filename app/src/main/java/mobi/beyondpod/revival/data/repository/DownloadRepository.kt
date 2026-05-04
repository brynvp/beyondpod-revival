package mobi.beyondpod.revival.data.repository

import kotlinx.coroutines.flow.Flow
import mobi.beyondpod.revival.data.local.entity.EpisodeEntity

interface DownloadRepository {
    /** Mark episode as QUEUED. WorkManager job is enqueued in Phase 3. */
    suspend fun enqueueDownload(episodeId: Long)

    /** Cancel an in-progress or queued download. */
    suspend fun cancelDownload(episodeId: Long)

    fun getDownloadedEpisodes(): Flow<List<EpisodeEntity>>

    /**
     * Delete the downloaded file and set downloadState = DELETED.
     * Returns failure if [episodeId] has isProtected = true (absolute veto — no exceptions).
     */
    suspend fun deleteDownload(episodeId: Long): Result<Unit>

    suspend fun batchEnqueueDownloads(episodeIds: List<Long>)

    /**
     * Delete multiple downloads. Protected episodes are silently skipped.
     * Returns the count of episodes actually deleted.
     */
    suspend fun batchDeleteDownloads(episodeIds: List<Long>): Result<Int>

    /**
     * Apply the auto-download rule for a feed after a feed refresh.
     * Cleanup always runs BEFORE new downloads are enqueued (§7.7, §9 rule).
     * isProtected episodes are never deleted during cleanup.
     *
     * [isManualRefresh] — when true (user explicitly triggered refresh), the per-cycle
     * downloadCount cap is bypassed so all available new episodes are enqueued. This
     * prevents the "pull multiple times to catch up" problem after a restore or first-run.
     * Background scheduled runs pass false (default) to preserve the configured limit.
     */
    suspend fun autoDownloadNewEpisodes(feedId: Long, isManualRefresh: Boolean = false, mobileAllowed: Boolean = false)

    /**
     * Returns true when the user has WiFi-only enabled (per-feed or global) but the device
     * is currently on mobile data. The caller should show a confirmation dialog before
     * proceeding with downloads and pass [mobileAllowed]=true if the user approves.
     *
     * Always returns false for background/worker callers — the check is only meaningful
     * for manual user-triggered refreshes.
     */
    suspend fun checkMobileDownloadBlocked(feedId: Long): Boolean

    /**
     * Apply age and retention-count cleanup across ALL feeds immediately, using the current
     * global settings. Does NOT enqueue any new downloads — cleanup only.
     * isProtected episodes are never deleted (rule #4).
     * Returns the total number of episode files deleted.
     *
     * Call this when the user changes global retention settings or taps "Clean up now".
     */
    suspend fun runGlobalRetentionCleanup(): Int
}
