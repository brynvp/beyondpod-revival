# BeyondPod Revival — Fix Backlog

> Sequenced from FEED_DOWNLOAD_AUDIT.md. Each group is a self-contained Claude Code
> session prompt. Groups are ordered so every fix has its dependencies satisfied first.
> Copy each prompt block and paste into the Claude Code CLI from H:\Git\Beyondpod.

## Status

| Group | Status | Items |
|-------|--------|-------|
| Group 1 — PlaybackService resilience | ✅ **DONE** (2026-05-04) | §1.3 ghost write, QE1, Q9, Q11, Q12 |
| Group 2 — Unsubscribe correctness | ✅ **DONE** (2026-05-05) | G12, G11 |
| Group 3 — Queue mutations | ✅ **DONE** (2026-05-05) | Q5, QE6, QE4, Q6, Q7 |
| Group 4 — Worker/background | ✅ **DONE** (2026-05-04) | G15, G8, G19 |
| Group 5 — Subscribe/data integrity | ✅ **DONE** (2026-05-05) | G4, G3, E2 (was already done) — G1 deferred |
| Group 6 — UI/polish | ✅ **DONE** (2026-05-05) | G5, G14 — E11 was already done |

**All backlog groups complete. G1 (subscribe double-fetch) deferred — requires UX refactor of AddFeedViewModel preview step.**

---

## 🔴 Gemini Red Team Findings (2026-05-04)

| # | Finding | Status |
|---|---------|--------|
| 1 | G11 — orphan files on unsubscribe | Already in Group 2 |
| 2 | G12 — ghost playback on unsubscribe | Already in Group 2 |
| 3 | Q5 — `currentItemIndex` points to wrong episode after reorder if playing item moved | **Fixed this session** — now resolves new position of playing `episodeId` in reordered list via `PlaybackStateHolder` |
| 4 | QE4 — auto-advance is feed-based not queue-based | **Elevated to High.** Already in Group 3 pending. Next session priority. |
| 5 | G1 — double fetch on subscribe | Already in Group 5 |
| 6 | Q12 — no skip-to-next on content errors | Noted. Skip logic deferred until QE4 is implemented (needs queue advance first) + Task #11 snackbar |
| 7 | Q9 ✅ — playing episode delete guard | Verified working |
| 8 | E11 ✅ — primaryCategoryId nulled on category delete | **Already implemented** — `CategoryRepositoryImpl` + `FeedListViewModel` both handle this. Removed from backlog. |
| 9 | QE6 ✅ — zero-item queue shows Empty state | Verified working |

---

## ⚡ Next Session Restart Prompt

Paste this into the Cowork chat at the start of the next session:

```
Continue BeyondPod Revival backlog work. Read all .md files in the Cowork folder
and review app/src/main/java/mobi/beyondpod/ structure before starting.

Context: We have a sequenced fix backlog in H:\Git\Beyondpod\FIX_BACKLOG.md.
Groups 1, 4, and partial Group 3 (Q5, QE6) are done.
Next up is Group 2 (unsubscribe correctness) then Group 3 remainder (Q6/Q7, QE4).

Group 2 prompt is in FIX_BACKLOG.md — implement it directly (write code to files,
don't just produce prompts). After Group 2, do Group 3 remainder.
Run ./gradlew assembleDebug after each group. End session with git push.
```

---

## Group 1 — PlaybackService Resilience + Network Awareness
**Why first:** All items live in or directly adjacent to `PlaybackService`. They share a common
theme: the service doesn't defend against file-gone, feed-deleted, or network-unavailable conditions.
Fix the foundation before anything else touches the service.

**⚠ BLOCKING CONSTRAINT:** Tasks #8 (skip silence + volume boost), #10 (playlist nav), and
#11 (notification foundation) **all modify `PlaybackService`** and must NOT be started until
this group is merged and building cleanly. Doing them before or in parallel will produce
conflicting edits to the same file.

