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
     */
    suspend fun autoDownloadNewEpisodes(feedId: Long)
}
