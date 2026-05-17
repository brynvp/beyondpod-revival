# BeyondPod Revival — Claude Code Context

> Read this file fully before writing any code. It is short by design — do not skip it.

## What This Project Is

Free, open-source, MIT-licensed Android podcast manager. Faithful recreation of BeyondPod 4.x,
the most capable Android podcast app before it was abandoned. The full specification is in
`BEYONDPOD_REVIVAL_SPEC.md` — read only the sections relevant to your current phase (listed below).

**App ID**: `mobi.beyondpod.revival` | **Min SDK**: 26 | **Target SDK**: 34
**Language**: Kotlin | **UI**: Jetpack Compose | **Architecture**: Clean Architecture + MVVM

---

## Current Build Phase

**PHASE: Early Beta — core loop working end-to-end on real device**

All 8 build phases complete. Core functionality confirmed working:
subscribe → refresh → download → play → mini-player → back → full player.

Update this line at the start of each session to reflect where you are:
- Phase 0: Scaffold only (project exists, no feature code)
- Phase 1: Data layer complete
- Phase 2: Repositories + Use Cases complete
- Phase 3: Core Services complete (PlaybackService, DownloadWorker, FeedUpdateWorker)
- Phase 4: Navigation shell + My Episodes UI
- Phase 5: Feed management UI
- Phase 6: SmartPlay + Queue UI
- Phase 7: Import/Export + Settings
- Phase 8: Widgets + Polish ✓ COMPLETE
- Post-build: Real-device QA fixes ✓ COMPLETE
- **Early Beta (v1.0.3-beta): Retention cleanup over-deletion bug fixed (2026-05-10). Root cause: effectiveKeep = keepCount - inFlight - trulyNew went negative when inFlight > keepCount, deleting ALL downloaded files on every refresh. Guard added: when inFlight ≥ keepCount, effectiveKeep = keepCount (no deletion). Applies to GLOBAL + DOWNLOAD_NEWEST strategies. Diagnostic DIAG logging added to both branches.**
- **Early Beta (v1.0.4-beta): Auto-download never-starts bug fixed (2026-05-11). Root cause: mergeWithExisting preserved isArchived = existing.isArchived. Backup-restored episodes had isArchived=1 (fell off original phone's RSS window). Every refresh merged and kept that flag → all episodes remained archived → getNotDownloadedNewest (isArchived=0 filter) found nothing → toDownload=0 always. Fix: changed to isArchived = incoming.isArchived (always false for RSS-sourced episodes).**
- **Early Beta (v1.0.5-beta): Stale DOWNLOADING ghost accumulation fixed (2026-05-11). Root cause: reconcileStalledDownloads only cleared episodes missing from DownloadManager entirely. Downloads that were PENDING (never started, 0 bytes, >15 min old), PAUSED_WAITING_FOR_NETWORK while on WiFi, or PAUSED_WAITING_TO_RETRY were kept as in-flight, causing inFlight to accumulate to 24+ across sessions and permanently block auto-download (slots=0 forever). Fix: reconcile now checks COLUMN_BYTES_DOWNLOADED_SO_FAR, COLUMN_LAST_MODIFIED_TIMESTAMP, and COLUMN_REASON; stale entries are cancelled in DownloadManager and reset to NOT_DOWNLOADED.**
- **Early Beta (v1.0.6-beta): Null-downloadId ghost accumulation fixed (2026-05-11). Root cause: countInFlightDownloads counted ALL DOWNLOADING/QUEUED episodes (including null-downloadId ghosts), but getDownloadingEpisodesForFeed filtered downloadId IS NOT NULL so reconcile never saw them. Ghosts accumulated to 1869 (Documentary), 1651 (NPR Politics), 417 (Real Story) episodes, permanently blocking auto-download. Fix: new resetNullDownloadIdGhosts DAO query bulk-resets all null-downloadId DOWNLOADING/QUEUED rows at start of reconcile.**
- **Early Beta (v1.0.7-beta): Downloads stop when screen locks fixed (2026-05-11). Root cause: Doze mode defers WorkManager after ~2 min screen-off. DownloadManager (system process) finishes active downloads but nothing queues the next batch — chain breaks. Fix: DownloadCompleteReceiver now calls autoDownloadNewEpisodes() immediately after marking episode DOWNLOADED. ACTION_DOWNLOAD_COMPLETE is a system broadcast exempt from Doze, so the chain self-sustains while locked.**
- **Early Beta (v1.0.8-beta): Stale-reconcile + null-ghost fixes committed (2026-05-14). These were written to disk before v1.0.7 but never committed. v1.0.5: reconcileStalledDownloads now detects PENDING (0 bytes >15min), PAUSED_WAITING_FOR_NETWORK on WiFi, PAUSED_WAITING_TO_RETRY, and terminal STATUS_SUCCESSFUL/FAILED (receiver miss); stalled entries are cancelled in DownloadManager and reset to NOT_DOWNLOADED. v1.0.6: new resetNullDownloadIdGhosts DAO query bulk-resets all null-downloadId DOWNLOADING/QUEUED rows at start of reconcile, fixing permanent slot=0 blockage from ghost accumulation.**
- **Early Beta (v1.0.9-beta): Backlog suppression guard + Gemini red-team fixes (2026-05-14). (1) Backlog suppression: autoDownloadNewEpisodes (DOWNLOAD_NEWEST + GLOBAL) returns early when window is full and all candidates are older than window, preventing endless download-then-delete loop. (2) Ghost-reset scope narrowed: resetNullDownloadIdGhosts now only resets DOWNLOADING (not QUEUED) null-downloadId rows — QUEUED+null is intentional for STREAM_NEWEST. (3) Mobile chain fix: DownloadCompleteReceiver now passes mobileAllowed = !checkMobileDownloadBlocked() instead of hardcoded false — mobile-approved downloads now chain correctly. (4) Filename uniqueness: episode ID suffix added to prevent same-title collision. (5) FeedUpdateWorker per-feed isolation: processFeed wrapped in runCatching so one bad feed cannot cancel the whole refresh batch. Next: cancel UI fix, Tasks #8 (skip silence + volume boost), #10 (playlist nav), #11 (notification foundation).**
- **Early Beta (v1.0.10-beta): Backlog suppression applied to manual refresh + cleanup deadlock + trulyNew threshold fixed (2026-05-15). (1) Suppression fix: removed !isManualRefresh — manual refresh now also suppresses when window full. (2) Cleanup deadlock fix: applyRetentionCleanup moved to run BEFORE the suppression return so over-full windows always get trimmed. (3) trulyNew threshold fix (root cause of window locking): changed from firstOrNull() (newest) to lastOrNull() (oldest) when computing oldestInWindow. Using newestDate meant trulyNew was always 0 after the first download because nothing can ever be newer than the newest — permanently locking the window. Using oldestInWindow means any candidate fresher than the oldest downloaded episode counts as trulyNew and correctly triggers a swap. Applies to both DOWNLOAD_NEWEST and GLOBAL strategy blocks.**
- **Early Beta (v1.0.11-beta): Target-window download model (2026-05-15). Replaced the trulyNew/effectiveKeep/suppression logic in GLOBAL and DOWNLOAD_NEWEST with a direct target-window approach. Root cause of all window-pollution bugs: the window was defined by "what happened to be downloaded" rather than "the Y newest episodes by pubDate". Fix: new DAO query getNewestEpisodesForWindow(feedId, keepCount) returns top keepCount episodes by pubDate (any state, not archived). Strategy blocks now: (1) compute target window, (2) delete any DOWNLOADED episode not in window, (3) enqueue any NOT_DOWNLOADED or DELETED episode in window up to slots. DELETED episodes in the window are re-fetched — earlier buggy runs had downloaded-then-deleted episodes still holding the top pubDate slots, causing toDownload=0 even with 150 NOT_DOWNLOADED available (DELETED gap). Old backlog episodes that snuck into the window are automatically evicted. No suppression logic needed. New DAO query added to EpisodeDao.**
- **Early Beta (v1.0.12→v1.0.13-beta): Download ping-pong fix (2026-05-16). Root cause: user-deleted episode (DELETED state) still occupied a slot in getNewestEpisodesForWindow, blocking the next newer episode from entering the window. toDownload=0 with 150 NOT_DOWNLOADED available. Fix: getNewestEpisodesForWindow SQL now excludes downloadState = 'DELETED'. Deleted episodes' slots immediately fill with the next newer episode. Step C filter simplified to NOT_DOWNLOADED only (DELETED can no longer appear in the window). v1.0.12 committed same session with related cleanup.**
- **Early Beta (v1.0.14-beta): Per-feed mutex + MANUAL strategy cancels in-flight (2026-05-16). (1) #19 concurrent race: ConcurrentHashMap<Long, Mutex> (feedDownloadLocks) prevents two concurrent autoDownloadNewEpisodes callers (DownloadCompleteReceiver + FeedUpdateWorker) for the same feed from double-enqueuing. (2) #2.2 MANUAL strategy change: new cancelFeedDownloads(feedId) cancels all in-flight DownloadManager entries and resets to NOT_DOWNLOADED. FeedDetailViewModel.updateFeedProperties detects strategy → MANUAL transition and calls it before persisting.**
- **Early Beta (v1.0.15-beta): Queue cursor fixes (2026-05-16). (1) #5.9: onPlaybackStateChanged and onPlayerError replaced indexOfFirst { episodeId } with snapshot.currentItemIndex as primary cursor — handles duplicate episodes in queue correctly. Falls back to indexOfFirst only on cursor mismatch (logs warning). (2) #6.2: QueueViewModel.reorderItems when service dead (playingId=-1L) now resolves episode identity from the old list at snapshot.currentItemIndex, then finds it in the new order — avoids pointing cursor at wrong episode after drag-to-reorder.**
- **Early Beta (v1.0.16-beta): downloadCount/keepCount semantic fix + label clarity (2026-05-16). Root cause: downloadLimit = keepCount on isManualRefresh=true — with downloadCount=1, keepCount=5, any manual refresh or new subscribe downloaded 5 episodes instead of 1. Fix: downloadLimit = downloadCount always (isManualRefresh no longer affects the cap). keepCount is the storage ceiling enforced only by Step B cleanup. Label updates: "Auto-download count" → "Download latest", "Keep downloaded episodes" → "Keep at most", subtitles clarified on SettingsScreen and FeedDetailScreen.**
- **Early Beta (v1.0.17-beta): Split keepWindow/autoWindow (2026-05-16). Root cause: both DOWNLOAD_NEWEST and GLOBAL built a single targetWindow sized by keepCount and used it for Step C downloads. DownloadCompleteReceiver chaining filled all keepCount slots ignoring downloadCount. Fix: keepWindow = getNewestEpisodesForWindow(feedId, keepCount) for Step B (deletion protection); autoWindow = getNewestEpisodesForWindow(feedId, downloadCount) for Step C (auto-download). Chaining now stops once the top downloadCount episodes are all DOWNLOADED — autoWindow has no more NOT_DOWNLOADED entries.**
- **Early Beta (v1.0.18-beta): STREAM_NEWEST slot starvation fix (2026-05-16). Root cause: QUEUED streaming placeholders (QUEUED + null downloadId) accumulated across refreshes and were never cleared. countInFlightDownloads included them, so inFlight grew to downloadCount → slots=0 → nothing ever queued. Fix: window-based approach identical to DOWNLOAD_NEWEST/GLOBAL. New getStreamQueuedForFeed DAO query (QUEUED + downloadId IS NULL). Step B clears stale placeholders outside autoWindow; Step C queues NOT_DOWNLOADED in autoWindow. No slot arithmetic.**
- **Early Beta (v1.0.19-beta): Storage quota pre-flight + missing file reconciliation (2026-05-16). (1) StatFs check in enqueueDownload before DownloadManager.enqueue() — requires 50MB free, sets FAILED immediately on insufficient space (shows retry button vs. silent stuck-DOWNLOADING). (2) reconcileStalledDownloads now checks all DOWNLOADED episodes' localFilePath exists on disk — files deleted externally (user cleared app storage) are reset to NOT_DOWNLOADED and auto-re-downloaded on next refresh.**
- **Early Beta (v1.0.20-beta): Orphaned podcast folder scavenger (2026-05-16). When a feed is deleted and re-added it gets a new feedId and a new podcasts/{newFeedId}/ directory. Old folder never cleaned up. Fix: scrubOrphanedPodcastFolders() runs at end of runGlobalRetentionCleanup(). Lists all dirs under podcasts/, compares names against feedDao.getAllFeedsList() IDs, deletes any dir whose name is not a known feedId. 24-hour safety buffer on lastModified() prevents racing with just-subscribed feeds.**
- **Early Beta (v1.0.21-beta): Task #8 — skip silence + volume boost wired end-to-end (2026-05-16). (1) skipSilenceEnabled set on ExoPlayer at build time from AppSettings.SKIP_SILENCE — uses ExoPlayer's built-in detector, no custom AudioProcessor. (2) New resolveVolumeBoost() suspend fn: per-feed FeedEntity.playbackVolumeBoost (non-zero) → global VOLUME_BOOST_GLOBAL → 1 (no boost). Called from loadAndPlay() after prepare() and from onIsPlayingChanged via serviceScope. applyVolumeBoost() made private. (3) SettingsScreen: StepperPref for global volume boost added. (4) FeedDetailScreen: volume boost row changed from read-only to ClickableSettingsRow cycling 0–10.**
- **Early Beta (v1.0.24-beta): Feed-aware prev/next navigation (2026-05-17). Root cause: ACTION_PLAY_EPISODE called loadAndPlay directly without replacing the active queue snapshot — stale My Episodes snapshot drove prev/next, jumping to unrelated feeds. Fix: ACTION_PLAY_EPISODE now calls buildFeedQueueSnapshot(feedId, startEpisodeId) before loadAndPlay. Snapshot built pubDate ASC (oldest=index 0, newest=index N) so next=index+1=newer, prev=index-1=older. At newest: next→nothing. Auto-advance on finish→next newer. New EpisodeDao.getEpisodesForFeedListAsc query added.**
- **Early Beta (v1.0.23-beta): MiniPlayer swipe-to-dismiss + Smart Playlists in drawer (2026-05-17). SwipeToDismissBox wraps MiniPlayer Row — swipe left or right fires stopPlayback() → ACTION_STOP_PLAYBACK → service stops → hasActiveEpisode false → bar slides out. LaunchedEffect resets dismiss state on next episode load. PlaybackViewModel.stopPlayback() added. Smart Playlists NavigationDrawerItem added to drawer between Queue and Settings.**
- **Early Beta (v1.0.22-beta): Task #10 — playlist nav + player UI polish (2026-05-16). (1) PlaybackService: ACTION_PREV_EPISODE (double-tap: >5s restart, else prev slot) + ACTION_NEXT_EPISODE (next slot, no-op at end). (2) PlaybackViewModel: injects QueueSnapshotDao, observes snapshot flatMapped to items → hasPrev/hasNext/queueIndex/queueSize StateFlows. skipToPrev/skipToNext/cyclePlaybackSpeed added. (3) PlayerScreen: 3-button controls expanded to 5 (⏮ rw ▶ ff ⏭), prev/next dimmed at boundaries; "X of Y" queue indicator; speed TextButton cycling 0.75×–2.0×. (4) MiniPlayer: Forward30 skip button added after play/pause.**
- **Early Beta (v1.0.27-beta): Category dialog — inline creation + predefined suggestions in FeedDetailScreen (2026-05-17). Rewrote Assign Category dialog with: LazyColumn (heightIn max=200dp) for existing categories, HorizontalDivider, 19 predefined suggestion chips (SuggestionChip in LazyRow), OutlinedTextField + Add button for custom name. CreateCategoryUseCase injected into FeedDetailViewModel; createAndAssignCategory(name) added. Manual path (overflow → Assign Category) confirmed working end-to-end.**
- **Early Beta (v1.0.28→v1.0.29-beta): Failed nav-arg approach for auto-open category picker (2026-05-17). Attempted passing showCategoryPicker=true as optional Boolean query param on FeedEpisodes route. Navigation 2.9.0 string-route optional Bool query params do not reliably populate SavedStateHandle or BackStackEntry arguments. Two separate implementation attempts both failed. Approach abandoned entirely in v1.0.30.**
- **Early Beta (v1.0.30-beta): AddFeedScreen category dialog via SubscribedPickCategory state (2026-05-17). AddFeedViewModel: new sealed state SubscribedPickCategory(feedId); confirmSubscribe() emits it before navigating; CategoryRepository/CreateCategoryUseCase/MoveFeedToCategoryUseCase injected; skipCategory(), assignCategoryAndProceed(), createCategoryAndProceed() added. AddFeedScreen: category AlertDialog shown when uiState is SubscribedPickCategory. All nav-arg dead code removed from Screen.kt and NavGraph.**
- **Early Beta (v1.0.31-beta): Local state pattern for dialog — StateFlow batching fix (2026-05-17). Root cause: dialog condition `if (uiState is SubscribedPickCategory)` driven by StateFlow via collectAsState(). When confirmSubscribe() emits SubscribedPickCategory and enqueueImmediateRefresh fires Room updates simultaneously, Compose batches recompositions and can skip the intermediate state. Fix: local `var pendingCategoryFeedId by remember { mutableStateOf<Long?>(null) }` set inside LaunchedEffect — local remember state changes always trigger isolated, guaranteed recompositions. Mirrors the working FeedDetailScreen showCategoryDialog pattern.**
- **Early Beta (v1.0.32-beta): BP.Subscribe diagnostics logging (2026-05-17). Added Log.d("BP.Subscribe") at every step of the subscribe→category dialog flow: confirmSubscribe(), skipCategory(), assignCategoryAndProceed(), createCategoryAndProceed() in ViewModel; LaunchedEffect body and dialog condition in Screen. Diagnostic commit only — no behaviour change. Added to identify the true failure point.**
- **Early Beta (v1.0.33-beta): PodcastSearchScreen crash on duplicate feed URLs (2026-05-17). Root cause: iTunes Search API returns duplicate entries for the same feedUrl. LazyColumn key = { it.feedUrl } threw IllegalArgumentException when two results shared a key, crashing the app before Subscribe could ever be tapped. Fix: state.items.distinctBy { it.feedUrl } wrapped in remember(state.items) before passing to LazyColumn.**
- **Early Beta (v1.0.34-beta): Root fix — category dialog missing from podcast search subscribe path (2026-05-17). Root cause: two completely separate subscribe paths exist. AddFeedScreen (URL entry) correctly showed category dialog via AddFeedViewModel. PodcastSearchScreen (iTunes search results) had its own PodcastSearchViewModel.subscribe() that called subscribeToFeedUseCase, added to subscribedUrls, and stopped — no SubscribedPickCategory state, no dialog, no navigation. All v1.0.29–v1.0.33 work targeted AddFeedViewModel/Screen which was correct but unreachable from the search path. Fix: CategoryRepository, CreateCategoryUseCase, MoveFeedToCategoryUseCase injected into PodcastSearchViewModel; pendingCategoryFeedId StateFlow added; subscribe() now emits feedId after success; skipCategory/assignCategoryAndProceed/createCategoryAndProceed added. PodcastSearchScreen mirrors the AddFeedScreen local-state LaunchedEffect + AlertDialog pattern; navigates to FeedEpisodes and pops search off back stack after selection.**

**Room DB version**: 5
- Migration 1→2: compound indices (feedId+pubDate, feedId+playState, feedId+downloadState, queue snapshot position)
- Migration 2→3: standalone indices (downloadState, downloadedAt, isStarred, lastPlayed)
- Migration 3→4: feed_category_cross_ref recreated with categoryId FK + ON DELETE CASCADE
- Migration 4→5: virtual folder feed columns (isVirtualFeed, virtualFeedFolderPath) — PRAGMA-guarded ALTER TABLE

**Spec sections for current phase**: See `FEED_DOWNLOAD_AUDIT.md` for the full gap analysis and `FIX_BACKLOG.md` for resolution status. `red_team_qa.md` tracks Gemini red team findings.

---

## Non-Negotiable Architecture Rules

These are design decisions already made. Do not change them without strong reason.

1. **Queue is a frozen snapshot** — `QueueSnapshotEntity` + `QueueSnapshotItemEntity`. Never
   add `isInQueue` or `queuePosition` to `EpisodeEntity`. All queue mutations go through
   `QueueSnapshotDao`. See §5.1 QueueSnapshotEntity.

2. **My Episodes is not just a SmartPlaylist** — `SmartPlaylistEntity(isDefault=true)` is the
   implementation, but it has 5 distinct behavioural rules. See §7.5 before touching it.

3. **SmartPlay has two modes** — `SEQUENTIAL_BLOCKS` (Standard, default, legacy-compatible)
   and `FILTER_RULES` (Advanced). New playlists default to SEQUENTIAL_BLOCKS. See §5.1.

4. **isProtected is an absolute veto on auto-deletion** — `EpisodeEntity.isProtected = true`
   means never auto-delete (retention cleanup, age cleanup, global cleanup). Enforced at all
   cleanup paths and download deletion. Exception: manual unsubscribe with "delete downloads"
   is explicit user intent — protected files are deleted alongside others (the DB row is gone
   via CASCADE anyway; preserving the file would create an unmanaged orphan).

5. **Episode identity is multi-key** — dedup priority: GUID → URL → Title+Duration heuristic
   → file hash. Never assume GUID alone is reliable. See §5.1 Episode Identity Strategy.

6. **Volume boost via LoudnessEnhancer only** — never set `player.volume > 1.0f`. Use
   `android.media.audiofx.LoudnessEnhancer` attached to `exoPlayer.audioSessionId`. See §7.6.

7. **Category deletion never cascades to feeds** — feeds move to Uncategorized. `FeedCategoryCrossRef`
   has no FK on categoryId deliberately. See §7.3 and FeedCategoryCrossRef KDoc.

8. **FeedUpdateWorker cleanup order is mandatory** — cleanup (steps 5-6) BEFORE download (step 7).
   See §9 FeedUpdateWorker.

---

## Build & Run Commands

```bash
./gradlew assembleDebug          # build check — run after every session
./gradlew test                   # unit tests
./gradlew connectedAndroidTest   # instrumented tests (needs device/emulator)
./gradlew lint                   # lint check
```

**End every session by running `./gradlew assembleDebug` and confirming it passes.**
If it doesn't compile, fix it before stopping — a broken build is a bad handoff.

---

## Key File Locations

```
BEYONDPOD_REVIVAL_SPEC.md          ← Full specification. Read §-numbered sections as needed.
CLAUDE.md                          ← This file. Update "Current Build Phase" each session.
app/src/main/java/mobi/beyondpod/ ← All Kotlin source
app/src/main/res/                  ← Resources (XML widgets, values)
app/build.gradle.kts               ← App-level build config
build.gradle.kts                   ← Root build config
gradle/libs.versions.toml          ← Version catalog (all dependency versions live here)
```

---

## Commit Convention

Use conventional commits with phase tags:

```
feat(data): add FeedEntity and EpisodeEntity Room entities [phase-1]
feat(data): add BeyondPodDatabase and all DAOs [phase-1]
feat(repo): implement FeedRepository [phase-2]
feat(service): add PlaybackService skeleton [phase-3]
```

Commit at logical checkpoints — do not batch an entire phase into one commit.

---

## What NOT to Do

- Do not create a `FeatureModule` or multi-module structure — single module for v1.0
- Do not add RxJava — Coroutines + Flow only
- Do not use a third-party RSS parsing library — custom SAX parser as specced in §7.1
- Do not add Firebase, Analytics, or any telemetry — zero telemetry is a project principle
- Do not use `fallbackToDestructiveMigration` — all Room migrations must be explicit
- Do not read the entire BEYONDPOD_REVIVAL_SPEC.md in one go — read only the §sections
  relevant to the current phase to preserve token budget