**⚠ ABSORBS Task #9** ("Playback ghost write + mid-playback delete guard" from QA_REVIEW.md §1.3).
Task #9 covers the same ground as Q9 + QE1 here, plus adds the ghost-write position-save
protection (item 0 below). Mark Task #9 in-progress and close it when this group is done.

**Items covered:** §1.3 ghost write fix (new), QE1 (snapshot fallback chain), Q9 (protect playing
episode from cleanup), Q11 (WiFi-only check before streaming), Q12 (offline skip on auto-advance)

---

### Prompt — paste into Claude Code CLI

```
Fix five PlaybackService resilience gaps identified in FEED_DOWNLOAD_AUDIT.md and QA_REVIEW.md §1.3.
Read CLAUDE.md and app/src/main/java/mobi/beyondpod/revival/service/PlaybackService.kt first.

0. §1.3 GHOST WRITE — Protect the 5-second position-save loop from writing to a deleted episode.
   `PlaybackService` runs a coroutine every 5s calling `episodeRepository.savePlayPosition(currentEpisodeId, ...)`.
   If the episode was deleted mid-playback (by cleanup, unsubscribe, or manual delete), this
   write silently no-ops in the DAO (UPDATE on a non-existent row). The risk is not a crash but
   a ghost: the service continues playing, position never saves, and on restart the episode
   isn't in the DB so there's no resume position.
   Fix: in the position-save coroutine, after calling `savePlayPosition`, check the return value
   OR call `episodeRepository.getEpisodeById(currentEpisodeId)` once — if null, stop the
   position-save loop and clear `currentEpisodeId` to -1L. Do NOT stop playback — the audio
   can continue playing from the already-buffered data. Just stop the ghost writes.
   Alternative (simpler): wrap `savePlayPosition` in a try/catch and on any exception reset
   `currentEpisodeId = -1L` to prevent further writes.

1. QE1 — Wire the resilience fallback chain in `loadAndPlay()`.
   Currently: `episodeRepository.getEpisodeById(episodeId) ?: return@launch` — silently exits.
   Required: if the episode row is gone (feed was unsubscribed), fall back to the
   QueueSnapshotItemEntity fields for that episodeId:
     a. Query the active snapshot items: `queueSnapshotDao.getSnapshotItemsList(snapshotId)`
        and find the item with `item.episodeId == episodeId`.
     b. If `item.localFilePathSnapshot != null` AND `File(it).exists()`: play from that path.
     c. Else if `item.episodeUrlSnapshot.isNotBlank()`: stream from that URL.
     d. Else: show a snackbar "File not available", skip to next queue item.
   Inject `QueueSnapshotDao` into `PlaybackService` via `@Inject`.

2. Q9 — Prevent retention cleanup from deleting the currently-playing episode's file.
   The service already tracks `currentEpisodeId: Long`. The problem is that
   `DownloadRepositoryImpl.applyRetentionCleanup()` has no awareness of what's playing.
   Solution: add a singleton `PlaybackStateHolder` object (in the `service` package):
     ```kotlin
     object PlaybackStateHolder {
         @Volatile var currentlyPlayingEpisodeId: Long = -1L
     }
     ```
   `PlaybackService` sets `PlaybackStateHolder.currentlyPlayingEpisodeId = episodeId`
   when `loadAndPlay()` is called, and resets it to -1L in `onDestroy()`.
   In `DownloadRepositoryImpl.applyRetentionCleanup()`, skip any episode whose
   `id == PlaybackStateHolder.currentlyPlayingEpisodeId`.

3. Q11 — Enforce `downloadOnlyOnWifi` before ExoPlayer streams an episode.
   In `loadAndPlay()`, after resolving the URI:
   - If `uri` is a remote URL (starts with "http"), not a local file:
     - Read `feed.downloadOnlyOnWifi` (fall back to `AppSettings.DOWNLOAD_ON_WIFI_ONLY`)
     - If WiFi-only AND `!isOnWifi()`: emit a side-effect event that the UI can show
       a snackbar ("This feed is WiFi-only. Connect to WiFi to stream.") and return
       without starting playback.
   - Add `isOnWifi(): Boolean` to `PlaybackService` (same implementation as in
     `DownloadRepositoryImpl` — use `ConnectivityManager`).
   - Inject `FeedDao` (or `FeedRepository`) to read the feed's `downloadOnlyOnWifi` flag.
   - Expose a `StateFlow<String?>` on `PlaybackService` or `PlaybackViewModel` for the
     snackbar message so the UI can observe it.

4. Q12 — Handle offline + stream-only episodes on auto-advance without stalling.
   In the `Player.Listener.onPlaybackStateChanged` handler inside `PlaybackService`,
   when a `PlaybackException` is caught (error state, not STATE_ENDED):
   - Check if the error is a network/IO error (ExoPlayer `PlaybackException.errorCode`
     in the `ERROR_CODE_IO_*` range).
   - If yes AND the device has no network (`!isOnWifi()` and no active network):
     emit a "No network — playback paused" snackbar event and pause.
   - If yes AND device has network (server error, bad URL, etc.): skip to the next
     queue item (call the existing auto-advance logic) and show
     "Could not play [title] — skipping".
   Also apply this same network check when auto-advancing on STATE_ENDED: if the next
   queue item is stream-only (localFilePath == null) and device is offline, pause and
   emit "Next episode requires network connection".

Run ./gradlew assembleDebug at the end and confirm clean build.
```

