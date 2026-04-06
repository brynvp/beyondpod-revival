package mobi.beyondpod.revival.data.repository

import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.flow.Flow
import mobi.beyondpod.revival.data.local.dao.EpisodeDao
import mobi.beyondpod.revival.data.local.dao.FeedDao
import mobi.beyondpod.revival.data.local.entity.DownloadQueueEntity
import mobi.beyondpod.revival.data.local.entity.DownloadStateEnum
import mobi.beyondpod.revival.data.local.entity.DownloadStrategy
import mobi.beyondpod.revival.data.local.entity.EpisodeEntity
import mobi.beyondpod.revival.service.DownloadWorker
import java.io.File
import javax.inject.Inject

class DownloadRepositoryImpl @Inject constructor(
    private val episodeDao: EpisodeDao,
    private val feedDao: FeedDao,
    private val workManager: WorkManager
) : DownloadRepository {

    override suspend fun enqueueDownload(episodeId: Long) {
        episodeDao.updateDownloadState(episodeId, DownloadStateEnum.QUEUED, null)
        workManager.enqueueUniqueWork(
            "download_$episodeId",
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<DownloadWorker>()
                .setInputData(workDataOf(DownloadWorker.KEY_EPISODE_ID to episodeId))
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
        )
    }

    override suspend fun cancelDownload(episodeId: Long) {
        workManager.cancelUniqueWork("download_$episodeId")
        episodeDao.updateDownloadState(episodeId, DownloadStateEnum.NOT_DOWNLOADED, null)
    }

    override fun getDownloadedEpisodes(): Flow<List<EpisodeEntity>> =
        episodeDao.getDownloadedEpisodes()

    /**
     * Delete a downloaded file and mark its episode as DELETED.
     * isProtected is an absolute veto — refuses with failure result (CLAUDE.md rule #4).
     */
    override suspend fun deleteDownload(episodeId: Long): Result<Unit> = runCatching {
        val episode = episodeDao.getEpisodeById(episodeId)
            ?: throw NoSuchElementException("Episode $episodeId not found")
        require(!episode.isProtected) {
            "Cannot delete download for protected episode $episodeId"
        }
        episode.localFilePath?.let { path ->
            val file = File(path)
            if (file.exists()) file.delete()
        }
        episodeDao.updateDownloadState(episodeId, DownloadStateEnum.DELETED, null)
    }

    override suspend fun batchEnqueueDownloads(episodeIds: List<Long>) {
        episodeIds.forEach { enqueueDownload(it) }
    }

    /**
     * Batch delete downloads. Protected episodes are silently skipped per the
     * isProtected absolute veto rule. Returns the count of episodes actually deleted.
     */
    override suspend fun batchDeleteDownloads(episodeIds: List<Long>): Result<Int> = runCatching {
        var deleted = 0
        for (id in episodeIds) {
            val episode = episodeDao.getEpisodeById(id) ?: continue
            if (episode.isProtected) continue   // absolute veto — skip silently
            episode.localFilePath?.let { path ->
                val file = File(path)
                if (file.exists()) file.delete()
            }
            episodeDao.updateDownloadState(id, DownloadStateEnum.DELETED, null)
            deleted++
        }
        deleted
    }

    /**
     * Apply auto-download rules for a feed after a feed refresh.
     *
     * CRITICAL ordering: cleanup ALWAYS runs before new downloads (§7.7 + §9).
     *
     * isProtected episodes are NEVER deleted during cleanup (CLAUDE.md rule #4).
     * WorkManager job creation is stubbed until Phase 3.
     */
    override suspend fun autoDownloadNewEpisodes(feedId: Long) {
        val feed = feedDao.getFeedById(feedId) ?: return
        val effectiveStrategy = feed.downloadStrategy
        val keepCount = feed.maxEpisodesToKeep ?: Int.MAX_VALUE
        val downloadCount = feed.downloadCount ?: 3

        when (effectiveStrategy) {
            DownloadStrategy.DOWNLOAD_NEWEST -> {
                // ── Step 1: Cleanup FIRST ───────────────────────────────────
                if (keepCount != Int.MAX_VALUE && keepCount > 0) {
                    val played = episodeDao.getPlayedDownloadedForCleanup(feedId)
                    // Determine how many downloaded episodes exist
                    // Keep the newest keepCount, delete the rest (already sorted oldest-first).
                    val toDelete = played.dropLast(keepCount.coerceAtLeast(0))
                    for (ep in toDelete) {
                        // isProtected check already enforced by the query (isProtected = 0)
                        ep.localFilePath?.let { path ->
                            val file = File(path)
                            if (file.exists()) file.delete()
                        }
                        episodeDao.updateDownloadState(ep.id, DownloadStateEnum.DELETED, null)
                    }
                }

                // ── Step 2: Enqueue new downloads ─────────────────────────
                val toDownload = episodeDao.getNotDownloadedNewest(feedId, downloadCount)
                toDownload.forEach { ep -> enqueueDownload(ep.id) }
            }

            DownloadStrategy.DOWNLOAD_IN_ORDER -> {
                // No automatic cleanup for serial content
                val toDownload = episodeDao.getNotDownloadedOldest(feedId, downloadCount)
                toDownload.forEach { ep -> enqueueDownload(ep.id) }
            }

            DownloadStrategy.STREAM_NEWEST -> {
                // No file download — stream only; mark QUEUED so PlaybackService streams
                val toQueue = episodeDao.getNotDownloadedNewest(feedId, downloadCount)
                toQueue.forEach { ep ->
                    episodeDao.updateDownloadState(ep.id, DownloadStateEnum.QUEUED, null)
                }
            }

            DownloadStrategy.MANUAL -> {
                // No automatic action; optional cleanup if allowCleanupForManual is set
                if (feed.allowCleanupForManual && keepCount != Int.MAX_VALUE && keepCount > 0) {
                    val played = episodeDao.getPlayedDownloadedForCleanup(feedId)
                    val toDelete = played.dropLast(keepCount.coerceAtLeast(0))
                    for (ep in toDelete) {
                        ep.localFilePath?.let { path ->
                            val file = File(path)
                            if (file.exists()) file.delete()
                        }
                        episodeDao.updateDownloadState(ep.id, DownloadStateEnum.DELETED, null)
                    }
                }
            }

            DownloadStrategy.GLOBAL -> {
                // Treat the same as DOWNLOAD_NEWEST with default count
                val toDownload = episodeDao.getNotDownloadedNewest(feedId, downloadCount)
                toDownload.forEach { ep -> enqueueDownload(ep.id) }
            }
        }
    }
}
