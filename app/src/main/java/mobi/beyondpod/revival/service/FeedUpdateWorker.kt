package mobi.beyondpod.revival.service

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import mobi.beyondpod.revival.data.local.dao.FeedDao
import mobi.beyondpod.revival.data.repository.DownloadRepository
import mobi.beyondpod.revival.data.repository.FeedRepository

/**
 * Refreshes one feed (or all feeds) and enforces post-update cleanup rules.
 *
 * Input data keys:
 *   [KEY_FEED_ID] Long — single feed id, or [ALL_FEEDS] to refresh every feed.
 *   [KEY_IS_MANUAL] Boolean — true when triggered by the user; bypasses per-cycle downloadCount
 *       cap in autoDownloadNewEpisodes so all available new episodes are enqueued in one pass.
 *
 * Step order is MANDATORY (CLAUDE.md rule #8, §9):
 *   1–4  Fetch + parse RSS, dedup, upsert episodes, archive removed   ← Phase 7 wires SAX parser
 *   5    Enforce maxTrackAgeDays                                        ← Phase 7 DAO query
 *   6    Enforce maxEpisodesToKeep — handled inside autoDownloadNewEpisodes (soft delete, rule #8)
 *   7    Trigger auto-download (includes cleanup-before-download)       ← LIVE
 *   8    Auto-add to My Episodes                                        ← Phase 4 stub
 *   9    New-episode notification                                       ← Phase 4 stub
 *
 * NOTE: Step 6 is intentionally NOT a separate trimOldDownloads() call here.
 * That DAO method hard-deletes episode records, which destroys play history and starred state.
 * Retention is handled in DownloadRepositoryImpl.autoDownloadNewEpisodes() via soft-delete
 * (file removed, downloadState = DELETED, record kept) — cleanup runs before download per rule #8.
 */
@HiltWorker
class FeedUpdateWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val feedRepository: FeedRepository,
    private val downloadRepository: DownloadRepository,
    private val feedDao: FeedDao
) : CoroutineWorker(context, params) {

    companion object {
        const val KEY_FEED_ID  = "feed_id"
        const val KEY_IS_MANUAL = "is_manual"
        const val ALL_FEEDS    = -1L
    }

    override suspend fun doWork(): Result {
        val feedId   = inputData.getLong(KEY_FEED_ID, ALL_FEEDS)
        val isManual = inputData.getBoolean(KEY_IS_MANUAL, false)
        return try {
            if (feedId == ALL_FEEDS) {
                // Clear any stale lastUpdateFailed flags left by the old worker bug before
                // processing feeds — ensures the warning icon only fires on real failures.
                feedDao.clearAllUpdateFailedFlags()
                feedDao.getAllFeedsList().forEach { processFeed(it.id, isManual) }
            } else {
                processFeed(feedId, isManual)
            }
            Result.success()
        } catch (e: Exception) {
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    private suspend fun processFeed(feedId: Long, isManual: Boolean = false) {
        val feed = feedDao.getFeedById(feedId) ?: return

        // ── Virtual folder feeds: scan for new audio files, skip RSS fetch ───
        if (feed.isVirtualFeed) {
            feedRepository.scanFolderFeed(feedId)
                .onFailure { /* silent — folder may have been moved or permission revoked */ }
            return
        }

        // ── Steps 1–4: Fetch, parse, dedup, upsert, archive ─────────────────
        // markFailure=false: background failures are transient — don't stamp lastUpdateFailed=true
        // on every feed when the device is briefly offline. The warning icon is only meaningful
        // for manual pull-to-refresh failures (FeedDetailViewModel.refresh uses the default true).
        val refreshResult = feedRepository.refreshFeed(feedId, markFailure = false)

        // ── Step 5: Enforce maxTrackAgeDays ─────────────────────────────────
        // Full age-based cleanup requires a DAO query keyed on pubDate + maxTrackAgeDays.
        // Wired in Phase 7 alongside the RSS parser. isProtected veto applies.

        // ── Steps 6+7: Cleanup THEN auto-download (mandatory order, rule #8) ─
        // G8: only run cleanup + download when the RSS fetch actually succeeded.
        // Running cleanup on a stale snapshot (fetch failed, no new episodes) risks
        // deleting valid downloads without replacing them. Mirrors FeedDetailViewModel.refresh().
        if (refreshResult.isSuccess) {
            downloadRepository.autoDownloadNewEpisodes(feedId, isManualRefresh = isManual)
        }

        // ── Step 8: Auto-add to My Episodes (Phase 4 stub) ──────────────────
        // ── Step 9: New-episode notification  (Phase 4 stub) ────────────────
    }
}