---

## Group 2 — Unsubscribe Correctness
**Why second:** Builds on Group 1's `PlaybackStateHolder` (to know if a feed's episode is
currently playing). G12 (stop playback) must come before G11 (cancel downloads) — you need
to release the player reference before cancelling the underlying DownloadManager job.

**Items covered:** G12 (stop playing on unsubscribe), G11 (cancel DownloadManager downloads)

---

### Prompt — paste into Claude Code CLI

```
Fix two unsubscribe correctness gaps from FEED_DOWNLOAD_AUDIT.md.
Read CLAUDE.md first. Group 1 (PlaybackStateHolder) must already be applied.

1. G12 — Stop playback if the currently-playing episode belongs to the feed being deleted.
   In `DeleteFeedUseCase` (or `FeedRepositoryImpl.deleteFeed()`, wherever the delete is
   orchestrated), before deleting:
   - Check `PlaybackStateHolder.currentlyPlayingEpisodeId`.
   - Query the episode: if `episode.feedId == feedId`, send a stop-playback broadcast
     or start a `PlaybackService` stop intent:
     `context.startService(Intent(context, PlaybackService::class.java).apply {
         action = PlaybackService.ACTION_STOP_PLAYBACK
     })`
   - Add `ACTION_STOP_PLAYBACK` to `PlaybackService.onStartCommand` — calls
     `activePlayer.stop()`, resets `currentEpisodeId = -1L`,
     `PlaybackStateHolder.currentlyPlayingEpisodeId = -1L`.

2. G11 — Cancel in-flight DownloadManager downloads on unsubscribe.
   In `DeleteFeedUseCase` (or wherever episode deletion happens before the feed row is deleted):
   - Before deleting the feed, query all episodes for this feed that have
     `downloadState IN ('DOWNLOADING', 'QUEUED')` and a non-null `downloadId`.
   - Call `downloadManager.remove(*episodeIds.toLongArray())` for all matching downloadIds.
   - This must happen BEFORE `feedDao.deleteFeed(feed)` — the CASCADE delete will wipe
     the episode rows, losing the downloadId values.
   - Add `DownloadManager` injection to whichever class handles this.
   - Add a DAO query: `getDownloadingEpisodesForFeed(feedId: Long): List<EpisodeEntity>`
     — `WHERE feedId = :feedId AND downloadState IN ('DOWNLOADING', 'QUEUED')`

Run ./gradlew assembleDebug at the end and confirm clean build.
```

