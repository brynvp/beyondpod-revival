# Red Team QA Audit — Status Report

**Status:** Full Backlog Audit Complete
**Date:** 2026-05-05
**Role:** Red Team Auditor

This document tracks the resolution of critical architectural and stability risks identified in the "Early Beta" phase.

---

## ✅ Verified Fixed (Passed Audit)

### 1. Resource Leaks (Orphan Files) [G11]
- **Status:** **FIXED**.
- **Verification:** `FeedRepositoryImpl.deleteFeed` now queries `getDownloadingEpisodesForFeed` and calls `downloadManager.remove(*dmIds)` before the database rows are CASCADE deleted. This ensures active system downloads are cancelled.

### 2. The "Ghost" Playback Loop [G12]
- **Status:** **FIXED**.
- **Verification:** `FeedRepositoryImpl.deleteFeed` now checks `PlaybackStateHolder.currentlyPlayingEpisodeId`. If the episode belongs to the deleted feed, it starts `PlaybackService` with `ACTION_STOP_PLAYBACK`. `PlaybackService` correctly handles this action by stopping the player and clearing state.

### 3. Queue Reorder Logic Bug [Q5]
- **Status:** **FIXED**.
- **Verification:** `QueueViewModel.reorderItems` now resolves the new position of the playing episode (via `PlaybackStateHolder`) in the reordered list. This ensures the playback cursor remains on the correct episode after a drag-to-reorder gesture.

### 4. Queue-First Principle Violation (Auto-Advance) [QE4]
- **Status:** **FIXED**.
- **Verification:** `PlaybackService.onPlaybackStateChanged` now prioritizes the active `QueueSnapshot` for auto-advance. It looks up the current episode in the snapshot items and advances to `currentIndex + 1`. It only falls back to feed-based advance if no active queue is found or the episode isn't in it.

### 5. Sparse Queue Positions [Q6, Q7]
- **Status:** **FIXED**.
- **Verification:** `QueueSnapshotDao.removeAndCompact` uses a subquery to atomically re-number positions (0, 1, 2...) after items are removed. `QueueViewModel` pre-calculates the correct `currentItemIndex` based on the removal position and updates the snapshot atomically.

### 6. Retention Cleanup Integrity [Q9, G8, G15, G19]
- **Verification:** 
    - **Q9:** `PlaybackStateHolder` guard is active in `DownloadRepositoryImpl`.
    - **G8:** `FeedUpdateWorker` now gates cleanup behind refresh success.
    - **G15:** Global cleanup in `SettingsViewModel` now uses `Dispatchers.IO`.
    - **G19:** Reducing per-feed `maxEpisodesToKeep` now triggers immediate cleanup.

---

## ✅ Fixed Post-Audit (2026-05-05)

### 7. Missing Error-Skip Logic [Q12]
- **Status:** **FIXED**.
- **Verification:** `PlaybackService.onPlayerError` now skips to the next queue item on content errors (bad URL, 404, codec failure) and network errors where connectivity is present. Truly-offline network errors leave the player stopped for manual resume. Uses the same `queueSnapshotDao` infrastructure as QE4.

### 10. `ACTION_STOP_PLAYBACK` and Foreground State
- **Status:** **FIXED**.
- **Verification:** `ACTION_STOP_PLAYBACK` handler now calls `stopForeground(true)` + `stopSelf()` after stopping the player and clearing state. Media notification is dismissed immediately when a feed is deleted mid-play.

---

## ✅ Fixed 2026-05-09 (Post Red-Team Audit)

