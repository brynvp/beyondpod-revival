package mobi.beyondpod.revival.data.repository

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import mobi.beyondpod.revival.data.local.dao.EpisodeDao
import mobi.beyondpod.revival.data.local.dao.FeedDao
import mobi.beyondpod.revival.data.local.entity.DownloadStateEnum
import mobi.beyondpod.revival.data.local.entity.DownloadStrategy
import mobi.beyondpod.revival.data.local.entity.EpisodeEntity
import mobi.beyondpod.revival.data.settings.AppSettings
import java.io.File
import javax.inject.Inject

class DownloadRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val episodeDao: EpisodeDao,
    private val feedDao: FeedDao,
    private val downloadManager: DownloadManager,
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
     * Resolves effective auto-download count: per-feed override first, then global DataStore setting.
     * Returns 0 when auto-download is disabled.
     */
    private suspend fun effectiveDownloadCount(perFeedSetting: Int?): Int {
        if (perFeedSetting != null) return perFeedSetting
        return dataStore.data.first()[AppSettings.GLOBAL_DOWNLOAD_COUNT] ?: 1
    }

    /**
     * Resolves effective age cutoff in ms, or null if never (99999 days).
     * Returns null when "delete older than" is set to 99999 (never).
     */
    private suspend fun effectiveDeleteOlderThanCutoffMs(): Long? {
        val days = dataStore.data.first()[AppSettings.GLOBAL_DELETE_OLDER_THAN_DAYS] ?: 99999
        if (days >= 99999) return null
        return System.currentTimeMillis() - (days.toLong() * 86_400_000L)
    }

    /**
     * Delete files for episodes beyond the retention limit.
     * [allDownloaded] must be ordered newest-first (pubDate DESC).
     * Keeps the first [keepCount] episodes; soft-deletes the rest (file removed, state = DELETED).
     * isProtected is enforced by the DAO query — no protected episode ever appears in the list.
     */
    private suspend fun applyRetentionCleanup(allDownloaded: List<EpisodeEntity>, keepCount: Int) {
        val toDelete = allDownloaded.drop(keepCount)
        for (ep in toDelete) {
            ep.localFilePath?.let { path ->
                val file = File(path)
                if (file.exists()) file.delete()
            }
            episodeDao.updateDownloadState(ep.id, DownloadStateEnum.DELETED, null)
        }
    }

    /**
     * Enqueue a download via Android's [DownloadManager] system service.
     *
     * The download runs entirely in the Android system process — no foreground service,
     * no WorkManager worker, no risk of ANR or app responsiveness impact. The system
     * shows its own download notification and handles retries, redirects, and resume.
     *
     * The returned system download ID is stored in [EpisodeEntity.downloadId] so we can
     * cancel or query the download later, and so [DownloadCompleteReceiver] can match
     * the completion broadcast back to the episode.
     */
    override suspend fun enqueueDownload(episodeId: Long) {
        val episode = episodeDao.getEpisodeById(episodeId) ?: return

        // Build destination path — same location as the old custom worker used
        val safeTitle = episode.title.take(64).replace(Regex("[^A-Za-z0-9._\\- ]"), "_")
        val ext = episode.url.substringAfterLast('.').substringBefore('?').lowercase()
            .let { if (it.length in 2..4 && it.all { c -> c.isLetter() }) it else "mp3" }
        val dir = File(context.getExternalFilesDir(null), "podcasts/${episode.feedId}")
            .also { it.mkdirs() }
        val outputFile = File(dir, "$safeTitle.$ext")

        val request = DownloadManager.Request(Uri.parse(episode.url))
            .setTitle(episode.title)
            .setDescription(context.getString(android.R.string.unknownName).let { "BeyondPod" })
            .setMimeType(episode.mimeType.ifEmpty { "audio/mpeg" })
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationUri(Uri.fromFile(outputFile))
            .addRequestHeader("User-Agent", "BeyondPodRevival/5.0")

        val dmId = downloadManager.enqueue(request)
        episodeDao.updateDownloadIdAndState(episodeId, dmId, DownloadStateEnum.DOWNLOADING)
    }

    override suspend fun cancelDownload(episodeId: Long) {
        val episode = episodeDao.getEpisodeById(episodeId)
        episode?.downloadId?.let { dmId -> downloadManager.remove(dmId) }
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
            if (episode.isProtected) continue
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
     * CRITICAL ordering (rule #8): cleanup ALWAYS runs before new downloads.
     *   Step A — age cleanup: delete episodes older than global threshold (isProtected veto applies)
     *   Step B — retention count cleanup: keep only newest N (isProtected veto applies)
     *   Step C — enqueue new downloads
     *
     * Effective downloadCount = per-feed override ?? global GLOBAL_DOWNLOAD_COUNT.
     * Effective keepCount = per-feed override ?? global GLOBAL_MAX_KEEP (0 = unlimited).
     * isProtected episodes are NEVER deleted (rule #4, enforced by DAO query).
     */
    override suspend fun autoDownloadNewEpisodes(feedId: Long) {
        val feed = feedDao.getFeedById(feedId) ?: return
        val effectiveStrategy = feed.downloadStrategy
        val keepCount = effectiveKeepCount(feed.maxEpisodesToKeep)
        val downloadCount = effectiveDownloadCount(feed.downloadCount)

        // Step A — age-based cleanup (global setting; runs before count cleanup)
        val ageCutoffMs = effectiveDeleteOlderThanCutoffMs()
        if (ageCutoffMs != null) {
            val tooOld = episodeDao.getDownloadedOlderThan(feedId, ageCutoffMs)
            for (ep in tooOld) {
                ep.localFilePath?.let { path ->
                    val file = File(path)
                    if (file.exists()) file.delete()
                }
                episodeDao.updateDownloadState(ep.id, DownloadStateEnum.DELETED, null)
            }
        }

        when (effectiveStrategy) {
            DownloadStrategy.DOWNLOAD_NEWEST -> {
                // Step B — retention count
                if (keepCount != null) {
                    val allDownloaded = episodeDao.getAllDownloadedNonProtected(feedId)
                    applyRetentionCleanup(allDownloaded, keepCount)
                }
                // Step C — download
                if (downloadCount > 0) {
                    val toDownload = episodeDao.getNotDownloadedNewest(feedId, downloadCount)
                    toDownload.forEach { ep -> enqueueDownload(ep.id) }
                }
            }

            DownloadStrategy.DOWNLOAD_IN_ORDER -> {
                // Step C — download in serial order (no retention count cleanup for this strategy)
                if (downloadCount > 0) {
                    val toDownload = episodeDao.getNotDownloadedOldest(feedId, downloadCount)
                    toDownload.forEach { ep -> enqueueDownload(ep.id) }
                }
            }

            DownloadStrategy.STREAM_NEWEST -> {
                // No download — mark as QUEUED for streaming
                if (downloadCount > 0) {
                    val toQueue = episodeDao.getNotDownloadedNewest(feedId, downloadCount)
                    toQueue.forEach { ep ->
                        episodeDao.updateDownloadState(ep.id, DownloadStateEnum.QUEUED, null)
                    }
                }
            }

            DownloadStrategy.MANUAL -> {
                // Only run cleanup if the feed opts in — never auto-download
                if (feed.allowCleanupForManual && keepCount != null) {
                    val allDownloaded = episodeDao.getAllDownloadedNonProtected(feedId)
                    applyRetentionCleanup(allDownloaded, keepCount)
                }
            }

            DownloadStrategy.GLOBAL -> {
                // Step B — retention count
                if (keepCount != null) {
                    val allDownloaded = episodeDao.getAllDownloadedNonProtected(feedId)
                    applyRetentionCleanup(allDownloaded, keepCount)
                }
                // Step C — download
                if (downloadCount > 0) {
                    val toDownload = episodeDao.getNotDownloadedNewest(feedId, downloadCount)
                    toDownload.forEach { ep -> enqueueDownload(ep.id) }
                }
            }
        }
    }
}
