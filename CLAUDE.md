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
- **Early Beta (v1.0.10-beta): Backlog suppression applied to manual refresh (2026-05-15). Root cause: suppression check had !isManualRefresh exception — manual pull-to-refresh bypassed it and downloaded old NOT_DOWNLOADED backlog (Jan-Feb 2025) even when window was full of newer content. The gap: newer episodes from the interim were in DELETED state (auto-cleaned by retention), leaving only old NOT_DOWNLOADED ones as candidates. Fix: removed !isManualRefresh and allDownloaded.size >= keepCount from both DOWNLOAD_NEWEST and GLOBAL suppression conditions. Now suppresses whenever trulyNew == 0 in all modes. Old backlog is never auto-downloaded; users tap individual episodes to download specific historical content.**

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