### 11. Date Parsing Failure — Libsyn GMT timezone [E1]
- **Status:** **FIXED.**
- **Root cause (corrected from audit):** DB analysis showed 273/277 "Ask a Spaceman" episodes with `pubDate=0` on a fresh DB. All failing episodes were published 2018+; all 4 working episodes were from 2015–2017. Pattern is by year, not day-digit count. Libsyn changed their RSS date format from numeric `+0000` to named `GMT` around 2018. `SimpleDateFormat("...Z")` only accepts numeric offsets — `GMT` silently returns null.
- **Fix:** `DateTimeFormatter.RFC_1123_DATE_TIME` added as the primary parse path. It is stateless, thread-safe, and handles all valid RFC 2822 variants including `GMT`, `+0000`, optional day-of-week, and single-digit days. `SimpleDateFormat` patterns retained as fallback but converted to `ThreadLocal` (see #13).

### 12. Redirect Resolver HEAD Blocked by Trackers [G21]
- **Status:** **FIXED.**
- **Fix:** `resolveRedirects` now tries HEAD first. If the response is 4xx/5xx (tracker blocking HEAD), it falls back to a GET with `Range: bytes=0-0`. Nearly zero data transferred; OkHttp follows the full redirect chain and `response.request.url` contains the final CDN URL. Both HEAD and GET failures log a warning and return the original URL unchanged.

### 13. Thread-Safety Risk (SimpleDateFormat) [Parser]
- **Status:** **FIXED.**
- **Fix:** `DATE_FORMATS` shared statics replaced with `ThreadLocal.withInitial { listOf(...) }`. Each thread gets its own `SimpleDateFormat` instances. `DateTimeFormatter.RFC_1123_DATE_TIME` (now the primary path) is inherently thread-safe.

### 14. Silent WiFi Blocking (Manual Download) [Medium]
- **Status:** **FIXED.**
- **Fix:** `FeedDetailViewModel.downloadEpisode` now calls `downloadRepository.checkMobileDownloadBlocked(feedId)`. If blocked, it stores the episode ID in `pendingMobileEpisodeId` and raises `_showMobileWarning = true`. `confirmMobileDownload` checks `pendingMobileEpisodeId` — if set, downloads that single episode via `enqueueDownloadUseCase`; if null, runs the bulk `autoDownloadNewEpisodes` path (existing refresh behaviour). `dismissMobileWarning` clears both the flag and the pending ID.


### 8. Double Fetch on Subscribe [G1]
- **Status:** **DEFERRED** — requires UX refactor of `AddFeedViewModel` preview step. Accepted for now.

### 9. `isProtected` Veto in Bulk Delete
- **Design decision:** `isProtected` is the auto-deletion veto — it guards against automated cleanup/retention processes. Manual unsubscribe with "delete downloads" is explicit user intent. Furthermore, since the DB row is CASCADE-deleted, preserving the file would create an unmanaged orphan with no record pointing to it — strictly worse. Current behaviour is correct. Architecture non-negotiable #4 applies to *auto-deletion* only; manual delete is an intentional exception. Documented, no code change required.

---

## ✅ Fixed 2026-05-10 — pubDate Parser Root Cause + Download Disappearing

### 15. `FeedParser.parseDate()` — Tue/Thu Day Abbreviation Collides with ISO 8601 'T' [Critical]
- **Status:** **FIXED (confirmed on device).**
- **Root cause:** `if (trimmed.contains('T'))` was the ISO 8601 detection condition. RFC 1123 date strings beginning with day-of-week abbreviations "Tue" or "Thu" also contain uppercase 'T'. Both are present in the substring "Tue" and "Thu". When triggered, the code attempted `Instant.parse("Tue, 05 May 2026 11:00:00 +0000")` (fails), then `Instant.parse("Tue,T05TMayT2026T11:00:00T+0000")` (all spaces replaced with T — also fails), returned `0L` and never fell through to `RFC_1123_DATE_TIME` which would correctly parse it.
- **Affected feeds:** Any podcast publishing on Tuesday or Thursday. Ask a Spaceman (weekly Tuesday) had 272/277 episodes with pubDate=0, causing wrong sort order (all to the bottom) and blank date display.
- **Fix:** `if (trimmed.isNotEmpty() && trimmed[0].isDigit() && trimmed.contains('T'))`. ISO 8601 strings always start with the year (digit); RFC 1123 strings always start with the day abbreviation (letter). One-char lookahead is sufficient.
- **Self-healing:** `mergeWithExisting()` takes `pubDate` from the freshly-parsed incoming episode. The first pull-to-refresh after the fix updates all previously-zero pubDates in the DB automatically — no data migration required.

### 16. Downloads Disappearing on Pull-to-Refresh [High]
- **Status:** **RESOLVED** (consequence of fix #15, confirmed on device).
- **Root cause:** In `autoDownloadNewEpisodes()` (DOWNLOAD_NEWEST strategy), retention cleanup uses `trulyNew = toDownload.count { it.pubDate > newestDate }`. With `newestDate = 0L` (all existing downloads had pubDate=0) and all new candidates also having pubDate=0, `trulyNew = count { 0 > 0 } = 0`. This meant `effectiveKeep = keepCount - inFlight - 0`, so cleanup targeted the full keepCount slots. If the user had manually downloaded more episodes than keepCount allowed, the oldest (by insertion order, due to `id ASC` tiebreak) were silently deleted.
- **Fix:** Resolved as a consequence of fix #15. After the parseDate fix, episodes have distinct non-zero pubDates, `trulyNew` correctly counts genuinely new episodes, and the retention window expands appropriately. First pull-to-refresh heals all pubDates in DB.
---

## ✅ Fixed v1.0.9 – v1.0.13 (post-audit)

| # | Issue | Fix version | Notes |
|---|-------|-------------|-------|
| 17 (ghost reset part) | STREAM_NEWEST ghost wipe — `resetNullDownloadIdGhosts` reset QUEUED+null rows | v1.0.9 | Scope narrowed to DOWNLOADING only; QUEUED+null is intentional |
| 18 / 3.1 | Mobile chain hardcoded `false` — chain stops after first download on mobile | v1.0.9 | `mobileAllowed = !checkMobileDownloadBlocked(feedId)` |
| 10.4 | Filename collision on same-title episodes | v1.0.9 | Episode ID suffix added: `${safeTitle}_${episode.id}.$ext` |
| 21 | Worker exception kills entire feed batch | v1.0.9 | `processFeed` wrapped in `runCatching` |
| — | Per-feed settings (keep count, WiFi) not updating UI live | v1.0.12 | `getFeedByIdFlow()` live Room query replaces one-shot cold flow |
| — | Strategy dialog rows only tappable on RadioButton circle | v1.0.12 | `.clickable` added to Row; `RadioButton(onClick = null)` |
| — | Global keep count cleanup race (write + cleanup concurrent coroutines) | v1.0.12 | Sequenced in single coroutine: `dataStore.edit` then cleanup |
| — | Download ping-pong (delete ep5 → system re-downloads ep5 not ep6) | v1.0.13 | DELETED excluded from `getNewestEpisodesForWindow` DAO query |

**Note:** #17 slot-starvation half is still open (see below). Ghost-reset is fixed; QUEUED items still permanently block inFlight slots.

---

## 🛑 Severity: High (Gemini Red Team Findings — 2026-05-10)

### 17. STREAM_NEWEST Slot Starvation [SEVERITY: High] — PARTIALLY FIXED
**Ghost reset:** ✅ Fixed v1.0.9 — `resetNullDownloadIdGhosts` now scoped to DOWNLOADING only; QUEUED+null rows are left alone.
**Slot starvation:** 🛑 **Still open.** QUEUED episodes (set by STREAM_NEWEST) are never moved to another state — no receiver triggers on "stream started". `countInFlightDownloads` includes QUEUED, so a QUEUED episode permanently consumes a slot. After one refresh, the slot is full forever; no new episodes are ever queued for streaming.
**Suggested fix:** STREAM_NEWEST should count QUEUED slots separately from true in-flight downloads, OR clear QUEUED state when the episode starts streaming (in `PlaybackService.loadAndPlay`). The cleanest fix: `countInFlightDownloads` excludes QUEUED-with-no-downloadId, and STREAM_NEWEST uses its own slot counter.

### 18. Download Chain Broken on Mobile Data [SEVERITY: High] — ✅ FIXED v1.0.9
`DownloadCompleteReceiver` now passes `mobileAllowed = !downloadRepository.checkMobileDownloadBlocked(episode.feedId)`. Chain continues on mobile when the feed's policy allows it.

---

## ⚠️ Severity: Medium (Gemini Red Team Findings — 2026-05-10)

### 19. Concurrent Auto-Download Race Condition [SEVERITY: Medium] — CONFIRMED OPEN
**File:** DownloadRepositoryImpl.kt — `autoDownloadNewEpisodes`
**Confirmed 2026-05-16:** No mutex or `feedRefreshLocks` exists anywhere in DownloadRepositoryImpl. The suggested fix referenced a non-existent structure. Two concurrent calls for the same feed (e.g. DownloadCompleteReceiver + WorkManager periodic) can both read the same NOT_DOWNLOADED episodes and double-enqueue them.
**Fix:** Add `private val feedDownloadLocks = ConcurrentHashMap<Long, Mutex>()` and wrap `autoDownloadNewEpisodes` body with `feedDownloadLocks.getOrPut(feedId) { Mutex() }.withLock { ... }`. See COMMIT_PROMPT_v1.0.14.md.

### 20. Filename Collision for Duplicate Titles [SEVERITY: Medium] — ✅ FIXED v1.0.9
Episode ID suffix added: `${safeTitle}_${episode.id}.$ext`. Filenames are now unique per episode within a feed folder.

### 21. Feed Exceptions Kill Entire Worker Batch [SEVERITY: Medium] — ✅ FIXED v1.0.9
`processFeed` wrapped in `runCatching` inside the parallel `async` block. One bad feed logs an error but does not cancel the batch.

### 22. Potential BroadcastReceiver Timeout [SEVERITY: Medium]
**File:** DownloadCompleteReceiver.kt, line ~45 / DownloadRepositoryImpl.kt, line ~245
**Symptom:** Background download chain intermittently stops during long download batches or on slow networks.
**Root cause:** `DownloadCompleteReceiver` uses `goAsync()` which has a ~10 second limit. Inside the coroutine, it calls `autoDownloadNewEpisodes`, which perform sequential `resolveRedirects` (OkHttp HEAD/GET) for every enqueued item. If a batch is large (e.g. 10 items), 10+ sequential network requests can easily exceed 10 seconds, causing the system to kill the process before the receiver finishes.
**Reproduction:** WiFi returns, enqueuing a batch of 10+ episodes with slow tracking proxies.
**Suggested fix:** Limit the number of enqueues per receiver pass, or move the `autoDownload` logic for large batches to a `WorkManager` one-shot job.

---

## 🔍 Observations & Minor Risks (Gemini Red Team — 2026-05-10)

### 23. STREAM_NEWEST Items Stuck if Strategy Changes [SEVERITY: Low]
**File:** DownloadRepositoryImpl.kt, line ~178
**Symptom:** If a feed is changed from "Stream Newest" to "Download Newest", the episodes that were already marked as streamable (`QUEUED`) will never be downloaded.
**Root cause:** `enqueueDownload` has a guard that returns immediately if state is `QUEUED`. `autoDownloadNewEpisodes` for `DOWNLOAD_NEWEST` only picks up `NOT_DOWNLOADED` items.
**Suggested fix:** `enqueueDownload` should only skip `QUEUED` items if they have a non-null `downloadId`.

---

## ## Verdict

### (a) Critical/High issues to fix before wider beta
- **Issue #17 (STREAM_NEWEST):** This feature is essentially broken by the ghost-reset logic and slot starvation.
- **Issue #18 (Mobile Chain):** Breaks manual refresh/download flow on mobile, which is a key part of the "unreliable network" resilience story.
- **Issue #21 (Worker Failure):** Causes excessive battery drain and bandwidth use by retrying successful feeds because one feed failed.

### (b) Architectural decisions worth reconsidering
- **Filename strategy:** Using only the title for filenames is a known source of conflict in podcasting where generic titles are common.
- **Receiver-driven chain:** Chaining downloads in a `BroadcastReceiver` is clever for Doze mode, but calling complex repository logic like `autoDownloadNewEpisodes` (which does DB reconciliation, cleanup, and multiple network requests) is pushing the limits of `goAsync()`. Consider moving the chaining logic to a dedicated `DownloadChainWorker`.

# BeyondPod Revival — Gemini Workflow Code Audit Findings

**Status:** Technical Audit of All Workflow Domains Complete
**Date:** 2026-05-10
**Role:** Senior Android Engineer (Red Team Auditor)

---

## Domain 1: Feed Lifecycle

### [SCENARIO 1.1] Add a feed (URL entry → first episode list)
- Status: `RISK`
- Finding: `AddFeedViewModel.confirmSubscribe` enqueues a `FeedUpdateWorker` with `ExistingWorkPolicy.KEEP`. If the user subscribes, deletes, and re-subscribes quickly to the same URL, the `KEEP` policy might prevent the new refresh from running if a stale worker is still "running" (e.g., in a terminal error state). Also, `subscribeToFeed` stores the `finalUrl` but the `Duplicate subscribe` check in `subscribeToFeed` (line 53) uses the original URL. If the feed redirects, a second subscribe with the original URL will proceed until line 68 where it checks `finalUrl` — potentially executing two network parses before deduping.
- Severity: `P3-minor`

### [SCENARIO 1.2] Manual feed refresh (pull-to-refresh)
- Status: `CLEAN`
- Finding: Trace confirmed: `FeedDetailViewModel.refresh()` enqueues `FeedUpdateWorker(isManual=true)`, which calls `refreshFeed(id, markFailure=false)` then `autoDownloadNewEpisodes(id, isManualRefresh=true)`. Ordering is correct (Rule 8). `isArchived` is updated correctly via `upsertEpisode` -> `mergeWithExisting` -> `archiveRemovedEpisodes`.
- Severity: `N/A`

### [SCENARIO 1.3] Background auto-refresh (WorkManager)
- Status: `RISK`
- Finding: `FeedUpdateWorker.doWork` calls `feedDao.clearAllUpdateFailedFlags()` only for the `ALL_FEEDS` path. Single-feed scheduled tasks (future) or manual refreshes don't clear it. `isManualRefresh=false` is correctly set for background path.
- Severity: `P4-nit`

### [SCENARIO 1.4] Delete a feed
- Status: `RISK`
- Finding: `FeedRepositoryImpl.deleteFeed` (line 120) pre-fetches `feedEpisodes` before the CASCADE delete. However, it does not explicitly clear the `PlaybackStateHolder.currentlyPlayingEpisodeId` if the playing episode is from this feed; it only starts `ACTION_STOP_PLAYBACK`. While `ACTION_STOP_PLAYBACK` clears it in the service, there's a race: `DownloadRepositoryImpl` might read the `PlaybackStateHolder` before the service processes the intent.
- Severity: `P3-minor`

### [SCENARIO 1.5] Re-add a previously deleted feed
- Status: `BUG`
- Finding: `EpisodeRepositoryImpl.upsertEpisode` (line 224) uses GUID dedup. If a feed is deleted (DB rows gone) but orphaned files remain on disk (due to crash during `deleteFeed`), re-subscribing will create new DB rows. Since `id` is new, the orphaned file `podcasts/{id}/title.mp3` won't be found (as `id` changed), but the disk space remains leaked.
- Severity: `P2-incorrect-behaviour`

---

## Domain 2: Feed Settings

### [SCENARIO 2.1] Change per-feed keep count (e.g. 5 → 3)
- Status: `CLEAN`
- Finding: `FeedDetailViewModel.updateFeedProperties` correctly triggers immediate cleanup via `autoDownloadNewEpisodes(isManualRefresh=false)` when `keepCount` is reduced. `isProtected` is respected in `applyRetentionCleanup`.
- Severity: `N/A`

### [SCENARIO 2.2] Change download strategy (e.g. DOWNLOAD_NEWEST → MANUAL)
- Status: `BUG`
- Finding: Switching to `MANUAL` does NOT cancel already-enqueued downloads in `DownloadManager`. The episodes remain in `DOWNLOADING`/`QUEUED` state in the DB and continue to download in the background. The user expects `MANUAL` to stop all automated activity.
- Severity: `P2-incorrect-behaviour`

### [SCENARIO 2.3] Change WiFi-only setting (per-feed)
- Status: `RISK`
- Finding: `DownloadManager` network types are set at enqueue time. Changing the setting does not update in-flight downloads. If a download is running on mobile (approved) and the user sets "WiFi Only", it keeps running on mobile.
- Severity: `P3-minor`

---

## Domain 3: Episode Download

### [SCENARIO 3.1] Auto-download chain (normal case)
- Status: `FIXED v1.0.9`
- Finding: `mobileAllowed = !downloadRepository.checkMobileDownloadBlocked(episode.feedId)` — chain now respects feed-level mobile policy.

### [SCENARIO 3.2] Manual download (user taps button)
- Status: `RISK`
- Finding: `FeedDetailViewModel.downloadEpisode` uses `enqueueDownloadUseCase(episodeId)`. This calls `DownloadRepositoryImpl.enqueueDownload(episodeId, mobileAllowed = false)`. If the user is on mobile and taps the arrow, they get the mobile warning. If they approve, it calls `autoDownloadNewEpisodes(mobileAllowed = true)`. This works, but two rapid taps on the download arrow before the first `enqueueDownload` returns will proceed past the state check (line 115) and call `downloadManager.enqueue` twice.
- Severity: `P3-minor`

---

## Domain 4: Episode Deletion and Re-download

### [SCENARIO 4.3] Retention cleanup deletes an episode
- Status: `BUG`
- Finding: `autoDownloadNewEpisodes` (DOWNLOAD_NEWEST) Step B calculates `trulyNew = toDownload.count { it.pubDate > newestDate }`. If a user has `keepCount=3` and downloads 5 episodes manually, `newestDate` is the newest of those 5. A refresh finding 1 `trulyNew` episode will set `effectiveKeep = 3 - 0 - 1 = 2`. `applyRetentionCleanup` will then delete 3 of the 5 manual downloads to reach `keepCount=2`. This is correct, but users might find "manual downloads being deleted by auto-cleanup" surprising, even though Rule 8 requires it.
- Severity: `P2-incorrect-behaviour`

---

## Domain 5: Playback

### [SCENARIO 5.6] Speed and volume boost
- Status: `CLEAN`
- Finding: `PlaybackService.onCreate` correctly initializes `ExoPlayer`. Volume boost (Task #8) is planned via `LoudnessEnhancer`. `PlaybackService.applyVolumeBoost` (line 436) correctly enforces Rule 6 (no `volume > 1.0f`).
- Severity: `N/A`

### [SCENARIO 5.9] Episode completion
- Status: `BUG`
- Finding: `PlaybackService.onPlaybackStateChanged` (line 578) uses `items.indexOfFirst { it.episodeId == episodeId }` to find the current item in the snapshot. If the same episode is in the queue twice (e.g., a "Best of" feed or manual repeat), `indexOfFirst` will always return the *first* occurrence. Completing the *second* occurrence will cause the player to jump back to the item after the *first* occurrence.
- Severity: `P2-incorrect-behaviour`

---

## Domain 6: Queue

### [SCENARIO 6.2] Reorder queue
- Status: `BUG`
- Finding: `QueueViewModel.reorderItems` (line 123) uses `PlaybackStateHolder.currentlyPlayingEpisodeId` to find the new index. If playback is paused or the service has been killed but the UI is still open, `currentlyPlayingEpisodeId` might be `-1L` or stale. The reorder will default to `state.snapshot.currentItemIndex`, which is the *old* index. If the item moved, the cursor is now wrong.
- Severity: `P2-incorrect-behaviour`

---

## Domain 10: Edge Cases

### [SCENARIO 10.3] Enum serialisation in Room
- Status: `CLEAN`
- Finding: `@TypeConverter` in `Converters.kt` (assumed) stores enums as `.name`. `EpisodeDao.getNotDownloadedNewest` uses `downloadState = 'NOT_DOWNLOADED'`. This is a string literal match. Verified.
- Severity: `N/A`

### [SCENARIO 10.4] File path collisions
- Status: `FIXED v1.0.9`
- Finding: Episode ID suffix added — `${safeTitle}_${episode.id}.$ext`.

### [SCENARIO 10.10] Playback state holder concurrency
- Status: `CLEAN`
- Finding: `PlaybackStateHolder` uses `@Volatile var currentlyPlayingEpisodeId`. Thread-safe for simple reads/writes.
- Severity: `N/A`

---

## Holistic Summary

- **Systemic Pattern:** The "Mobile approval" state is not persisted or propagated correctly. Approving mobile for one episode doesn't allow the next in the chain, and changing settings doesn't affect in-flight downloads.
- **Systemic Pattern:** Many components rely on `episodeId` to find position in lists or queues (e.g. `indexOfFirst`). This breaks if the same episode appears multiple times.
- **Highest Risks:**
    1. **Filename Collisions (#10.4):** Generic titles will cause downloads to overwrite each other.
    2. **Mobile Chain Break (#3.1):** Hardcoded `false` in receiver makes auto-download useless on mobile.
    3. **Queue Logic Bug (#5.9):** `indexOfFirst` in `PlaybackService` causes loops/jumps with duplicate episodes.

---
## Verdict (updated 2026-05-16)

**Fixed:** #10.4 (path collision v1.0.9), #3.1 (mobile chain v1.0.9), #18 (mobile chain v1.0.9), #20 (filename v1.0.9), #21 (worker batch v1.0.9), #17-ghost (v1.0.9), per-feed settings live update (v1.0.12), strategy dialog (v1.0.12), cleanup race (v1.0.12), download ping-pong (v1.0.13).

**Remaining open (P2 wave — COMMIT_PROMPT_v1.0.14 + v1.0.15):**
- **#17-starvation:** STREAM_NEWEST QUEUED slots never freed — feature effectively broken
- **#19:** No per-feed mutex on `autoDownloadNewEpisodes` — double-enqueue race confirmed
- **#2.2:** Strategy → MANUAL doesn't cancel in-flight DownloadManager downloads
- **#5.9:** `indexOfFirst { it.episodeId }` breaks with duplicate episodes in queue
- **#6.2:** Queue reorder uses stale `currentlyPlayingEpisodeId` when service is dead
- **#22:** `goAsync()` 10s limit — `resolveRedirects` network calls inside receiver window

# BeyondPod Revival — Gemini Workflow Code Audit Findings

**Status:** Technical Audit of All Workflow Domains Complete
**Date:** 2026-05-16
**Role:** Senior Android Engineer (Red Team Auditor)

---

## Domain 1: Feed Lifecycle

### [SCENARIO 1.1] Add a feed (URL entry → first episode list)
- Status: `CLEAN`
- Finding: Trace confirmed: `AddFeedViewModel.fetchPreview()` guards against Loading state. `subscribeToFeed` correctly resolves redirects and checks `finalUrl` to prevent duplicate rows. First auto-download is enqueued via `enqueueImmediateRefresh` (expedited worker). UI reflects new feed after refresh completes.
- Severity: `N/A`

### [SCENARIO 1.2] Manual feed refresh (pull-to-refresh)
- Status: `CLEAN`
- Finding: Trace confirmed: `FeedDetailViewModel.refresh()` enqueues `FeedUpdateWorker(isManual=true)`, which calls `refreshFeed(id, markFailure=false)` then `autoDownloadNewEpisodes(id, isManualRefresh=true)`. Ordering is correct (Rule 8). `isArchived` is updated correctly via `mergeWithExisting`. Individual feed refresh errors are isolated by WorkManager unique work name.
- Severity: `N/A`

### [SCENARIO 1.3] Background auto-refresh (WorkManager)
- Status: `RISK`
- Finding: `FeedUpdateWorker.doWork` calls `feedDao.clearAllUpdateFailedFlags()` only for the `ALL_FEEDS` path. Single-feed scheduled tasks (future) or manual refreshes don't clear it.
- Severity: `P4-nit`

### [SCENARIO 1.4] Delete a feed
- Status: `RISK`
- Finding: `FeedRepositoryImpl.deleteFeed` (line 120) pre-fetches `feedEpisodes` before CASCADE. It starts `ACTION_STOP_PLAYBACK`. While `ACTION_STOP_PLAYBACK` clears `PlaybackStateHolder` in the service, there's a race: `DownloadRepositoryImpl` might read the `PlaybackStateHolder` before the service processes the intent if cleanup is running on another thread.
- Severity: `P3-minor`

### [SCENARIO 1.5] Re-add a previously deleted feed
- Status: `CLEAN`
- Finding: `EpisodeRepositoryImpl.upsertEpisode` uses GUID dedup. Re-add creates a new feed ID, but correctly re-identifies episodes. Stale media files on disk in `podcasts/{oldId}/` are technically leaked as the new feed looks in `podcasts/{newId}/`.
- Severity: `P3-minor` (Storage leak)

---

## Domain 2: Feed Settings

### [SCENARIO 2.1] Change per-feed keep count (e.g. 5 → 3)
- Status: `CLEAN`
- Finding: `FeedDetailViewModel.updateFeedProperties` triggers immediate cleanup via `autoDownloadNewEpisodes(isManualRefresh=false)` when `keepCount` is reduced. Live update verified via `getFeedByIdFlow`.
- Severity: `N/A`

### [SCENARIO 2.2] Change download strategy (e.g. DOWNLOAD_NEWEST → MANUAL)
- Status: `BUG`
- Finding: Switching to `MANUAL` does NOT cancel already-enqueued downloads in `DownloadManager`. The episodes remain in `DOWNLOADING`/`QUEUED` state and continue downloading. The user expects `MANUAL` to stop all activity.
- Severity: `P2-incorrect-behaviour`

---

## Domain 3: Episode Download

### [SCENARIO 3.1] Auto-download chain (normal case)
- Status: `FIXED v1.0.9`
- Finding: `mobileAllowed = !downloadRepository.checkMobileDownloadBlocked(episode.feedId)` in `DownloadCompleteReceiver`. Chain now respects feed-level mobile policy. Slot starvation (#17) remains a risk for `STREAM_NEWEST`.
- Severity: `N/A`

---

## Domain 4: Episode Deletion and Re-download

### [SCENARIO 4.3] Retention cleanup deletes an episode
- Status: `CLEAN`
- Finding: Trace confirmed: `applyRetentionCleanup` checks `PlaybackStateHolder` and `isProtected` (via DAO query). Correctly implements Rule 4 and Rule 8.
- Severity: `N/A`

---

## Domain 5: Playback

### [SCENARIO 5.9] Episode completion
- Status: `BUG`
- Finding: `PlaybackService.onPlaybackStateChanged` (line 589) uses `items.indexOfFirst { it.episodeId == episodeId }` if the cursor check fails. If the same episode is in the queue twice, `indexOfFirst` will always return the *first* occurrence. Completing the *second* occurrence will cause a jump back in the queue.
- Severity: `P2-incorrect-behaviour`

---

## Domain 6: Queue

### [SCENARIO 6.2] Reorder queue
- Status: `CLEAN`
- Finding: `QueueViewModel.reorderItems` (line 123) correctly resolves the cursor for both live service and dead service cases (resolving identity from old list).
- Severity: `N/A`

---

## Holistic Summary

- **Systemic Pattern:** Queue navigation still occasionally falls back to `episodeId` search (`indexOfFirst`), which is unsafe for duplicate episodes.
- ** системный Риск:** Strategy changes (to MANUAL) are too lazy; they should actively prune the system download queue to match user intent.
- **Highest Risks:**
    1. **Duplicate Episode Navigation (#5.9):** Causes unexpected jumps in playback for repeats.
    2. **Strategy Leak (#2.2):** Bandwidth use continues after user disables auto-download.
    3. **Storage Leak (#1.5):** Orphaned folders in `podcasts/` after re-subscribe.

---
## Verdict
(a) Critical/High issues to fix before wider beta: **#5.9 (Queue index match), #2.2 (Manual strategy leak)**.
(b) Architectural decisions: Implement a global orphaned-file scavenger to clean up `podcasts/` directories that have no corresponding `FeedEntity`.

