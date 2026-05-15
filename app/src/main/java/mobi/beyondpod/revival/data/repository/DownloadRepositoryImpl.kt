package mobi.beyondpod.revival.data.repository

import android.app.DownloadManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import mobi.beyondpod.revival.data.local.dao.EpisodeDao
import mobi.beyondpod.revival.data.local.dao.FeedDao
import mobi.beyondpod.revival.data.local.entity.DownloadStateEnum
import mobi.beyondpod.revival.data.local.entity.DownloadStrategy
import mobi.beyondpod.revival.data.local.entity.EpisodeEntity
import mobi.beyondpod.revival.data.settings.AppSettings
import mobi.beyondpod.revival.service.PlaybackStateHolder
import java.io.File
import javax.inject.Inject

private const val TAG = "BP.Download"

class DownloadRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val episodeDao: EpisodeDao,
    private val feedDao: FeedDao,
    private val downloadManager: DownloadManager,
    private val dataStore: DataStore<Preferences>,
    private val okHttpClient: okhttp3.OkHttpClient
) : DownloadRepository {

    /**
     * Follow HTTP redirects via OkHttp and return the final resolved URL.
     *
     * Android's DownloadManager caps at 5 redirects. Many podcast feeds chain through
     * multiple tracking proxies (podtrac, podsights, chartable, etc.) — a single episode
     * URL can easily chain 5+ hops before reaching the actual MP3. Passing the raw RSS
     * URL to DownloadManager causes ERROR_TOO_MANY_REDIRECTS while ExoPlayer (no cap)
     * can stream the same URL without issue.
     *
     * OkHttpClient is configured with redirects enabled (default) and has no cap, so it
     * resolves the chain and we hand DownloadManager the direct CDN URL.
     * Falls back to the original URL on any network error.
     */
    /**
     * Follow HTTP redirects via OkHttp and return the final resolved URL.
     *
     * Android's DownloadManager caps at 5 redirects. Many podcast feeds chain through
     * multiple tracking proxies (podtrac, podsights, chartable, etc.) — a single episode
     * URL can easily chain 5+ hops before reaching the actual MP3. Passing the raw RSS
     * URL to DownloadManager causes ERROR_TOO_MANY_REDIRECTS while ExoPlayer (no cap)
     * can stream the same URL without issue.
     *
     * Strategy:
     *   1. Try HEAD — fast, no data transfer. Some tracking proxies block HEAD (403/405).
     *   2. If HEAD returns 4xx/5xx, fall back to GET with Range: bytes=0-0 — nearly zero
     *      data transferred, but tracking proxies that block HEAD allow GET. The response
     *      body is immediately discarded. The final URL is read from response.request.url
     *      after OkHttp has followed all redirects.
     *   3. On any network error, return the original URL unchanged.
     */
    private suspend fun resolveRedirects(url: String): String = withContext(Dispatchers.IO) {
        // 1. Try HEAD first
        try {
            val req = okhttp3.Request.Builder().url(url).head()
                .addHeader("User-Agent", "BeyondPodRevival/5.0").build()
            val resp = okHttpClient.newCall(req).execute()
            val finalUrl = resp.request.url.toString()
            val status = resp.code
            resp.close()
            if (status in 200..399) {
                if (finalUrl != url) Log.d(TAG, "resolveRedirects HEAD: $url → $finalUrl")
                return@withContext finalUrl
            }
            Log.d(TAG, "resolveRedirects: HEAD returned $status, falling back to GET range")
        } catch (e: Exception) {
            Log.d(TAG, "resolveRedirects: HEAD failed (${e.message}), falling back to GET range")
        }

        // 2. Fallback: GET with Range header — bytes=0-0 minimises transfer
        //    Most tracking proxies (podtrac, podsights, chartable, etc.) allow GET.
        try {
            val req = okhttp3.Request.Builder().url(url)
                .addHeader("User-Agent", "BeyondPodRevival/5.0")
                .addHeader("Range", "bytes=0-0")
                .build()
            val resp = okHttpClient.newCall(req).execute()
            resp.body?.close()                  // discard immediately — we only need the URL
            val finalUrl = resp.request.url.toString()
            if (finalUrl != url) Log.d(TAG, "resolveRedirects GET: $url → $finalUrl")
            finalUrl
        } catch (e: Exception) {
            Log.w(TAG, "resolveRedirects: both HEAD and GET failed for $url — using original", e)
            url
        }
    }

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
     * Reset any DOWNLOADING episodes that the Android DownloadManager no longer tracks.
     *
     * Episodes get stuck in DOWNLOADING when:
     *   - The app was killed while a download was running (DownloadCompleteReceiver never fired)
     *   - The user cleared the DownloadManager's history externally
     *   - A previous app build failed to handle the completion broadcast
     *
     * Ghost rows block future downloads by consuming all available slots in countInFlightDownloads.
     * This reconciliation runs before the slot calculation in autoDownloadNewEpisodes.
     */
    private suspend fun reconcileStalledDownloads(feedId: Long) {
        // Step 0: bulk-reset any DOWNLOADING/QUEUED rows with no downloadId.
        // countInFlightDownloads counts these but reconcile's getDownloadingEpisodesForFeed
        // filters them out (downloadId IS NOT NULL), so they accumulate invisibly across
        // sessions, inflating inFlight to 1000+ and permanently blocking new downloads.
        val ghostsReset = episodeDao.resetNullDownloadIdGhosts(feedId)
        if (ghostsReset > 0)
            Log.w(TAG, "reconcile: feed=$feedId reset $ghostsReset null-downloadId ghost(s) → NOT_DOWNLOADED")

        val downloading = episodeDao.getDownloadingEpisodesForFeed(feedId)
        if (downloading.isEmpty()) return
        val now = System.currentTimeMillis()
        val onWifi = isOnWifi()
        for (episode in downloading) {
            val dmId = episode.downloadId ?: run {
                // No downloadId — definitely ghost; reset immediately
                Log.w(TAG, "reconcile: episode=${episode.id} '${episode.title}' in DOWNLOADING with no dmId — resetting")
                episodeDao.updateDownloadState(episode.id, DownloadStateEnum.NOT_DOWNLOADED, null)
                continue
            }
            val cursor = downloadManager.query(DownloadManager.Query().setFilterById(dmId))
            val stalled = cursor.use { c ->
                if (!c.moveToFirst()) return@use true   // not in DM at all → ghost
                val status       = c.getInt(c.getColumnIndex(DownloadManager.COLUMN_STATUS))
                val reason       = c.getInt(c.getColumnIndex(DownloadManager.COLUMN_REASON))
                val bytesDown    = c.getLong(c.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                val lastModified = c.getLong(c.getColumnIndex(DownloadManager.COLUMN_LAST_MODIFIED_TIMESTAMP))
                val ageMs        = now - lastModified

                when (status) {
                    DownloadManager.STATUS_RUNNING -> false   // actively downloading — keep
                    DownloadManager.STATUS_SUCCESSFUL,
                    DownloadManager.STATUS_FAILED  -> true    // terminal — receiver missed it; reset
                    DownloadManager.STATUS_PENDING -> {
                        // PENDING with no bytes for > 15 min = DownloadManager has forgotten it
                        // or is permanently throttled. Cancel and retry fresh next refresh.
                        val stale = bytesDown == 0L && ageMs > 15 * 60_000L
                        if (stale) Log.w(TAG, "reconcile: episode=${episode.id} dmId=$dmId PENDING ${ageMs/1000}s with 0 bytes — stale")
                        stale
                    }
                    DownloadManager.STATUS_PAUSED  -> {
                        // PAUSED_WAITING_FOR_NETWORK while on WiFi = network flag mismatch or stale
                        // PAUSED_WAITING_TO_RETRY = previous failure; treat as stalled
                        val networkStall = onWifi && (reason == DownloadManager.PAUSED_WAITING_FOR_NETWORK ||
                                                       reason == DownloadManager.PAUSED_QUEUED_FOR_WIFI)
                        val retryStall   = reason == DownloadManager.PAUSED_WAITING_TO_RETRY
                        // Also catch generic PENDING-like stall: 0 bytes, > 15 min old
                        val ageStall     = bytesDown == 0L && ageMs > 15 * 60_000L
                        if (networkStall || retryStall || ageStall)
                            Log.w(TAG, "reconcile: episode=${episode.id} dmId=$dmId PAUSED reason=$reason age=${ageMs/1000}s bytes=$bytesDown onWifi=$onWifi — stale")
                        networkStall || retryStall || ageStall
                    }
                    else -> true  // unknown status — reset to be safe
                }
            }
            if (stalled) {
                Log.w(TAG, "reconcile: episode=${episode.id} '${episode.title}' dmId=$dmId stalled — cancelling DM entry, resetting to NOT_DOWNLOADED")
                downloadManager.remove(dmId)
                episodeDao.updateDownloadState(episode.id, DownloadStateEnum.NOT_DOWNLOADED, null)
            }
        }
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
        val episode = episodeDao.getEpisodeById(episodeId) ?: run {
            Log.w(TAG, "enqueue[$episodeId] — episode not found in DB, skipping")
            return
        }

        // Don't double-enqueue — if already in flight, leave DownloadManager to finish it.
        if (episode.downloadState == DownloadStateEnum.DOWNLOADING ||
            episode.downloadState == DownloadStateEnum.QUEUED) {
            Log.d(TAG, "enqueue[$episodeId] '${episode.title}' — already ${episode.downloadState}, skipping")
            return
        }

        // Enforce WiFi-only constraint. Skip entirely (do NOT enqueue) when:
        //   - WiFi-only is on (per-feed override → global) AND
        //   - not currently on WiFi/Ethernet AND
        //   - caller has not explicitly approved mobile download.
        // This prevents episodes from silently stuck in DOWNLOADING state with no progress.
        val feed = feedDao.getFeedById(episode.feedId)
        val wifiOnlyGlobal = dataStore.data.first()[AppSettings.DOWNLOAD_ON_WIFI_ONLY] ?: false
        val wifiOnly = feed?.downloadOnlyOnWifi ?: wifiOnlyGlobal
        val onWifi = isOnWifi()
        Log.d(TAG, "enqueue[$episodeId] '${episode.title}' — wifiOnly=$wifiOnly onWifi=$onWifi mobileAllowed=$mobileAllowed")
        if (wifiOnly && !onWifi && !mobileAllowed) {
            Log.i(TAG, "enqueue[$episodeId] SKIPPED — WiFi-only policy active and not on WiFi")
            return
        }

        // Build destination path — fall back to internal storage if external unavailable.
        val safeTitle = episode.title.take(64).replace(Regex("[^A-Za-z0-9._\\- ]"), "_")
        val ext = episode.url.substringAfterLast('.').substringBefore('?').lowercase()
            .let { if (it.length in 2..4 && it.all { c -> c.isLetter() }) it else "mp3" }
        val baseDir = context.getExternalFilesDir(null) ?: context.filesDir
        val dir = File(baseDir, "podcasts/${episode.feedId}").also { it.mkdirs() }
        // Episode ID suffix guarantees uniqueness within a feed — same-titled episodes
        // (e.g. "Bonus Interview", "News Update") would otherwise collide and overwrite
        // each other in DownloadManager.
        val outputFile = File(dir, "${safeTitle}_${episode.id}.$ext")

        // When WiFi-only is set and the user has NOT explicitly approved mobile data,
        // constrain DownloadManager to WiFi only.  This means:
        //   - Downloads enqueued on WiFi will PAUSE if WiFi drops and RESUME when it returns.
        //   - They will NOT silently continue over mobile data.
        // When mobileAllowed=true (user approved via dialog), both networks are permitted.
        val networkTypes = if (wifiOnly && !mobileAllowed)
            DownloadManager.Request.NETWORK_WIFI
        else
            DownloadManager.Request.NETWORK_WIFI or DownloadManager.Request.NETWORK_MOBILE

        // Resolve redirect chain before handing to DownloadManager.
        // DownloadManager caps at 5 redirects; podcast tracking URLs (podtrac, podsights,
        // chartable, etc.) often chain 5+ hops. OkHttp follows all redirects and returns
        // the final CDN URL, which DownloadManager can then fetch directly.
        val resolvedUrl = resolveRedirects(episode.url)

        val request = DownloadManager.Request(Uri.parse(resolvedUrl))
            .setTitle(episode.title)
            .setDescription("BeyondPod")
            .setMimeType(episode.mimeType.trim().ifEmpty { "audio/mpeg" })
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationUri(Uri.fromFile(outputFile))
            .addRequestHeader("User-Agent", "BeyondPodRevival/5.0")
            .setAllowedNetworkTypes(networkTypes)

        // Wrap enqueue — DownloadManager.enqueue() can throw IllegalArgumentException
        // (bad URI, bad MIME type, destination conflict). Without this, the failure is
        // swallowed by the coroutine handler and the episode is stuck NOT_DOWNLOADED
        // with no feedback. On failure: log + set FAILED so the retry button appears.
        Log.i(TAG, "enqueue[$episodeId] → DownloadManager url=$resolvedUrl dest=${outputFile.absolutePath}")
        val dmId = try {
            downloadManager.enqueue(request)
        } catch (e: Exception) {
            Log.e(TAG, "enqueue[$episodeId] FAILED — DownloadManager.enqueue threw: ${e.message}", e)
            episodeDao.updateDownloadState(episodeId, DownloadStateEnum.FAILED, null)
            return
        }
        Log.i(TAG, "enqueue[$episodeId] SUCCESS — dmId=$dmId state→DOWNLOADING")
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
        val feed = feedDao.getFeedById(feedId) ?: run {
            Log.w(TAG, "autoDownload feed=$feedId — feed not found, skipping")
            return
        }

        // Reconcile ghost DOWNLOADING rows before counting in-flight slots.
        // Episodes can get stuck in DOWNLOADING if DownloadCompleteReceiver never fired
        // (e.g. app killed mid-download, DownloadManager row removed externally). Without
        // this, those ghost rows permanently block future auto-downloads by consuming slots.
        reconcileStalledDownloads(feedId)

        val effectiveStrategy = feed.downloadStrategy
        val keepCount = effectiveKeepCount(feed.maxEpisodesToKeep)
        val downloadCount = effectiveDownloadCount(feed.downloadCount)
        val downloadLimit = when {
            isManualRefresh -> keepCount ?: Int.MAX_VALUE
            else            -> downloadCount
        }
        val inFlightCheck = episodeDao.countInFlightDownloads(feedId)
        val slots = (downloadLimit - inFlightCheck).coerceAtLeast(0)
        Log.d(TAG, "autoDownload feed=$feedId '${feed.title}' strategy=$effectiveStrategy manual=$isManualRefresh " +
            "keepCount=$keepCount downloadCount=$downloadCount downloadLimit=$downloadLimit inFlight=$inFlightCheck slots=$slots")
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

                // Diagnostic: log state breakdown when slots exist but nothing to download.
                if (toDownload.isEmpty() && slots > 0) {
                    val allEps = episodeDao.getEpisodesForFeedList(feedId)
                    val states = allEps.groupBy { it.downloadState.name }.mapValues { it.value.size }
                    val archived = allEps.count { it.isArchived }
                    Log.d(TAG, "autoDownload DIAG NEWEST feed=$feedId total=${allEps.size} archived=$archived states=$states")
                }

                // Step B — retention: only make room for episodes that are NEWER than the current
                // window. Old backlog episodes (pubDate < newestDownloaded) don't shrink the keep
                // threshold — otherwise having any backlog at all would delete all downloads.
                //
                // Guard: when in-flight already meets/exceeds keepCount, do NOT delete currently-
                // downloaded files. Without this, keepCount=3 + inFlight=10 → effectiveKeep=0 →
                // all downloaded files wiped on every refresh pass.
                if (keepCount != null) {
                    val allDownloaded = episodeDao.getAllDownloadedNonProtected(feedId)
                    // Compare against the OLDEST downloaded episode, not the newest.
                    // A candidate "belongs in the window" if it's fresher than the oldest
                    // episode currently downloaded — it would replace that one. Using newestDate
                    // means trulyNew is always 0 after the first download, locking the window.
                    val oldestInWindow = allDownloaded.lastOrNull()?.pubDate ?: Long.MIN_VALUE
                    val trulyNew       = toDownload.count { it.pubDate > oldestInWindow }

                    // Step B cleanup runs UNCONDITIONALLY — even when no downloads follow.
                    // Without this, an over-full window never gets trimmed when suppression fires.
                    val effectiveKeep = if (inFlight >= keepCount) keepCount
                                        else (keepCount - inFlight - trulyNew).coerceAtLeast(0)
                    applyRetentionCleanup(allDownloaded, effectiveKeep)

                    // Backlog suppression: when window is full AND no candidate is fresher than
                    // the oldest downloaded episode, there is nothing worth swapping in — suppress.
                    // If window has room, or if candidates would displace old window entries, download.
                    if (toDownload.isNotEmpty() && allDownloaded.size >= keepCount && trulyNew == 0) {
                        Log.d(TAG, "autoDownload NEWEST feed=$feedId suppressed: window full " +
                            "(${allDownloaded.size}/$keepCount), all ${toDownload.size} candidates older than oldest in window")
                        return
                    }
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
                Log.d(TAG, "autoDownload GLOBAL feed=$feedId inFlight=$inFlight slots=$slots toDownload=${toDownload.size} downloadCount=$downloadCount")

                // Diagnostic: log state breakdown when slots exist but nothing to download.
                if (toDownload.isEmpty() && slots > 0) {
                    val allEps = episodeDao.getEpisodesForFeedList(feedId)
                    val states = allEps.groupBy { it.downloadState.name }.mapValues { it.value.size }
                    val archived = allEps.count { it.isArchived }
                    Log.d(TAG, "autoDownload DIAG GLOBAL feed=$feedId total=${allEps.size} archived=$archived states=$states")
                }

                // Step B — retention: only count truly new (newer than window) when shrinking threshold.
                //
                // Guard: when in-flight already meets/exceeds keepCount, do NOT delete currently-
                // downloaded files. Without this, keepCount=3 + inFlight=31 → effectiveKeep=0 →
                // all downloaded files wiped on every refresh, causing "downloads disappear" symptom.
                if (keepCount != null) {
                    val allDownloaded = episodeDao.getAllDownloadedNonProtected(feedId)
                    // Compare against the OLDEST downloaded episode, not the newest.
                    // A candidate "belongs in the window" if it's fresher than the oldest
                    // episode currently downloaded — it would displace that one. Using newestDate
                    // (firstOrNull) means trulyNew is always 0 after the first download, because
                    // nothing can ever be newer than the newest — permanently locking the window.
                    val oldestInWindow = allDownloaded.lastOrNull()?.pubDate ?: Long.MIN_VALUE
                    val trulyNew       = toDownload.count { it.pubDate > oldestInWindow }

                    // Step B cleanup runs UNCONDITIONALLY — even when no downloads will follow.
                    // Without this, an over-full window (e.g. 7 > keepCount=5 from a previous
                    // bad session) never gets trimmed when suppression fires, deadlocking forever.
                    val effectiveKeep = if (inFlight >= keepCount) keepCount
                                        else (keepCount - inFlight - trulyNew).coerceAtLeast(0)
                    applyRetentionCleanup(allDownloaded, effectiveKeep)

                    // Backlog suppression: when the window is full AND no candidate is fresher
                    // than the OLDEST downloaded episode, there is nothing worth swapping in.
                    // If window has room, or candidates would displace old window entries, download.
                    if (toDownload.isNotEmpty() && allDownloaded.size >= keepCount && trulyNew == 0) {
                        Log.d(TAG, "autoDownload GLOBAL feed=$feedId suppressed: window full " +
                            "(${allDownloaded.size}/$keepCount), all ${toDownload.size} candidates older than oldest in window")
                        return
                    }
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