---

## Group 3 — Queue Mutation Correctness
**Why third:** Self-contained to `QueueViewModel`, `QueueSnapshotDao`, and `PlaybackService`.
Fix the ordering bugs (Q5, Q6+Q7) before tackling the advance-context issue (QE4), since
QE4 depends on the queue having correct position state after mutations.

**Items covered:** Q5 (reorder preserves index), Q6+Q7 (renumber + update index on remove),
QE4 (service advances correctly after new queue built), QE6 (zero-item active snapshot → Empty UI)

---

### Prompt — paste into Claude Code CLI

```
Fix four queue mutation correctness gaps from FEED_DOWNLOAD_AUDIT.md.
Read CLAUDE.md, QueueViewModel.kt, QueueSnapshotDao.kt, and PlaybackService.kt first.

1. Q5 — `reorderItems` must preserve `currentItemIndex` after drag.
   Currently: `snapshot.copy(id=0)` resets `currentItemIndex` to 0 (entity default).
   Fix: copy `currentItemIndex` and `currentItemPositionMs` from the existing snapshot:
     ```kotlin
     val snapshot = state.snapshot.copy(
         id = 0,
         currentItemIndex = state.snapshot.currentItemIndex,
         currentItemPositionMs = state.snapshot.currentItemPositionMs
     )
     ```
   This is a one-line fix in `QueueViewModel.reorderItems()`.

2. Q6 — Renumber positions after `removeItemsFromActiveSnapshot`.
   After calling `removeItemsFromActiveSnapshot(listOf(episodeId))`, the remaining items
   have sparse positions (e.g. [0,1,3,4] after removing position 2).
   Add a DAO method to compact positions:
     ```kotlin
     @Query("""
         UPDATE queue_snapshot_items
         SET position = (
             SELECT COUNT(*) FROM queue_snapshot_items i2
             WHERE i2.snapshotId = queue_snapshot_items.snapshotId
               AND i2.position < queue_snapshot_items.position
         )
         WHERE snapshotId = (SELECT id FROM queue_snapshots WHERE isActive = 1 LIMIT 1)
     """)
     suspend fun compactPositions()
     ```
   Call `compactPositions()` in `QueueSnapshotDao` after `removeItemsFromActiveSnapshot`.
   Wrap both in a `@Transaction` method `removeAndCompact(episodeIds: List<Long>)`.
   Update `QueueViewModel.removeItem` to call `removeAndCompact` instead.

3. Q7 — Update `currentItemIndex` after removing an item at or before the current position.
   In `QueueSnapshotDao.removeAndCompact()`, after compacting:
   - If the removed item's position was < `snapshot.currentItemIndex`:
     decrement `currentItemIndex` by the count of removed items that were before it.
   - If the removed item's position == `currentItemIndex`: the current episode was
     removed. Clamp `currentItemIndex` to `min(currentItemIndex, newItemCount - 1)`.
   Add a DAO query to update `currentItemIndex` in the active snapshot:
     ```kotlin
     @Query("UPDATE queue_snapshots SET currentItemIndex = :index WHERE isActive = 1")
     suspend fun updateCurrentIndex(index: Int)
     ```
   The `removeAndCompact` transaction should call this after the position compact.

4. QE4 — `PlaybackService` uses correct advance context after a new queue is built.
   Currently the service holds `currentEpisodeId` independently and never observes the
   active snapshot. When a new queue is built while playing, auto-advance still uses the
   old context.
   Fix: in `PlaybackService.onCreate()`, start a coroutine that observes
   `queueSnapshotDao.getActiveSnapshot()`. When a new snapshot emits (different `id`
   from the previously known snapshot id), update an internal `activeSnapshotId` field.
   The auto-advance logic (after STATE_ENDED) should look up the active snapshot by
   `activeSnapshotId`, find the next item after the current position, and play it.
   This replaces any hard-coded "get next from same feed" logic.
   Inject `QueueSnapshotDao` into `PlaybackService` (already needed for QE1 in Group 1).

5. QE6 — Active snapshot with 0 items shows `QueueUiState.Empty`, not `Active(emptyList)`.
   In `QueueViewModel.uiState` flow, change:
     ```kotlin
     QueueUiState.Active(snapshot, queueItems)
     ```
   to:
     ```kotlin
     if (queueItems.isEmpty()) QueueUiState.Empty
     else QueueUiState.Active(snapshot, queueItems)
     ```
   This is a one-line fix.

Run ./gradlew assembleDebug at the end and confirm clean build.
```

