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
