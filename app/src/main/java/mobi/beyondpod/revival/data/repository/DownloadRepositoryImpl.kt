package mobi.beyondpod.revival.data.repository

import android.app.DownloadManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
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
import mobi.beyondpod.revival.service.PlaybackStateHolder
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
     * True when the active network has the WiFi/Ethernet transport (UNMETERED).
     * Returns false if there is no active network or only mobile data is available.
     */
    private fun isOnWifi(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
               caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    override suspend fun checkMobileDownloadBlocked(feedId: Long): Boolean {
        if (isOnWifi()) return false                               // On WiFi — never blocked
        val feed = feedDao.getFeedById(feedId)
        val wifiOnlyGlobal = dataStore.data.first()[AppSettings.DOWNLOAD_ON_WIFI_ONLY] ?: false
        val wifiOnly = feed?.downloadOnlyOnWifi ?: wifiOnlyGlobal
        return wifiOnly                                            // Blocked only when WiFi-only is set
    }

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
     *
     * Q9 guard: the currently-playing episode is never deleted regardless of its position in the
     * retention window. [PlaybackStateHolder.currentlyPlayingEpisodeId] is set by PlaybackService
     * the moment an episode is claimed for playback and cleared on service destroy/stop.
     */
    private suspend fun applyRetentionCleanup(allDownloaded: List<EpisodeEntity>, keepCount: Int) {
        val playingId = PlaybackStateHolder.currentlyPlayingEpisodeId
        val toDelete = allDownloaded.drop(keepCount)
        for (ep in toDelete) {
            if (ep.id == playingId) continue  // Q9: never delete the playing episode's file
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
    override suspend fun enqueueDownload(episodeId: Long) = enqueueDownload(episodeId, mobileAllowed = false)

    private suspend fun enqueueDownload(episodeId: Long, mobileAllowed: Boolean) {
        val episode = episodeDao.getEpisodeById(episodeId) ?: return

        // Enforce WiFi-only constraint. Skip entirely (do NOT enqueue) when:
        //   - WiFi-only is on (per-feed override → global) AND
        //   - not currently on WiFi/Ethernet AND
        //   - caller has not explicitly approved mobile download.
        // This prevents episodes from silently stuck in DOWNLOADING state with no progress.
        val feed = feedDao.getFeedById(episode.feedId)
        val wifiOnlyGlobal = dataStore.data.first()[AppSettings.DOWNLOAD_ON_WIFI_ONLY] ?: false
        val wifiOnly = feed?.downloadOnlyOnWifi ?: wifiOnlyGlobal
        if (wifiOnly && !isOnWifi() && !mobileAllowed) return

        // Build destination path
        val safeTitle = episode.title.take(64).replace(Regex("[^A-Za-z0-9._\\- ]"), "_")
        val ext = episode.url.substringAfterLast('.').substringBefore('?').lowercase()
            .let { if (it.length in 2..4 && it.all { c -> c.isLetter() }) it else "mp3" }
        val dir = File(context.getExternalFilesDir(null), "podcasts/${episode.feedId}")
            .also { it.mkdirs() }
        val outputFile = File(dir, "$safeTitle.$ext")

        // When WiFi-only is set and the user has NOT explicitly approved mobile data,
        // constrain DownloadManager to WiFi only.  This means:
        //   - Downloads enqueued on WiFi will PAUSE if WiFi drops and RESUME when it returns.
        //   - They will NOT silently continue over mobile data.
        // When mobileAllowed=true (user approved via dialog), both networks are permitted.
        val networkTypes = if (wifiOnly && !mobileAllowed)
            DownloadManager.Request.NETWORK_WIFI
        else
            DownloadManager.Request.NETWORK_WIFI or DownloadManager.Request.NETWORK_MOBILE

        val request = DownloadManager.Request(Uri.parse(episode.url))
            .setTitle(episode.title)
            .setDescription(context.getString(android.R.string.unknownName).let { "BeyondPod" })
            .setMimeType(episode.mimeType.ifEmpty { "audio/mpeg" })
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationUri(Uri.fromFile(outputFile))
            .addRequestHeader("User-Agent", "BeyondPodRevival/5.0")
            .setAllowedNetworkTypes(networkTypes)

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
    override suspend fun autoDownloadNewEpisodes(feedId: Long, isManualRefresh: Boolean, mobileAllowed: Boolean) {
        val feed = feedDao.getFeedById(feedId) ?: return
        val effectiveStrategy = feed.downloadStrategy
        val keepCount = effectiveKeepCount(feed.maxEpisodesToKeep)
        val downloadCount = effectiveDownloadCount(feed.downloadCount)
        // Manual refreshes bypass the per-cycle cap but are capped at keepCount so we never
        // enqueue more episodes than the retention window allows.  Example: keepCount=5 →
        // manual refresh downloads at most the 5 newest undownloaded episodes.
        // Background scheduled runs respect the configured per-cycle downloadCount.
        val downloadLimit = when {
            isManualRefresh -> keepCount ?: Int.MAX_VALUE
            else            -> downloadCount
        }

        // Step A — age-based cleanup (global setting; runs before count cleanup)
        val ageCutoffMs = effectiveDeleteOlderThanCutoffMs()
        if (ageCutoffMs != null) {
            val playingId = PlaybackStateHolder.currentlyPlayingEpisodeId
            val tooOld = episodeDao.getDownloadedOlderThan(feedId, ageCutoffMs)
            for (ep in tooOld) {
                if (ep.id == playingId) continue  // Q9: never delete the playing episode's file
                ep.localFilePath?.let { path ->
                    val file = File(path)
                    if (file.exists()) file.delete()
                }
                episodeDao.updateDownloadState(ep.id, DownloadStateEnum.DELETED, null)
            }
        }

        when (effectiveStrategy) {
            DownloadStrategy.DOWNLOAD_NEWEST -> {
                // Peek at incoming downloads first so Step B can make exactly the right amount of room.
                // Rule #8 still holds — cleanup EXECUTES before enqueue, we just calculate first.
                val inFlight = episodeDao.countInFlightDownloads(feedId)
                val slots    = (downloadLimit - inFlight).coerceAtLeast(0)
                val toDownload = if (downloadCount > 0 && slots > 0)
                    episodeDao.getNotDownloadedNewest(feedId, slots)
                else emptyList()

                // Step B — retention: only make room for episodes that are NEWER than the current
                // window. Old backlog episodes (pubDate < newestDownloaded) don't shrink the keep
                // threshold — otherwise having any backlog at all would delete all downloads.
                if (keepCount != null) {
                    val allDownloaded = episodeDao.getAllDownloadedNonProtected(feedId)
                    val newestDate    = allDownloaded.firstOrNull()?.pubDate ?: Long.MIN_VALUE
                    val trulyNew      = toDownload.count { it.pubDate > newestDate }
                    val effectiveKeep = (keepCount - inFlight - trulyNew).coerceAtLeast(0)
                    applyRetentionCleanup(allDownloaded, effectiveKeep)
                }
                // Step C — enqueue
                toDownload.forEach { ep -> enqueueDownload(ep.id, mobileAllowed) }
            }

            DownloadStrategy.DOWNLOAD_IN_ORDER -> {
                // Peek first, same reason as DOWNLOAD_NEWEST
                val inFlight = episodeDao.countInFlightDownloads(feedId)
                val slots    = (downloadLimit - inFlight).coerceAtLeast(0)
                val toDownload = if (downloadCount > 0 && slots > 0)
                    episodeDao.getNotDownloadedOldest(feedId, slots)
                else emptyList()

                // Step C — no retention count cleanup for serial strategy
                toDownload.forEach { ep -> enqueueDownload(ep.id, mobileAllowed) }
            }

            DownloadStrategy.STREAM_NEWEST -> {
                // No download — mark as QUEUED for streaming (QUEUED already counted by inFlight)
                val inFlight = episodeDao.countInFlightDownloads(feedId)
                val slots    = (downloadLimit - inFlight).coerceAtLeast(0)
                if (downloadCount > 0 && slots > 0) {
                    val toQueue = episodeDao.getNotDownloadedNewest(feedId, slots)
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
                // Peek first — same room-making logic as DOWNLOAD_NEWEST
                val inFlight = episodeDao.countInFlightDownloads(feedId)
                val slots    = (downloadLimit - inFlight).coerceAtLeast(0)
                val toDownload = if (downloadCount > 0 && slots > 0)
                    episodeDao.getNotDownloadedNewest(feedId, slots)
                else emptyList()

                // Step B — retention: only count truly new (newer than window) when shrinking threshold
                if (keepCount != null) {
                    val allDownloaded = episodeDao.getAllDownloadedNonProtected(feedId)
                    val newestDate    = allDownloaded.firstOrNull()?.pubDate ?: Long.MIN_VALUE
                    val trulyNew      = toDownload.count { it.pubDate > newestDate }
                    val effectiveKeep = (keepCount - inFlight - trulyNew).coerceAtLeast(0)
                    applyRetentionCleanup(allDownloaded, effectiveKeep)
                }
                // Step C — enqueue
                toDownload.forEach { ep -> enqueueDownload(ep.id, mobileAllowed) }
            }
        }
    }

    /**
     * Runs age + retention-count cleanup across every feed immediately.
     * Uses current global settings. No new downloads are enqueued.
     * Per-feed overrides (maxEpisodesToKeep, downloadOnlyOnWifi, etc.) are respected.
     * isProtected episodes are never deleted (rule #4).
     */
    override suspend fun runGlobalRetentionCleanup(): Int {
        var totalDeleted = 0
        val ageCutoffMs = effectiveDeleteOlderThanCutoffMs()
        val allFeeds = feedDao.getAllFeedsList()

        val playingId = PlaybackStateHolder.currentlyPlayingEpisodeId
        for (feed in allFeeds) {
            // Step A — age-based cleanup
            if (ageCutoffMs != null) {
                val tooOld = episodeDao.getDownloadedOlderThan(feed.id, ageCutoffMs)
                for (ep in tooOld) {
                    if (ep.id == playingId) continue  // Q9: never delete the playing episode's file
                    ep.localFilePath?.let { path ->
                        val file = File(path)
                        if (file.exists()) file.delete()
                    }
                    episodeDao.updateDownloadState(ep.id, DownloadStateEnum.DELETED, null)
                    totalDeleted++
                }
            }

            // Step B — retention count cleanup (skip feeds with unlimited retention)
            val keepCount = effectiveKeepCount(feed.maxEpisodesToKeep) ?: continue
            val allDownloaded = episodeDao.getAllDownloadedNonProtected(feed.id)
            val toDelete = allDownloaded.drop(keepCount)
            for (ep in toDelete) {
                if (ep.id == playingId) continue  // Q9: never delete the playing episode's file
                ep.localFilePath?.let { path ->
                    val file = File(path)
                    if (file.exists()) file.delete()
                }
                episodeDao.updateDownloadState(ep.id, DownloadStateEnum.DELETED, null)
                totalDeleted++
            }
        }
        return totalDeleted
    }
}
