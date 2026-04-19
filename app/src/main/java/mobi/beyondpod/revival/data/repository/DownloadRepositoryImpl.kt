package mobi.beyondpod.revival.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import mobi.beyondpod.revival.data.local.dao.EpisodeDao
import mobi.beyondpod.revival.data.local.dao.FeedDao
import mobi.beyondpod.revival.data.local.entity.DownloadStateEnum
import mobi.beyondpod.revival.data.local.entity.DownloadStrategy
import mobi.beyondpod.revival.data.local.entity.EpisodeEntity
import mobi.beyondpod.revival.data.settings.AppSettings
import mobi.beyondpod.revival.service.DownloadWorker
import java.io.File
import javax.inject.Inject

class DownloadRepositoryImpl @Inject constructor(
    private val episodeDao: EpisodeDao,
    private val feedDao: FeedDao,
    private val workManager: WorkManager,
    private val dataStore: DataStore<Preferences>
) : DownloadRepository {

    /**
     * Resolves effective retention limit: per-feed override first, then global setting.
     * Returns null when retention is unlimited (value = 0).
     */
    private suspend fun effectiveKeepCount(perFeedSetting: Int?): Int? {
        if (perFeedSetting != null) return if (perFeedSetting == 0) null else perFeedSetting
        val global = dataStore.data.first()[AppSettings.GLOBAL_MAX_KEEP] ?: 5
        return if (global == 0) null else global
    }

    /**
     * Delete files for episodes beyond the retention limit.
     * [allDownloaded] must be ordered newest-first (pubDate DESC).
     * Keeps the first [keepCount] episodes; soft-deletes the rest (file removed, state = DELETED).
     * isProtected is enforced by the DAO query — no protected episode ever appears in the list.
     */
    private suspend fun applyRetentionCleanup(allDownloaded: List<EpisodeEntity>, keepCount: Int) {
        val toDelete = allDownloaded.drop(keepCount)   // drop newest keepCount, delete the rest
        for (ep in toDelete) {
            ep.localFilePath?.let { path ->
                val file = File(path)
                if (file.exists()) file.delete()
            }
            episodeDao.updateDownloadState(ep.id, DownloadStateEnum.DELETED, null)
        }
    }

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
     * CRITICAL ordering: cleanup ALWAYS runs before new downloads (§7.7 + §9, rule #8).
     *
     * Retention removes BOTH played AND unplayed files beyond the limit (user confirmed).
     * Effective keepCount = per-feed override ?? global GLOBAL_MAX_KEEP (0 = unlimited).
     * isProtected episodes are NEVER deleted (rule #4, enforced by DAO query).
     */
    override suspend fun autoDownloadNewEpisodes(feedId: Long) {
        val feed = feedDao.getFeedById(feedId) ?: return
        val effectiveStrategy = feed.downloadStrategy
        val keepCount = effectiveKeepCount(feed.maxEpisodesToKeep)
        val downloadCount = feed.downloadCount ?: 3

        when (effectiveStrategy) {
            DownloadStrategy.DOWNLOAD_NEWEST -> {
                // ── Step 1: Cleanup FIRST (rule #8) ────────────────────────
                if (keepCount != null) {
                    val allDownloaded = episodeDao.getAllDownloadedNonProtected(feedId)
                    applyRetentionCleanup(allDownloaded, keepCount)
                }

                // ── Step 2: Enqueue all outstanding new downloads ──────────
                // Download ALL not-yet-downloaded episodes (not just one) per user requirement.
                val toDownload = episodeDao.getNotDownloadedNewest(feedId, downloadCount)
                toDownload.forEach { ep -> enqueueDownload(ep.id) }
            }

            DownloadStrategy.DOWNLOAD_IN_ORDER -> {
                // No automatic cleanup for serial content; oldest-first ordering preserved.
                val toDownload = episodeDao.getNotDownloadedOldest(feedId, downloadCount)
                toDownload.forEach { ep -> enqueueDownload(ep.id) }
            }

            DownloadStrategy.STREAM_NEWEST -> {
                // No file download — stream only; mark QUEUED so PlaybackService streams.
                val toQueue = episodeDao.getNotDownloadedNewest(feedId, downloadCount)
                toQueue.forEach { ep ->
                    episodeDao.updateDownloadState(ep.id, DownloadStateEnum.QUEUED, null)
                }
            }

            DownloadStrategy.MANUAL -> {
                // No automatic downloads; cleanup only if feed explicitly permits it.
                if (feed.allowCleanupForManual && keepCount != null) {
                    val allDownloaded = episodeDao.getAllDownloadedNonProtected(feedId)
                    applyRetentionCleanup(allDownloaded, keepCount)
                }
            }

            DownloadStrategy.GLOBAL -> {
                // Same as DOWNLOAD_NEWEST — uses global defaults already resolved above.
                if (keepCount != null) {
                    val allDownloaded = episodeDao.getAllDownloadedNonProtected(feedId)
                    applyRetentionCleanup(allDownloaded, keepCount)
                }
                val toDownload = episodeDao.getNotDownloadedNewest(feedId, downloadCount)
                toDownload.forEach { ep -> enqueueDownload(ep.id) }
            }
        }
    }
}
