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

**PHASE: 7 — Import/Export + Settings complete**

Update this line at the start of each session to reflect where you are:
- Phase 0: Scaffold only (project exists, no feature code)
- Phase 1: Data layer complete
- Phase 2: Repositories + Use Cases complete
- Phase 3: Core Services complete (PlaybackService, DownloadWorker, FeedUpdateWorker)
- Phase 4: Navigation shell + My Episodes UI
- Phase 5: Feed management UI
- Phase 6: SmartPlay + Queue UI
- Phase 7: Import/Export + Settings
- Phase 8: Widgets + Polish

**Spec sections for current phase**: §6 Repository Layer (next: Phase 2)

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

4. **isProtected is an absolute veto** — `EpisodeEntity.isProtected = true` means never
   auto-delete under any circumstances. Enforced at cleanup, download deletion, and manual
   delete prompts. No exceptions.

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