---

## Group 4 — Worker + Background Correctness
**Why fourth:** These are backend-only changes (ViewModel dispatcher, Worker logic). No UI
changes, no service changes. Clean, low-risk group. G15 (fix the dispatcher) before G8
(fix worker logic) because the dispatcher fix makes G8's retry behaviour more predictable.

**Items covered:** G15 (IO dispatcher for global cleanup), G8 (worker guards cleanup behind
refresh success), G19 (per-feed keepCount reduction triggers immediate cleanup)

---

### Prompt — paste into Claude Code CLI

```
Fix three background/worker correctness gaps from FEED_DOWNLOAD_AUDIT.md.
Read CLAUDE.md, SettingsViewModel.kt, FeedUpdateWorker.kt, DownloadRepositoryImpl.kt,
and FeedDetailViewModel.kt first.

1. G15 — Move `runGlobalRetentionCleanup()` off `viewModelScope` to `Dispatchers.IO`.
   In `SettingsViewModel`, the calls to `downloadRepository.runGlobalRetentionCleanup()`
   in `setGlobalMaxKeep()` and `setGlobalDeleteOlderThanDays()` currently run on the
   default coroutine dispatcher (UI-bound viewModelScope).
   Fix: wrap in `withContext(Dispatchers.IO)` inside the launch block:
     ```kotlin
     viewModelScope.launch {
         dataStore.edit { it[AppSettings.GLOBAL_MAX_KEEP] = value }
         withContext(Dispatchers.IO) {
             downloadRepository.runGlobalRetentionCleanup()
         }
     }
     ```
   Apply the same pattern to `cleanUpNow()`.
   Also add a `_cleanupRunning: MutableStateFlow<Boolean>` and set it true/false around
   the cleanup call so the UI can show a progress indicator. Wire a simple
   `if (cleanupRunning) CircularProgressIndicator()` next to the "Clean up now" PrefItem.

2. G8 — FeedUpdateWorker must not run cleanup when refreshFeed fails.
   Currently in `FeedUpdateWorker.processFeed()`:
     ```kotlin
     feedRepository.refreshFeed(feedId, markFailure = false)
         .onFailure { /* silent */ }
     // cleanup + download runs unconditionally after this
     downloadRepository.autoDownloadNewEpisodes(feedId, isManualRefresh = isManual)
     ```
   Fix: capture the result and only call `autoDownloadNewEpisodes` on success:
     ```kotlin
     val result = feedRepository.refreshFeed(feedId, markFailure = false)
     if (result.isSuccess) {
         downloadRepository.autoDownloadNewEpisodes(feedId, isManualRefresh = isManual)
     }
     ```
   Consistent with the fix already applied in `FeedDetailViewModel.refresh()`.

3. G19 — Reducing per-feed `maxEpisodesToKeep` triggers immediate cleanup for that feed.
   In `FeedDetailViewModel.updateFeedProperties(feed: FeedEntity)`:
   After `feedRepository.updateFeedProperties(feed)`, check if `maxEpisodesToKeep` was
   reduced vs the previous value. If reduced, call:
     ```kotlin
     withContext(Dispatchers.IO) {
         downloadRepository.autoDownloadNewEpisodes(feed.id, isManualRefresh = false)
     }
     ```
   This runs the cleanup steps (A+B) and respects the new keepCount without downloading
   anything new (MANUAL strategy skips download; other strategies with no new episodes
   also skip download — Step C is a no-op when `getNotDownloadedNewest` returns empty).
   To compare old vs new, load the current feed entity before saving:
     ```kotlin
     val existing = feedRepository.getFeedById(feedId)
     feedRepository.updateFeedProperties(feed)
     val oldKeep = existing?.maxEpisodesToKeep
     val newKeep = feed.maxEpisodesToKeep
     if (newKeep != null && (oldKeep == null || newKeep < oldKeep)) {
         withContext(Dispatchers.IO) {
             downloadRepository.autoDownloadNewEpisodes(feed.id, isManualRefresh = false)
         }
     }
     ```

Run ./gradlew assembleDebug at the end and confirm clean build.
```

