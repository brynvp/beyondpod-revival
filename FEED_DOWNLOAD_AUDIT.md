# Feed & Download Workflow Audit

> Working document for systematic review. Each section defines **expected behaviour**, then
> **what the code actually does**, then **gaps/risks** identified.  
> Fix decisions go in a separate session — this doc is diagnosis only.

---

## 1. New Feed Added

### Expected behaviour
1. Validate URL is reachable and is RSS/Atom.
2. Deduplicate — if feed already subscribed, return existing without any changes.
3. Fetch and parse RSS. Persist final redirected URL (not the original if a 301 occurred).
4. Create `FeedEntity` with sensible defaults (downloadStrategy=GLOBAL, no per-feed overrides).
5. Upsert all episodes from the initial fetch using multi-key dedup (GUID → URL → Title+Duration).
6. All episodes start as `NOT_DOWNLOADED`, `playState=NEW`.
7. Trigger `autoDownloadNewEpisodes(isManualRefresh=true)` — download the newest N episodes
   where N = `keepCount` (global or per-feed), never the entire backlog.
8. No cleanup needed on first subscribe (nothing to delete yet).

### What the code does
- `subscribeToFeed(url)` deduplicates by URL ✓
- Fetches RSS via `fetchAndParse`, creates `FeedEntity`, upserts episodes ✓
- Redirected URL IS persisted (handled in `refreshFeed`, but **not** in `subscribeToFeed` initial fetch — placeholder uses the input URL, not the final URL after redirects)
- After subscribe, caller (`AddFeedViewModel`, `PodcastSearchViewModel`) triggers
  `FeedUpdateWorker(KEY_IS_MANUAL=true)` which calls `refreshFeed` + `autoDownloadNewEpisodes` ✓
- `downloadLimit = keepCount ?: Int.MAX_VALUE` (manual refresh path) ✓

### Gaps / Risks
- **G1** `subscribeToFeed` does its own RSS fetch but ignores redirect. The worker then does a second full fetch via `refreshFeed`. Two fetches on subscribe — wasteful and the placeholder `FeedEntity` sits in DB with wrong URL until the second fetch completes.
- **G2** If the worker's second fetch fails, episodes from the first fetch exist but the feed URL is still the pre-redirect one. Future refreshes may keep bouncing through redirects.
- **G3** Episode state on first subscribe: all episodes arrive as `NOT_DOWNLOADED`/`NEW`. If the feed has 500 episodes, `getNotDownloadedNewest(feedId, keepCount)` correctly limits to `keepCount` — but ALL 500 episode rows are in DB. No pruning of old backlog rows ever happens. DB bloat over time.
- **G4** No validation that the URL is actually RSS before creating the `FeedEntity`. A 200 HTML page creates a feed stub with no title and no episodes, and it sits as a dead entry.

---

## 2. Feed Updated — Background (Scheduled Worker)

