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

## 🔍 Observations — Accepted / Won't Fix

### 8. Double Fetch on Subscribe [G1]
- **Status:** **DEFERRED** — requires UX refactor of `AddFeedViewModel` preview step. Accepted for now.

### 9. `isProtected` Veto in Bulk Delete
- **Design decision:** `isProtected` is the auto-deletion veto — it guards against automated cleanup/retention processes. Manual unsubscribe with "delete downloads" is explicit user intent. Furthermore, since the DB row is CASCADE-deleted, preserving the file would create an unmanaged orphan with no record pointing to it — strictly worse. Current behaviour is correct. Architecture non-negotiable #4 applies to *auto-deletion* only; manual delete is an intentional exception. Documented, no code change required.