---

## Group 5 — Subscribe Flow & Data Integrity
**Why fifth:** These are improvements to the subscribe path and DB hygiene. Lower risk than
Groups 1–4 since they improve new subscribes, not in-flight state. G4 (validate URL) first,
then G1 (fix double fetch) — the validation guard enables the single-fetch refactor.

**Items covered:** G4 (URL validation), G1 (single fetch on subscribe), G3 (episode row pruning),
E2 (wire title+duration dedup)

---

### Prompt — paste into Claude Code CLI

```
Fix subscribe flow and data integrity gaps from FEED_DOWNLOAD_AUDIT.md.
Read CLAUDE.md, FeedRepositoryImpl.kt, FeedParser.kt, and EpisodeDao.kt first.

1. G4 — Validate that the URL is RSS/Atom before creating a FeedEntity.
   In `FeedRepositoryImpl.subscribeToFeed(url)`, before creating anything:
   - Fetch the URL (HEAD or GET first 512 bytes).
   - Check `Content-Type` header: accept `application/rss+xml`, `application/atom+xml`,
     `text/xml`, `application/xml`. Reject `text/html` with a `Result.failure` and
     message "URL does not appear to be a podcast feed".
   - If Content-Type is ambiguous (some servers return `text/plain`), peek at the body
     for an `<rss` or `<feed` root element in the first 512 bytes.
   - Return `Result.failure(IllegalArgumentException("Not a valid RSS/Atom feed"))` so
     the UI can surface a specific error message.

2. G1 — Eliminate the double fetch on subscribe.
   Currently `subscribeToFeed` does its own RSS fetch to get title/episodes, then the
   caller fires `FeedUpdateWorker` which does a second full fetch via `refreshFeed`.
   Fix: make `subscribeToFeed` a thin operation — only validate the URL and create a
   minimal `FeedEntity` stub (title = URL hostname as placeholder, no episodes yet).
   Let `FeedUpdateWorker(isManual=true)` do the real first fetch. This means:
   - `subscribeToFeed` → validate URL → insert minimal FeedEntity → return feedId
   - Caller fires FeedUpdateWorker with KEY_IS_MANUAL=true as before
   - Worker's `refreshFeed` populates episodes, final URL, title, artwork
   The loading state in `AddFeedViewModel` should show "Subscribing..." until the
   worker finishes (observe feed's `lastUpdated` field changing from 0).

3. G3 — Automatically prune old NOT_DOWNLOADED/DELETED/PLAYED episode rows.
   DB rows for old episodes accumulate indefinitely — only files are cleaned up, not rows.
   Add to `EpisodeDao`:
     ```kotlin
     @Query("""
         DELETE FROM episodes
         WHERE feedId = :feedId
           AND downloadState IN ('NOT_DOWNLOADED', 'DELETED')
           AND playState = 'PLAYED'
           AND isProtected = 0
           AND isStarred = 0
           AND pubDate < :cutoffMs
     """)
     suspend fun pruneOldEpisodeRows(feedId: Long, cutoffMs: Long): Int
     ```
   Call this at the end of `FeedRepositoryImpl.refreshFeed()` with a cutoff of
   `System.currentTimeMillis() - (180 * 86_400_000L)` (180 days). This preserves:
   - Any episode the user starred
   - Protected episodes
   - Episodes downloaded (state = DOWNLOADED or QUEUED)
   - Episodes not yet played
   - Recent episodes (within 180 days) for feed display continuity

4. E2 — Wire Title+Duration dedup in the episode upsert path.
   `findPotentialDuplicates()` exists but is never called. In `EpisodeRepository.upsertEpisode()`
   (or `FeedRepositoryImpl.upsertEpisodes()`), after the GUID and URL checks fail to find
   an existing row, call `findPotentialDuplicates(feedId, title, duration, tolerance=5)`
   before inserting a new row. If a match is found, treat it as the same episode and
   update rather than insert.

Run ./gradlew assembleDebug at the end and confirm clean build.
```

