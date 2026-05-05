# Red Team QA Audit — Critical Build Notes

**Status:** Technical Deep Dive Audit
**Date:** 2024-05-04
**Role:** Red Team Auditor

This document highlights critical architectural and stability risks. These items are synthesized from a code-level review compared against `FEED_DOWNLOAD_AUDIT.md` and `FIX_BACKLOG.md`.

---

## 🛑 Severity: High (Data Integrity & UX Breaking)

### 1. Resource Leaks (Orphan Files) [G11]
- **Issue:** `FeedRepositoryImpl.deleteFeed` does NOT cancel active `DownloadManager` jobs.
- **Verification:** `FeedRepositoryImpl.kt` lacks `DownloadManager` injection and removal calls.
- **Impact:** Files continue to download in the background after the feed is deleted. Since the DB row is gone (CASCADE delete), these files become "orphans" — unmanaged by the app, invisible to cleanup, and wasting user storage.

### 2. The "Ghost" Playback Loop [G12]
- **Issue:** Playback is not stopped when a feed is deleted.
- **Verification:** Although `PlaybackService` supports `ACTION_STOP_PLAYBACK`, nothing calls it during the delete flow in `FeedRepositoryImpl`.
- **Impact:** If a user unsubscribes while listening, playback continues. Position saves and state updates become DAO no-ops. 

### 3. Queue Reorder Logic Bug [Q5]
- **Issue:** `QueueViewModel.reorderItems` copies the `currentItemIndex` from the old snapshot.
- **Verification:** `val snapshot = state.snapshot.copy(..., currentItemIndex = state.snapshot.currentItemIndex, ...)`
- **Impact:** If the currently playing item changes its position in the list due to a reorder, the `currentItemIndex` will point to the *wrong episode* in the new snapshot. The cursor must be updated to the new index of the active `episodeId`.

### 4. Queue-First Principle Violation (Auto-Advance) [QE4]
- **Issue:** `PlaybackService` auto-advance is feed-based, not queue-based.
- **Verification:** `onPlaybackStateChanged` calls `episodeRepository.getNextNewerEpisode(finished.feedId, ...)` instead of looking up the next item in the active `QueueSnapshot`.
- **Impact:** The "Queue is a frozen snapshot" rule is ignored during playback. If a user builds a manual queue, the player will still just follow the feed order when an episode ends. This is a core architecture deviation.

---

## ⚠️ Severity: Medium (Network & Performance)

### 5. Double Fetch on Subscribe [G1]
- **Issue:** `subscribeToFeed` performs a full RSS fetch and parse before the worker runs.
- **Verification:** `FeedRepositoryImpl.subscribeToFeed` calls `fetchAndParse(url)`.
- **Impact:** Wasteful network use and potential race conditions with the `FeedUpdateWorker` which performs a second fetch immediately after.

### 6. Missing Error-Skip Logic [Q12]
- **Issue:** `PlaybackService.onPlayerError` stops playback but doesn't auto-skip on content errors.
- **Verification:** Code in `onPlayerError` only stops the player. The planned skip logic is missing.
- **Impact:** A single bad URL or server error kills the entire playback session rather than advancing to the next queued item.

---

## ✅ Verified Fixed (Passed Audit)

### 7. Retention Cleanup "Friendly Fire" [Q9]
- **Verification:** `DownloadRepositoryImpl.applyRetentionCleanup` correctly checks `PlaybackStateHolder.currentlyPlayingEpisodeId` and skips the active file.

### 8. Category Deletion Integrity [E11]
- **Verification:** `CategoryRepositoryImpl.deleteCategory` correctly nullifies `primaryCategoryId` and `secondaryCategoryId` in the `feeds` table and prunes playlist blocks.

### 9. Zero-Item Queue State [QE6]
- **Verification:** `QueueViewModel` correctly emits `QueueUiState.Empty` if an active snapshot has zero items.

---

## 🚀 Recommended Immediate Actions (Pre-Beta)
1. **Fix `QueueViewModel.reorderItems`**: Resolve the `currentItemIndex` by finding the new position of the playing episode.
2. **Handoff Auto-Advance to Snapshot**: Refactor `PlaybackService` to observe the active snapshot and advance using `currentItemIndex + 1`.
3. **Bridge Unsubscribe to Service**: Ensure `DeleteFeedUseCase` (or Repository) triggers `ACTION_STOP_PLAYBACK` and cancels `DownloadManager` IDs.