### Expected behaviour
1. Worker fires every N hours (configured interval, network constraint optional).
2. For each feed: fetch RSS, parse, upsert new episodes (dedup), archive removed episodes.
3. Feed metadata updated (title, artwork, description) if changed upstream.
4. Cleanup: Step A age-based, Step B retention count — both **before** downloads (rule #8).
5. Download: only enqueue new episodes up to `downloadCount` per-cycle cap (NOT `keepCount`).
6. Background failures are silent — no error chevron stamped.
7. Virtual feeds: scan folder, not RSS fetch.
8. ALL_FEEDS pass: clear stale `lastUpdateFailed` flags first.

### What the code does
- `FeedUpdateWorker.doWork()` handles ALL_FEEDS vs single feed ✓
- `markFailure=false` for background runs ✓
- `isManualRefresh=false` → `downloadLimit=downloadCount` (per-cycle cap respected) ✓
- Virtual feeds early-return to `scanFolderFeed` ✓
- `clearAllUpdateFailedFlags()` on ALL_FEEDS run ✓
- Cleanup-before-download order enforced inside `autoDownloadNewEpisodes` ✓

### Gaps / Risks
- **G5** `archiveRemovedEpisodes` marks episodes as `isArchived=1` but they remain fully visible in the UI feed list with `NOT_DOWNLOADED` state. No UI treatment for archived episodes — they look like normal unplayed episodes. Should be hidden or visually distinct.
- **G6** If ALL_FEEDS worker run fails mid-way (one feed throws), `Result.retry()` re-runs ALL feeds including ones that already succeeded. No per-feed checkpoint. Feeds processed before the failure get double-processed on retry (upsert is idempotent so no data corruption, but unnecessary network traffic).
- **G7** Worker retry logic: `if (runAttemptCount < 3) Result.retry()` — an exception from ONE bad feed causes the entire ALL_FEEDS job to retry 3 times. A permanently broken feed (e.g., dead URL) will cause worker retries indefinitely until WorkManager gives up.
- **G8** `autoDownloadNewEpisodes` is called even when `refreshFeed` returns failure in the worker (line 92 runs regardless of line 81's result). So cleanup runs on a stale snapshot when the fetch failed. Low risk in background since no new episodes to interact with, but inconsistent with the manual refresh fix.

---

## 3. Feed Updated — Manual Pull-to-Refresh

### Expected behaviour
1. User pulls down on FeedDetailScreen.
2. Fetch RSS → upsert → archive (same as background).
3. On success: run `autoDownloadNewEpisodes(isManualRefresh=true)`.
   - `downloadLimit = keepCount` (catch up to window in one pull, not per-cycle cap).
   - Only count episodes genuinely newer than current window when shrinking keep threshold.
4. On failure: show error chevron, DO NOT touch downloads.
5. WiFi-only warning: if device is on mobile and WiFi-only is enabled, show dialog before downloading.

### What the code does
- `FeedDetailViewModel.refresh()` now correctly gates `autoDownloadNewEpisodes` behind `refreshResult.isSuccess` ✓ (fixed this session)
- `markFailure=true` → error chevron on failure ✓
- `checkMobileDownloadBlocked` → mobile warning dialog ✓
- `isManualRefresh=true` → `downloadLimit=keepCount` ✓
- `trulyNew` calc prevents backlog from triggering mass deletion ✓ (fixed this session)

### Gaps / Risks
- **G9** `confirmMobileDownload()` calls `autoDownloadNewEpisodes(mobileAllowed=true)` but does NOT re-check if the fetch actually succeeded. If the RSS fetch failed (error chevron showing) and the user happened to be on mobile, the dialog could theoretically appear and trigger downloads on a stale episode list. Low probability but inconsistent.
- **G10** Rapid pull-to-refresh: user pulls twice quickly. Two coroutines both call `refreshFeed` then `autoDownloadNewEpisodes`. The `countInFlightDownloads` check prevents double-enqueuing downloads ✓, but `refreshFeed` runs twice — second upsert is idempotent so no data damage, just wasted network.

---

## 4. Feed Unsubscribed

### Expected behaviour
1. User confirms unsubscribe dialog.
2. Cancel any in-progress DownloadManager downloads for this feed's episodes.
3. Delete all local audio files for downloaded episodes.
4. Delete `FeedEntity` from DB.
5. Cascade delete: all `EpisodeEntity` rows for this feed (FK CASCADE).
6. Cascade delete: `FeedCategoryCrossRef` rows (FK CASCADE).
7. Remove feed from any `SmartPlaylistBlock` (SEQUENTIAL_BLOCKS playlists).
8. Feed category entity itself is NOT deleted — only the cross-ref (rule #7).
9. If any episode is currently playing, stop playback gracefully.

### What the code does
- File deletion loop ✓
- `feedDao.deleteFeed(feed)` ✓
- FK CASCADE on episodes: **NOW enabled** (PRAGMA foreign_keys = ON added this session) ✓
- FK CASCADE on `FeedCategoryCrossRef` ✓
- `prunePlaylistBlocksForFeed` removes from SEQUENTIAL_BLOCKS playlists ✓
- Category entity NOT deleted ✓

### Gaps / Risks
- **G11** **Active DownloadManager downloads are NOT cancelled.** `downloadManager.remove(dmId)` is never called during unsubscribe. The system download continues in the background, completes, fires `ACTION_DOWNLOAD_COMPLETE`, and `DownloadCompleteReceiver` tries to look up the episode by `downloadManagerId`. The episode row is gone (CASCADE deleted), so `getEpisodeByDownloadManagerId` returns null, and the receiver silently exits — file lands in the podcasts folder with no DB entry referencing it. Orphan file on disk.
- **G12** **Currently playing episode not checked.** If the unsubscribed feed's episode is playing, playback continues. The `PlaybackService` holds a reference to `episodeId` — next position save, next play-state update, or next auto-advance will call DAOs against a deleted row. Silent failure in the DAO (no-op UPDATE on non-existent row), but the mini-player keeps showing the episode and the service stays live.
- **G13** `deleteDownloads=true` is hardcoded in `FeedDetailViewModel.deleteFeed()` — no option to keep downloaded files on unsubscribe. Original BeyondPod offered "keep downloads". Low priority but noted.
- **G14** FILTER_RULES playlists (SmartPlay Advanced mode) reference feeds via rule conditions, not `SmartPlaylistBlock`. `prunePlaylistBlocksForFeed` only handles SEQUENTIAL_BLOCKS. Deleted feed's rules silently remain in FILTER_RULES playlists and produce empty results without error.

---

## 5. Global Settings Changed

### Expected behaviour
- `GLOBAL_MAX_KEEP` changed → immediately apply retention cleanup across all feeds.
- `GLOBAL_DELETE_OLDER_THAN_DAYS` changed → immediately apply age cleanup across all feeds.
- `DOWNLOAD_ON_WIFI_ONLY` changed → no immediate action; takes effect on next enqueue.
- `GLOBAL_DOWNLOAD_COUNT` changed → no immediate action; takes effect on next refresh.
- `AUTO_DELETE_PLAYED` changed → no immediate action; takes effect when next episode finishes.
- `UPDATE_INTERVAL_HOURS` / `AUTO_UPDATE_ENABLED` → reschedule WorkManager periodic job.
- `UPDATE_ON_WIFI_ONLY` → reschedule WorkManager with updated network constraint.

### What the code does
- `setGlobalMaxKeep` → `runGlobalRetentionCleanup()` ✓ (added this session)
- `setGlobalDeleteOlderThanDays` → `runGlobalRetentionCleanup()` ✓ (added this session)
- `setAutoUpdateEnabled` / `setUpdateIntervalHours` / `setUpdateOnWifiOnly` → reschedule ✓
- `setDownloadOnWifiOnly` → DataStore write only, no side effects ✓ (correct — next enqueue reads it)

### Gaps / Risks
- **G15** `runGlobalRetentionCleanup()` runs on the main `viewModelScope`. If there are hundreds of feeds with many episodes, this is a potentially long-running DB + file I/O operation on the UI-bound scope. Should run on `Dispatchers.IO` or be delegated to a one-shot WorkManager job. Current implementation will block the coroutine dispatcher pool.
- **G16** No feedback to the user that cleanup is running or completed. User changes "keep 3" from 5, nothing visible happens. Suggest a toast or snackbar "Cleaned up X episodes".
- **G17** `DOWNLOAD_ON_WIFI_ONLY` change does not cancel or pause existing DOWNLOADING episodes that are currently running over mobile (if somehow they got through). DownloadManager requests already in flight have their network type baked in at enqueue time.
- **G18** Per-feed `downloadOnlyOnWifi` overrides are NOT re-evaluated when `DOWNLOAD_ON_WIFI_ONLY` global changes. Only affects feeds where `feed.downloadOnlyOnWifi == null` (using global default). This is correct behaviour but worth stating explicitly.

---

## 6. Per-Feed Settings Changed

### Expected behaviour
- `downloadStrategy` changed → takes effect on next refresh for this feed only.
- `downloadCount` changed → takes effect on next refresh.
- `maxEpisodesToKeep` changed → immediate cleanup for this feed (reduce) or no-op (increase).
- `downloadOnlyOnWifi` changed → takes effect on next enqueue.
- `playbackSpeed` / `skipIntroSeconds` etc. → takes effect on next play.
- `url` changed → takes effect on next refresh (fetches from new URL).

### What the code does
- `updateFeedProperties(feed)` → `feedDao.upsertFeed(feed)` — persists the whole entity ✓
- No side effects triggered — all changes are lazy ✓

### Gaps / Risks
- **G19** `maxEpisodesToKeep` reduced per-feed (e.g., 10 → 3) does NOT immediately clean up. Unlike the global setting which now triggers `runGlobalRetentionCleanup()`, a per-feed keepCount reduction sits dormant until the next refresh of that specific feed. User expects immediate effect.
- **G20** `url` changed but old downloaded files are in a folder named by the old `feedId` (which doesn't change). No migration needed — the folder path is `podcasts/{feedId}` so it's unaffected. ✓ But the old URL's redirect state is gone — if the new URL also redirects, it'll be chased again and persisted correctly on next refresh.
- **G21** Changing `downloadStrategy` from DOWNLOAD_NEWEST to MANUAL does not cancel in-flight downloads for this feed. Episodes already DOWNLOADING continue.

---

## 7. Edge Cases

### E1 — Episode with missing or zero pubDate
- **Expected:** Episode still upserted; sorted to bottom of feed list; never selected by `getNotDownloadedNewest` unless it's the only episode.
- **Actual:** `ORDER BY pubDate DESC` puts epoch-zero episodes at the bottom ✓. `getNotDownloadedNewest` would return them last. Low risk, but they'd persist in DB indefinitely as they'd never be "newest".

### E2 — Feed has duplicate episodes (same GUID, different URL)
- **Expected:** GUID wins — upsert finds existing row by GUID, updates URL but preserves play/download state.
- **Actual:** `episodeRepository.upsertEpisode` uses `@Upsert` on GUID+feedId index. If GUID matches, row is updated ✓. URL-only dedup (no GUID) handled as separate lookup. Title+Duration heuristic exists in `findPotentialDuplicates` but is NOT called in the main upsert path — it's available but unused. **Gap: Title+Duration dedup is dead code.**

### E3 — Protected episode during retention cleanup
- **Expected:** `isProtected=true` episodes are never deleted by any cleanup path.
- **Actual:** `getAllDownloadedNonProtected` DAO query excludes `isProtected=1` ✓. `deleteDownload` has explicit `require(!episode.isProtected)` check ✓. `batchDeleteDownloads` skips protected ✓. Rule #4 enforced at all known call sites.

### E4 — App killed mid-download
- **Expected:** DownloadManager continues independently (system process). On next app start, episode is still `DOWNLOADING` in DB. `DownloadCompleteReceiver` fires when done and updates DB to `DOWNLOADED`.
- **Actual:** DownloadManager is OS-managed ✓. However: if the app is force-killed AND the device reboots before download completes, `DownloadManager` may lose the pending download depending on Android version. Episode stays `DOWNLOADING` in DB forever. **No stale-DOWNLOADING recovery on app start.**

### E5 — WiFi drops during active download
- **Expected (WiFi-only feeds):** DownloadManager pauses download. Resumes when WiFi returns. Episode stays `DOWNLOADING` in DB.
- **Expected (mobile-allowed feeds):** DownloadManager switches to mobile and continues.
- **Actual:** `setAllowedNetworkTypes` now correctly set per `wifiOnly` flag ✓ (fixed this session). DownloadManager handles pause/resume natively ✓.

### E6 — Multiple rapid refreshes (race condition)
- **Expected:** Second refresh sees the same episode list; `countInFlightDownloads` prevents double-enqueue.
- **Actual:** `countInFlightDownloads` prevents extra enqueues ✓. Two concurrent `refreshFeed` calls are both idempotent upserts — last writer wins on feed metadata, no data loss. **Risk:** `applyRetentionCleanup` could run twice concurrently on the same episode list, with both instances seeing the same allDownloaded snapshot. Double-delete attempt on a file is harmless (`if (file.exists()) file.delete()`). Double `updateDownloadState` to DELETED on the same row is idempotent. Low risk.

### E7 — Feed returns 0 new episodes on refresh
- **Expected:** No downloads enqueued, no cleanup beyond what's already been done. No-op.
- **Actual:** `toDownload = []`, `trulyNew = 0`, `effectiveKeep = keepCount`, `drop(keepCount)` deletes nothing if already within window ✓.

### E8 — keepCount=0 (unlimited retention)
- **Expected:** `effectiveKeepCount` returns null. No retention cleanup ever runs for this feed.
- **Actual:** `effectiveKeepCount` returns null when value is 0 ✓. `if (keepCount != null)` gate skips Step B entirely ✓.

### E9 — Feed with keepCount < currently downloaded count, no new episodes
- **Expected:** On next refresh, cleanup trims to keepCount. No downloads (nothing new).
- **Actual:** `toDownload = []`, `trulyNew = 0`, `effectiveKeep = keepCount - 0 - 0 = keepCount`. `allDownloaded.drop(keepCount)` deletes the excess ✓.

### E10 — isProtected episode is the "oldest" in the retention window
- **Expected:** Protected episode is skipped; the NEXT-oldest non-protected is deleted instead. You may end up with `keepCount + 1` episodes on disk if the oldest is protected.
- **Actual:** `getAllDownloadedNonProtected` excludes protected rows from the list entirely. So `drop(keepCount)` operates only on the non-protected list. The protected episode exists outside the count. This means `keepCount=3` with 1 protected + 3 non-protected = 4 on disk, not 3. **This is the correct behaviour** (rule #4), but it means keepCount is a soft limit when protected episodes exist. Worth documenting, not fixing.

### E11 — Category deleted while feeds are assigned
- **Expected:** Feeds move to Uncategorized, not deleted (rule #7).
- **Actual:** `FeedCategoryCrossRef` has FK CASCADE on `categoryId` — cross-ref rows deleted when category deleted ✓. `FeedEntity.primaryCategoryId` is a denormalised copy — **not updated when the cross-ref is CASCADE-deleted**. Feed's `primaryCategoryId` column still holds the deleted category's ID. Feeds screen filters by cross-ref (not `primaryCategoryId`), so visually they appear uncategorised ✓. But `FeedEntity.primaryCategoryId` is stale. Any code reading `feed.primaryCategoryId` directly gets a dangling ID.

### E12 — OPML import of feed that already exists
- **Expected:** Skip feed creation, still assign category if provided.
- **Actual:** `importFromOpml` checks `feedDao.getFeedByUrl(xmlUrl)` — if found, uses existing ID. Category assignment still runs ✓. But: if the existing feed has a DIFFERENT URL (e.g., HTTP vs HTTPS) it won't be detected as a duplicate. Same feed, different protocol → duplicate entry.

### E13 — Very large feed (500+ episodes)
- **Expected:** Initial subscribe and subsequent refreshes are performant. DB doesn't grow unbounded.
- **Actual:** All 500 episodes are upserted and stored. `getNotDownloadedNewest` and `getAllDownloadedNonProtected` are indexed on `(feedId, downloadState)` and `(feedId, pubDate)` ✓ — queries are fast. But episode records accumulate indefinitely — `deletePlayedNonDownloadedEpisodes` exists but is never called automatically. **No automatic pruning of old PLAYED/NOT_DOWNLOADED/DELETED episode DB rows.** Only files are cleaned up; rows stay.

---

## 8. Queue System

### 8.1 Queue Generation

#### Expected behaviour
1. User explicitly triggers queue build — from SmartPlay, My Episodes, or manual drag-to-queue.
2. The queue is a **frozen snapshot** at generation time. Feed updates, new downloads, and rule changes never mutate the active queue.
3. Snapshot captures `episodeId`, `position`, and resilience fields: `episodeTitleSnapshot`, `feedTitleSnapshot`, `localFilePathSnapshot`, `episodeUrlSnapshot` — all cached from the episode at generation time.
4. Previous active snapshot is atomically deactivated before the new one is inserted (single `@Transaction`).
5. The snapshot survives episode/feed deletion — `episodeId` is intentionally NOT a FK.

#### What the code does
- `QueueSnapshotEntity` + `QueueSnapshotItemEntity` model: frozen snapshot ✓
- `replaceActiveSnapshot` is a `@Transaction`: `deactivateAllSnapshots()` then `insertSnapshot` + `insertItems` ✓
- `episodeId` has no FK constraint on `episodes.id` ✓ — intentional
- Resilience fields (`localFilePathSnapshot`, `episodeUrlSnapshot`) captured at snapshot build time ✓
- `BuildQueueSnapshotUseCase` delegates to `episodeRepository.buildQueueSnapshot` ✓

#### Gaps / Risks
- **Q1** **`localFilePathSnapshot` is never re-validated after snapshot is built.** If an episode file is subsequently deleted by retention cleanup (file removed, state → DELETED), `localFilePathSnapshot` still points to the now-missing path. `PlaybackService.loadAndPlay` checks `File(it).exists()` and falls back to stream URL ✓ — but the queue UI shows no indication that the file is gone. User sees "downloaded" indicator on a queue item that will actually stream.
- **Q2** **Snapshot generation doesn't check WiFi/mobile state.** A stream-only episode (`localFilePath = null`, `episodeUrlSnapshot` is the stream URL) is added to the queue regardless of network constraints. The streaming will begin over mobile even for feeds with `downloadOnlyOnWifi = true`. WiFi gating applies to *downloads* via `DownloadManager.setAllowedNetworkTypes` — it has no equivalent for live streaming through ExoPlayer.
- **Q3** **No maximum queue size enforced.** A `SmartPlaylist` with FILTER_RULES matching 500+ episodes generates a 500-item snapshot. No cap. DB bloat + possible OOM if the UI tries to render all items.
- **Q4** **`sourcePlaylistId` not verified on snapshot build.** If the source SmartPlaylist is deleted after snapshot generation, `sourcePlaylistId` becomes a dangling reference (no FK). UI code that tries to display "generated from playlist X" will get a null playlist name. No crash, but the label is empty/unknown.

---

### 8.2 Queue Mutations

#### Expected behaviour
- **Remove item:** Swipe-to-remove from queue UI removes that episode from the snapshot without rebuilding it. Does NOT mark episode as played or change `downloadState`.
- **Reorder:** Drag-and-drop reorders items. Produces a new snapshot atomically (old deactivated, new inserted with re-indexed positions).
- **Clear queue:** Deactivates all snapshots. No downloads cancelled, no episode state changed.

#### What the code does
- `removeItem` → `removeItemsFromActiveSnapshot(listOf(episodeId))` ✓
- `reorderItems` → `replaceActiveSnapshot(snapshot.copy(id=0), items.mapIndexed { ... })` ✓
- `clearQueue` → `deactivateAllSnapshots()` ✓

#### Gaps / Risks
- **Q5** **`reorderItems` resets `currentItemIndex = 0`.** `snapshot.copy(id=0)` uses the default `currentItemIndex=0`. If the user reorders the queue while an episode is playing mid-queue (e.g., item at position 3 of 10), the snapshot's `currentItemIndex` resets to 0. On next auto-advance, `PlaybackService` reads position 0 instead of position 4 (the next after the currently-playing 3). **The user's playback cursor jumps to the beginning of the queue after any reorder.**
- **Q6** **`removeItemsFromActiveSnapshot` does not renumber positions.** After removing item at position 2 from a [0,1,2,3,4] queue, positions are [0,1,3,4] — a gap. The `ORDER BY position ASC` query still returns them correctly ordered. No functional bug but the sparse positions are inconsistent and could cause issues if any code assumes contiguous positions.
- **Q7** **`removeItem` does NOT update `currentItemIndex`.** If the currently-playing item (e.g., index 2) is removed from the queue by another UI path (e.g., a secondary screen), `currentItemIndex` in the snapshot still points to position 2. The item at position 2 is now the old position-3 item. PlaybackService would skip directly to that episode on auto-advance, which may or may not be the expected next episode.

---

### 8.3 Queue + Download Interaction

#### Expected behaviour
- Retention cleanup (file deletion) should never corrupt the in-progress queue.
- If a queued episode's file is deleted mid-playback (e.g., user manually deletes), playback should handle gracefully.
- Protected episodes in the queue should never have their files deleted.
- A new download completing while an episode is queued should update the queue item's playback source from stream URL to local file.

#### What the code does
- `PlaybackService.loadAndPlay`: `File(localFilePath).exists()` check → fall back to stream URL if file gone ✓
- `QueueSnapshotItemEntity.localFilePathSnapshot` is a snapshot — not live ✓ (resilience design)
- `isProtected` veto in `applyRetentionCleanup` via `getAllDownloadedNonProtected` ✓

#### Gaps / Risks
- **Q8** **No live linkage between `EpisodeEntity.localFilePath` and the queue.** `localFilePathSnapshot` is set at snapshot build time. If a download completes AFTER the queue is built, `localFilePathSnapshot` for that item is null (was not downloaded at snapshot time). `PlaybackService.loadAndPlay` reads `episodeRepository.getEpisodeById(episodeId)` live ✓ — so it WILL find the new file. The resilience fallback is unnecessary in this case and works correctly. **This is fine**, but the resilience path is the happy path for late-completing downloads, not just the failure path.
- **Q9** **Retention cleanup running mid-queue can delete a file that's actively streaming.** Scenario: user is streaming episode 3 from local file; background cleanup (triggered by Settings → "Clean up now" or `runGlobalRetentionCleanup`) deletes that file; ExoPlayer's buffered data plays for a while and then fails. ExoPlayer will surface a `PlaybackException`. **There is no guard preventing cleanup from deleting the currently-playing episode.** The `isProtected` flag prevents this for episodes the user has manually protected, but unprotected playing episodes have no runtime guard.
- **Q10** **Auto-download enqueue during active playback can trigger retention cleanup that deletes queued episodes.** `autoDownloadNewEpisodes` is called after every background refresh. If keepCount=3 and queue has episodes [A, B, C, D] all downloaded, a background refresh that finds 1 new episode triggers `applyRetentionCleanup` with `effectiveKeep=2` (keepCount=3 - inFlight=0 - trulyNew=1). Episode D (oldest) is soft-deleted. If D is still in the queue ahead of the currently-playing episode, the queue item for D now has no local file and streams. This is handled by the `File.exists()` fallback, but the user sees a downloaded episode in the queue that suddenly streams over mobile.

---

### 8.4 Queue + WiFi / Mobile Data

#### Expected behaviour
- Episodes downloaded to local files play regardless of network.
- Stream-only episodes (no local file, `episodeUrlSnapshot` is the URL) require network.
- If a stream-only episode hits a WiFi-only constraint, playback should either refuse to start or warn the user.
- ExoPlayer should not be permitted to stream over mobile for feeds marked WiFi-only.

#### What the code does
- `enqueueDownload` enforces `setAllowedNetworkTypes` based on `wifiOnly` flag ✓
- `checkMobileDownloadBlocked` is called before `autoDownloadNewEpisodes` on manual refresh ✓
- `PlaybackService.loadAndPlay` resolves URI as: local file if exists, else `episode.url` (stream) — no WiFi check applied here

#### Gaps / Risks
- **Q11** **`PlaybackService` never checks `downloadOnlyOnWifi` before streaming.** An episode with no local file (stream-only, or file deleted) will stream over mobile even if the feed has `downloadOnlyOnWifi = true`. The WiFi constraint currently applies only to `DownloadManager` enqueues — it does not gate ExoPlayer streaming. This is a correctness gap: a feed marked "WiFi only" should not stream over mobile either.
- **Q12** **No network availability check before queue auto-advance.** When the current episode finishes and `PlaybackService` advances to the next queue item, there is no network check. If the device is offline and the next item is stream-only, ExoPlayer receives an unresolvable URI and surfaces a `PlaybackException`. The `playerListener.onPlayerError` handler should trap this and skip to the next item, but there is no evidence of that logic in the code. **An offline device with stream-only queue items will stall, not skip.**
- **Q13** **`localFilePathSnapshot` vs `localFilePath` fallback priority.** `PlaybackService.loadAndPlay` reads `episodeRepository.getEpisodeById(episodeId)` and uses `episode.localFilePath`. The `localFilePathSnapshot` in the queue item is only read if the episode DB row is not found (deleted episode case). If the episode was soft-deleted (state=DELETED, row preserved, `localFilePath=null`), `loadAndPlay` uses `episode.url` (stream), correctly. If the episode was hard-deleted (row gone — which shouldn't happen given the architecture, but could via a bug), `localFilePathSnapshot` provides resilience ✓.

---

### 8.5 Queue Edge Cases

### QE1 — Episode's feed is unsubscribed while it's in the queue
- **Expected:** Queue item remains (episodeId is not a FK). On playback, `loadAndPlay` gets null from `getEpisodeById`. Falls back to `localFilePathSnapshot` if file still exists; then `episodeUrlSnapshot` for streaming; then marks unplayable and skips.
- **Actual:** `getEpisodeById` returns null ✓ (row CASCADE-deleted). `loadAndPlay` has `?: return@launch` guard — returns without playing. **No fallback to `localFilePathSnapshot` is implemented in `loadAndPlay`.** The spec-intended resilience path (try snapshot fields) is defined in the `QueueSnapshotItemEntity` KDoc but not wired in `PlaybackService`. The episode is silently skipped with no "File not available" snackbar.

### QE2 — Reorder while currently playing (index regression — Q5 elaborated)
- **Expected:** Reorder preserves the `currentItemIndex` so playback position in the queue is maintained after drag.
- **Actual:** `snapshot.copy(id=0)` uses the entity default `currentItemIndex=0`. The entire queue restart from item 0 after any reorder. Active playback isn't interrupted (service holds its own `currentEpisodeId`) but auto-advance after the current episode ends will go to queue position 0, not the correct next item.

### QE3 — Queue built from a playlist that is subsequently deleted
- **Expected:** Queue continues playing from its frozen snapshot. Playlist deletion has no effect on the active queue.
- **Actual:** `sourcePlaylistId` becomes a dangling reference (no FK). Queue plays fine ✓ — snapshot is self-contained. UI label for "queue source" will show unknown/empty ✓ (null coalesce). No crash.

### QE4 — New queue built while current queue is playing
- **Expected:** Old snapshot deactivated atomically; new snapshot becomes active. PlaybackService detects the active snapshot changed and either continues the current episode or switches to the new queue's first item (depending on implementation).
- **Actual:** `replaceActiveSnapshot` atomically deactivates old and inserts new ✓. `PlaybackService` holds `currentEpisodeId` independently — it does NOT observe the active snapshot. **The service continues playing the old episode after a new queue is built.** The queue UI will show the new snapshot but the service plays from the old context until the current episode ends or the user explicitly starts the queue. Auto-advance after the current episode ends will try to advance using whatever logic drives the next-episode selection — needs verification.

### QE5 — Queue item position 0 is already played
- **Expected:** On queue auto-advance, skip already-played episodes? Or play them regardless (queue is explicit)?
- **Actual:** Queue plays items in position order regardless of play state. This is correct for queues — the user explicitly ordered these items. A played episode in position 3 will play again if the queue reaches it. No issue.

### QE6 — Zero-item queue after all items removed
- **Expected:** UI shows empty state. No crash on auto-advance attempt.
- **Actual:** `QueueUiState.Empty` shown when no active snapshot ✓. After `removeItemsFromActiveSnapshot` empties the items table, the snapshot row still exists but has 0 items. The `getActiveSnapshot()` flow returns the snapshot (not null), but `getSnapshotItems` returns an empty list → `QueueUiState.Active(snapshot, emptyList())`. The empty state rendering depends on the UI treating an Active state with 0 items as empty — needs verification.

---

## Summary: Priority Issues

| ID  | Severity | Description |
|-----|----------|-------------|
| G11 | High     | Unsubscribe doesn't cancel DownloadManager downloads → orphan files |
| G12 | High     | Unsubscribed feed's currently-playing episode not stopped |
| Q5  | High     | `reorderItems` resets `currentItemIndex=0` → queue restarts from top after any drag |
| QE1 | High     | `PlaybackService.loadAndPlay` has no fallback to `localFilePathSnapshot` — deleted episode silently skipped, no snackbar |
| Q9  | High     | Retention cleanup can delete the currently-playing episode's file with no guard |
| Q11 | High     | `PlaybackService` never checks `downloadOnlyOnWifi` before streaming — WiFi-only constraint bypassed for queue playback |
| Q12 | High     | No network check on queue auto-advance — offline + stream-only items stall, don't skip |
| G8  | Medium   | FeedUpdateWorker runs cleanup even when refreshFeed fails |
| G5  | Medium   | Archived episodes have no UI treatment — look like normal unplayed episodes |
| G1  | Medium   | Double fetch on subscribe (subscribeToFeed + worker refresh) |
| G3  | Medium   | DB row bloat — old episode rows never pruned |
| G4  | Medium   | No URL validation before creating FeedEntity |
| G15 | Medium   | `runGlobalRetentionCleanup` runs on viewModelScope, should use IO dispatcher |
| Q10 | Medium   | Background refresh + retention cleanup can soft-delete queued episodes → silent switch from local file to stream |
| QE4 | **High** | Auto-advance is feed-based not queue-based — core architecture violation. `onPlaybackStateChanged` calls `getNextNewerEpisode(feedId)` instead of reading the active `QueueSnapshot`. Flagged by Gemini red team. |
| QE6 | Medium   | Empty queue after all items removed shows Active state with 0 items, not Empty state |
| G19 | Low-Med  | Per-feed keepCount reduction doesn't trigger immediate cleanup |
| G14 | Low      | FILTER_RULES playlists not cleaned up on feed delete |
| Q2  | Low      | Stream-only episodes added to queue bypass WiFi constraint entirely (no download, no check) |
| Q6  | Low      | `removeItemsFromActiveSnapshot` leaves gaps in position sequence — non-contiguous positions |
| Q7  | Low      | `removeItem` doesn't update `currentItemIndex` → cursor may skip an episode after removal |
| E2  | Low      | Title+Duration dedup is dead code — never called in upsert path |
| E11 | ~~Low~~  | ~~`FeedEntity.primaryCategoryId` not nulled when category deleted~~ **ALREADY FIXED** — `CategoryRepositoryImpl.deleteCategory` calls `nullifyPrimaryCategory` + `nullifySecondaryCategory`; `FeedListViewModel` also defends against stale IDs. Verified by Gemini red team. |
| Q3  | Low      | No maximum queue size enforced — 500-item snapshot possible |
| Q4  | Low      | `sourcePlaylistId` becomes dangling reference if playlist deleted after snapshot build |
| Q8  | Info     | Late-completing downloads are handled correctly via live `getEpisodeById` in PlaybackService, not via snapshot field |
| Q13 | Info     | Soft-deleted episode (row preserved, localFilePath=null) correctly streams; hard-delete resilience via snapshot is correct |
| G13 | Info     | No "keep downloads" option on unsubscribe |
| E10 | Info     | keepCount is soft limit when protected episodes exist — correct, just document it |