---

## Group 6 — UI & Data Consistency (Polish)
**Why last:** Lowest risk, lowest urgency. No architecture changes. Safe to do in any order
after Groups 1–5.

**Items covered:** G5 (archived episode UI), E11 (null primaryCategoryId on category delete),
G14 (FILTER_RULES cleanup on feed delete)

---

### Prompt — paste into Claude Code CLI

```
Fix three UI and data consistency gaps from FEED_DOWNLOAD_AUDIT.md.
Read CLAUDE.md, EpisodeListItem.kt, FeedRepositoryImpl.kt or CategoryDao, and
SmartPlaylistRepositoryImpl.kt (or wherever FILTER_RULES playlist rules are stored) first.

1. G5 — Archived episodes need visual differentiation in the feed episode list.
   `isArchived = true` episodes currently look identical to normal NOT_DOWNLOADED episodes.
   In `EpisodeListItem.kt` (or wherever episodes are rendered in the feed detail screen):
   - If `episode.isArchived`, show a small "Archived" label in a muted/secondary colour,
     or an archive icon next to the episode title.
   - Archived episodes should appear at the bottom of the feed list (after non-archived).
     Add `isArchived ASC, pubDate DESC` to the `getEpisodesForFeed` DAO query ordering.
   - Optionally: add a "Show archived" toggle in the feed detail screen header to
     filter them out of the default view.

2. E11 — Null `FeedEntity.primaryCategoryId` when its category is deleted.
   Currently `FeedEntity.primaryCategoryId` holds a stale ID after the category is
   CASCADE-deleted (only the cross-ref row is removed, not the denormalised column).
   In `CategoryRepositoryImpl.deleteCategory(categoryId)` (or the use case that deletes
   categories), after the delete, run:
     ```kotlin
     feedDao.clearPrimaryCategoryForDeletedCategory(categoryId)
     ```
   Add to `FeedDao`:
     ```kotlin
     @Query("UPDATE feeds SET primaryCategoryId = NULL WHERE primaryCategoryId = :categoryId")
     suspend fun clearPrimaryCategoryForDeletedCategory(categoryId: Long)
     ```

3. G14 — FILTER_RULES playlists retain stale feed references after feed deletion.
   `prunePlaylistBlocksForFeed` handles SEQUENTIAL_BLOCKS but FILTER_RULES playlists
   store feed references as rule conditions (serialised in a JSON/string column).
   In `DeleteFeedUseCase` (or wherever feed deletion is orchestrated), after removing
   SEQUENTIAL_BLOCKS references, also scan FILTER_RULES playlists:
   - Query all SmartPlaylists with `mode = FILTER_RULES`.
   - For each, parse the rule conditions and remove any condition that references
     `feedId` (e.g., a "from feed X" filter rule).
   - If removing the condition leaves a playlist with zero rules, mark it as "no filter"
     or delete the empty playlist (your call on UX).
   The exact implementation depends on how FILTER_RULES conditions are serialised —
   read `SmartPlaylistEntity` and the rules storage before implementing.

Run ./gradlew assembleDebug at the end and confirm clean build.
```

