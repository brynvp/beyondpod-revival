package mobi.beyondpod.revival.service

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import mobi.beyondpod.revival.data.local.dao.EpisodeDao
import mobi.beyondpod.revival.data.local.dao.FeedDao
import mobi.beyondpod.revival.data.local.entity.DownloadStrategy
import mobi.beyondpod.revival.data.repository.DownloadRepository
import mobi.beyondpod.revival.data.repository.FeedRepository

/**
 * Refreshes one feed (or all feeds) and enforces post-update cleanup rules.
 *
 * Input data keys:
 *   [KEY_FEED_ID] Long — single feed id, or [ALL_FEEDS] to refresh every feed.
 *
 * Step order is MANDATORY (CLAUDE.md rule #8, §9):
 *   1–4  Fetch + parse RSS, dedup, upsert episodes, archive removed   ← Phase 7 wires SAX parser
 *   5    Enforce maxTrackAgeDays                                        ← Phase 7 DAO query
 *   6    Enforce maxEpisodesToKeep — trim oldest downloads              ← LIVE (cleanup BEFORE download)
 *   7    Trigger auto-download                                          ← LIVE
 *   8    Auto-add to My Episodes                                        ← Phase 4 stub
 *   9    New-episode notification                                       ← Phase 4 stub
 */
@HiltWorker
class FeedUpdateWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val feedRepository: FeedRepository,
    private val downloadRepository: DownloadRepository,
    private val episodeDao: EpisodeDao,
    private val feedDao: FeedDao
) : CoroutineWorker(context, params) {

    companion object {
        const val KEY_FEED_ID = "feed_id"
        const val ALL_FEEDS   = -1L
    }

    override suspend fun doWork(): Result {
        val feedId = inputData.getLong(KEY_FEED_ID, ALL_FEEDS)
        return try {
            if (feedId == ALL_FEEDS) {
                feedDao.getAllFeedsList().forEach { processFeed(it.id) }
            } else {
                processFeed(feedId)
            }
            Result.success()
        } catch (e: Exception) {
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    private suspend fun processFeed(feedId: Long) {
        val feed = feedDao.getFeedById(feedId) ?: return

        // ── Steps 1–4: Fetch, parse, dedup, upsert, archive ─────────────────
        // RSS parsing is a Phase 7 concern (custom SAX parser per CLAUDE.md).
        // refreshFeed() currently stores a NotImplementedError stub — that is intentional;
        // failures here must not block the cleanup + download steps below.
        feedRepository.refreshFeed(feedId)
            .onFailure { /* log in Phase 7; continue to cleanup */ }

        // ── Step 5: Enforce maxTrackAgeDays ─────────────────────────────────
        // Full age-based cleanup requires a DAO query keyed on pubDate + maxTrackAgeDays.
        // Wired in Phase 7 alongside the RSS parser. isProtected veto applies.

        // ── Step 6: Enforce maxEpisodesToKeep — CLEANUP BEFORE DOWNLOAD ─────
        // Runs unconditionally for DOWNLOAD_NEWEST and MANUAL+allowCleanup strategies.
        // isProtected veto is enforced inside trimOldDownloads (WHERE isProtected = 0).
        val keepCount = feed.maxEpisodesToKeep
        if (keepCount != null && keepCount > 0) {
            val shouldTrim = feed.downloadStrategy == DownloadStrategy.DOWNLOAD_NEWEST
                || feed.downloadStrategy == DownloadStrategy.GLOBAL
                || (feed.downloadStrategy == DownloadStrategy.MANUAL && feed.allowCleanupForManual)
            if (shouldTrim) {
                episodeDao.trimOldDownloads(feedId, keepCount)
            }
        }

        // ── Step 7: Trigger auto-download (AFTER cleanup — mandatory) ───────
        downloadRepository.autoDownloadNewEpisodes(feedId)

        // ── Step 8: Auto-add to My Episodes (Phase 4 stub) ──────────────────
        // ── Step 9: New-episode notification  (Phase 4 stub) ────────────────
    }
}