---

## Quick Reference: Sequence & Dependencies

```
Group 1 (PlaybackService)
  → introduces PlaybackStateHolder
  → introduces QueueSnapshotDao injection in service
  → introduces isOnWifi() in service

Group 2 (Unsubscribe) depends on Group 1
  → uses PlaybackStateHolder to detect if playing
  → adds ACTION_STOP_PLAYBACK

Group 3 (Queue mutations) depends on Group 1
  → uses QueueSnapshotDao already injected in Group 1
  → fixes reorder, remove, advance context

Group 4 (Worker/background) — independent
  → can run in parallel with Groups 2-3 if desired

Group 5 (Subscribe/data) — independent
  → no dependencies on Groups 1-4

Group 6 (UI/polish) — independent
  → no dependencies on any other group
```

---

---

## Phase Task Integration (Tasks #8, #10, #11 from QA task list)

These are the "next feature phase" tasks that were already planned before this backlog was written.
They are NOT included in Groups 1–6 above — they are new feature work, not bug fixes.
**They have a hard ordering dependency on Group 1.**

| Task | Description | Can start after |
|------|-------------|-----------------|
| #8   | Skip silence + volume boost per-feed | Group 1 merged |
| #10  | Playlist nav + UI polish pass | Group 1 + Group 3 merged |
| #11  | Notification system foundation | Group 1 merged |
| #12  | ~~Fix download cap~~ | **DONE** — fixed this session (isManualRefresh) |
| #13  | WiFi warning end-to-end verify | Group 1 merged (Q11, Q12 complete the fix) |

**Why #8 needs Group 1 first:** Skip silence adds `SilenceSkippingAudioProcessor` to the ExoPlayer
builder; volume boost modifies `LoudnessEnhancer` setup. Both are in `PlaybackService.onCreate()` and
`loadAndPlay()`. Group 1 also modifies those same blocks — doing both simultaneously creates merge conflicts.

**Why #10 needs Groups 1+3 first:** Playlist nav touches queue generation (builds snapshots) — the
same code Group 3 modifies. UI polish pass may also touch `QueueScreen` which Group 3 changes.

**Why #11 needs Group 1 first:** Notification system adds notification channel registration and
`setForegroundServiceNotification()` calls to `PlaybackService`. Group 1 injects new dependencies
(`QueueSnapshotDao`) and adds new service actions (`ACTION_STOP_PLAYBACK`). Conflicts guaranteed if done simultaneously.

---

## Items Not in Any Group (Accepted / Won't Fix for Now)

| ID  | Reason |
|-----|--------|
| Q2  | Stream-only episodes in WiFi-only queue: UX policy unclear. Defer until streaming policy is defined. |
| Q3  | Queue size cap: no evidence of real-world issue yet. Add if/when performance problems emerge. |
| Q4  | Dangling `sourcePlaylistId`: cosmetic (empty label). Low priority. |
| Q8  | Info only — late-completing downloads work correctly via live `getEpisodeById`. |
| Q13 | Info only — soft-delete resilience is correct. |
| G13 | "Keep downloads on unsubscribe" — backlog feature, not a bug. |
| E10 | keepCount soft limit with protected episodes — correct behaviour, document in spec. |
| G16 | Snackbar after global cleanup — nice-to-have; partially addressed by G15 progress indicator. |
| G17 | In-flight downloads not paused when global WiFi-only toggled — DownloadManager limitation. |
| E4  | Stale-DOWNLOADING recovery on reboot — low priority, uncommon scenario. |
