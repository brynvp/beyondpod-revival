# BeyondPod Revival — Full Technical Specification

> **For Claude Code**: This document is the authoritative blueprint for the BeyondPod Revival Android app. Build every feature described here unless explicitly marked `[FUTURE]`. Where implementation detail is not prescribed, use idiomatic modern Android. Never deviate from the architecture or data model without strong reason.

---

## 0. Design Philosophy (Read Before Writing Any Code)

BeyondPod was not merely a podcast app. It was a **deterministic, user-controlled, offline-first media system**. Every architectural decision must be tested against these three principles:

### 0.1 Queue-First, Not Feed-First

The active playback queue is the primary object in the system — not the feed list, not episode subscriptions. The user's current listening session is sacred. Feed updates, new downloads, rule changes, and network events must **never mutate the in-progress queue without explicit user action**. Once a queue is built (manually or by SmartPlay), it is a frozen snapshot. The feeds database is just the reservoir from which queues are drawn.

### 0.2 Rules-Driven, Not Subscription-Driven

The system is programmable. Users define rules; the app executes them deterministically. The same rules on the same data must always produce the same queue. There are no recommendations, no algorithmic surprises, no "you might also like". The SmartPlay engine is programmable radio — the user writes the programme.

### 0.3 Offline-First with Deterministic Behaviour

The app must be fully functional with no network connection. Queue, downloads, playback, settings, history — all must work offline. No feature should silently degrade or produce different results based on network state. Streaming-only episodes must be clearly distinguished from downloaded episodes so users know exactly what is available offline.

### 0.4 Corollaries (Claude Code Must Honour These)

- **No surprises**: if an action has a side effect, it must be visible and reversible
- **User always wins**: manual edits always override rule-generated state; the system must never "fight back"
- **Isprotected = inviolable**: a user-protected episode is never auto-deleted under any circumstance
- **Position fidelity**: playback position is saved every 5 seconds and on every pause; data loss of listening progress is a critical bug

---

## Table of Contents

1. [Project Overview](#1-project-overview)
2. [Architecture](#2-architecture)
3. [Module & Package Structure](#3-module--package-structure)
4. [Dependencies (build.gradle.kts)](#4-dependencies)
5. [Data Layer — Room Entities & DAOs](#5-data-layer)
6. [Repository Layer](#6-repository-layer)
7. [Feature Specifications](#7-feature-specifications)
   - 7.1 Feed (Podcast) Management
   - 7.2 Episode Management
   - 7.3 Categories & Virtual Folders
   - 7.4 Smart Playlists
   - 7.5 My Episodes Queue
   - 7.6 Playback Engine
   - 7.7 Download Manager
   - 7.8 Feed Discovery & Search
   - 7.9 Import / Export (OPML & Backup)
   - 7.10 Cross-Device Sync
   - 7.11 Home Screen Widgets
   - 7.12 Android Auto / Car Mode
   - 7.13 Notifications
   - 7.14 Settings
8. [UI Screens & Navigation](#8-ui-screens--navigation)
9. [Services & Background Work](#9-services--background-work)
10. [Permissions & Intent Filters](#10-permissions--intent-filters)
11. [First-Run Onboarding Flow](#11-first-run-onboarding-flow)
12. [Theming & Design System](#12-theming--design-system)
13. [Testing Strategy](#13-testing-strategy)

---

## 1. Project Overview

### What We Are Building

BeyondPod Revival is a **free, open-source, MIT-licensed** Android podcast manager that faithfully recreates BeyondPod 4.x — one of the most capable podcast apps ever built for Android before it was abandoned. The codebase will be hosted on GitHub, community-maintained, and built entirely with modern Android tech.

### Goals

- Exact functional parity with BeyondPod 4.3.321 (the final release)
- No ads, no paywalls, no telemetry
- MIT license, fully open source
- Minimum SDK: API 26 (Android 8.0); Target SDK: API 34
- Kotlin-first, Jetpack Compose UI, single-module to start

### App Identity

| Property | Value |
|---|---|
| Application ID | `mobi.beyondpod.revival` |
| App Name | `BeyondPod` |
| Version Name | `5.0.0` |
| Min SDK | 26 |
| Target SDK | 34 |
| License | MIT |

### Key Non-Goals (v1.0)

- No Feedly integration (Feedly API deprecated for free use)
- No Google Cast in v1.0 (add as `[FUTURE]` stub)
- No in-app browser/plugin system

---

## 2. Architecture

### Pattern: Clean Architecture + MVVM

```
┌─────────────────────────────────────────┐
│              UI Layer                   │
│   Compose Screens + ViewModels          │
│   (mobi.beyondpod.ui.*)                 │
├─────────────────────────────────────────┤
│           Domain Layer                  │
│   UseCases + Domain Models              │
│   (mobi.beyondpod.domain.*)             │
├─────────────────────────────────────────┤
│            Data Layer                   │
│   Repositories + Room + Network         │
│   (mobi.beyondpod.data.*)               │
├─────────────────────────────────────────┤
│         Infrastructure                  │
│   Services, WorkManager, Widgets        │
│   (mobi.beyondpod.service.*)            │
└─────────────────────────────────────────┘
```

### Key Architectural Decisions

**State management**: Each screen has a dedicated ViewModel. UI state is modelled as a sealed `UiState<T>` (Loading, Success, Error). All state flows downward as `StateFlow`; all events flow upward as lambdas or `SharedFlow`.

**Navigation**: Single-Activity with Jetpack Navigation Compose. The `NavHost` lives in `MainActivity`. Deep links from intent filters route into the nav graph.

**Dependency Injection**: Hilt (Dagger). All ViewModels use `@HiltViewModel`. All repositories are `@Singleton`. WorkManager workers use `HiltWorker`.

**Async**: Kotlin Coroutines + Flow everywhere. No RxJava. Repository functions return `Flow<T>` for streams and `suspend fun` for one-shots.

**Database**: Room with TypeConverters for enums and complex types. Migrations must be written explicitly — no `fallbackToDestructiveMigration` in production.

**Networking**: OkHttp 4.x + Retrofit for REST. Custom RSS/Atom parser (no third-party RSS lib — too many edge cases). Rome or custom SAX parser for feed parsing.

**Playback**: AndroidX Media3 (ExoPlayer). `MediaSessionService` for background playback.

**Image loading**: Coil 2.x.

---

## 3. Module & Package Structure

### Single Module (v1.0), Logical Package Separation

```
app/
└── src/main/
    ├── java/mobi/beyondpod/
    │   ├── BeyondPodApplication.kt           # Hilt application, initialises WorkManager
    │   │
    │   ├── ui/
    │   │   ├── MainActivity.kt               # Single activity, NavHost
    │   │   ├── navigation/
    │   │   │   ├── BeyondPodNavGraph.kt      # NavHost + all destinations
    │   │   │   └── Screen.kt                 # sealed class for routes
    │   │   ├── screens/
    │   │   │   ├── splash/
    │   │   │   ├── main/                     # MasterView — feed list + episode list
    │   │   │   ├── player/                   # Full-screen player
    │   │   │   ├── feedprops/                # Feed Properties
    │   │   │   ├── categoryprops/            # Category Properties
    │   │   │   ├── smartplaylist/            # Smart Playlist Editor
    │   │   │   ├── playlist/                 # My Episodes / Playlist view
    │   │   │   ├── discover/                 # Discover/Add Feed
    │   │   │   ├── impexp/                   # Import/Export (OPML, Backup)
    │   │   │   ├── settings/                 # Settings screens (nested)
    │   │   │   ├── sync/                     # Cross-Device Sync settings
    │   │   │   ├── downloadqueue/            # Download queue activity
    │   │   │   └── firstrun/                 # First-run wizard
    │   │   ├── components/
    │   │   │   ├── EpisodeCard.kt            # Shared episode card composable
    │   │   │   ├── MiniPlayer.kt             # Persistent mini-player bar
    │   │   │   ├── NavigationDrawer.kt       # App drawer
    │   │   │   ├── SwipeableEpisodeItem.kt   # Swipeable row
    │   │   │   └── ...
    │   │   └── theme/
    │   │       ├── Theme.kt
    │   │       ├── Color.kt
    │   │       └── Type.kt
    │   │
    │   ├── domain/
    │   │   ├── model/                        # Pure Kotlin domain models (no Android)
    │   │   │   ├── Feed.kt
    │   │   │   ├── Episode.kt
    │   │   │   ├── Category.kt
    │   │   │   ├── SmartPlaylist.kt
    │   │   │   ├── SmartPlaylistRule.kt
    │   │   │   ├── PlaybackState.kt
    │   │   │   ├── DownloadState.kt
    │   │   │   └── SyncState.kt
    │   │   └── usecase/
    │   │       ├── feed/
    │   │       ├── episode/
    │   │       ├── playlist/
    │   │       ├── playback/
    │   │       ├── download/
    │   │       └── sync/
    │   │
    │   ├── data/
    │   │   ├── local/
    │   │   │   ├── BeyondPodDatabase.kt
    │   │   │   ├── entity/                   # Room @Entity classes
    │   │   │   ├── dao/                      # Room @Dao interfaces
    │   │   │   └── converter/                # TypeConverters
    │   │   ├── remote/
    │   │   │   ├── feed/
    │   │   │   │   ├── FeedFetcher.kt        # OkHttp feed downloader
    │   │   │   │   └── FeedParser.kt         # RSS/Atom parser
    │   │   │   └── sync/
    │   │   │       └── GpodderApiService.kt  # gpodder.net REST client
    │   │   ├── repository/
    │   │   │   ├── FeedRepository.kt
    │   │   │   ├── EpisodeRepository.kt
    │   │   │   ├── CategoryRepository.kt
    │   │   │   ├── PlaylistRepository.kt
    │   │   │   ├── DownloadRepository.kt
    │   │   │   └── SyncRepository.kt
    │   │   └── prefs/
    │   │       └── AppPreferences.kt         # DataStore Preferences
    │   │
    │   ├── service/
    │   │   ├── playback/
    │   │   │   ├── PlaybackService.kt        # Media3 MediaSessionService
    │   │   │   └── PlaybackNotificationManager.kt
    │   │   ├── download/
    │   │   │   └── DownloadWorker.kt         # HiltWorker via WorkManager
    │   │   ├── update/
    │   │   │   └── FeedUpdateWorker.kt       # Scheduled feed refresh
    │   │   └── sync/
    │   │       └── SyncWorker.kt
    │   │
    │   ├── widget/
    │   │   ├── BeyondPodWidget.kt            # AppWidgetProvider base
    │   │   └── WidgetUpdateReceiver.kt
    │   │
    │   └── receiver/
    │       ├── BootReceiver.kt               # Reschedule WorkManager on boot
    │       └── BluetoothReceiver.kt          # Bluetooth connect/disconnect handling
    │
    └── res/
        ├── layout/                           # XML for widgets only (AppWidgetProvider)
        ├── xml/
        │   ├── widget_*.xml                  # AppWidgetProviderInfo for each size
        │   └── backup_rules.xml
        └── values/
```

---

## 4. Dependencies

```kotlin
// build.gradle.kts (app)

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

dependencies {
    // Compose BOM
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.activity.compose)
    debugImplementation(libs.compose.ui.tooling)

    // Navigation
    implementation(libs.navigation.compose)

    // Lifecycle + ViewModel
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.hilt.work)
    ksp(libs.hilt.ext.compiler)

    // Room
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // DataStore
    implementation(libs.datastore.preferences)

    // Media3 (ExoPlayer)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    implementation(libs.media3.ui)
    implementation(libs.media3.datasource.okhttp)

    // Networking
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.retrofit)
    implementation(libs.retrofit.gson)

    // Coil
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    // WorkManager
    implementation(libs.work.runtime.ktx)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // Gson
    implementation(libs.gson)

    // AndroidX Core
    implementation(libs.core.ktx)
    implementation(libs.appcompat)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.room.testing)
    testImplementation(libs.mockk)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.compose.ui.test.junit4)
}
```

---

## 5. Data Layer

### 5.1 Room Entities

#### FeedEntity

```kotlin
@Entity(tableName = "feeds")
data class FeedEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val url: String,                          // Canonical RSS/Atom URL
    val title: String,
    val description: String = "",
    val imageUrl: String? = null,
    val author: String = "",
    val website: String = "",
    val language: String = "",

    // IMPORTANT: Each feed can belong to UP TO 2 categories (BeyondPod original behaviour).
    // Primary category drives display position in navigator. Secondary is additive only.
    // Stored in FeedCategoryCrossRef join table — these fields are denormalised caches only.
    val primaryCategoryId: Long? = null,      // Drives navigator placement
    val secondaryCategoryId: Long? = null,    // Optional second category membership

    val isVirtualFeed: Boolean = false,       // True = virtual folder feed
    val virtualFeedFolderPath: String? = null,// Folder path for virtual feeds
    val sortOrder: Int = 0,                   // User-defined sort position within category
    val priority: Int = 0,                    // 0=Normal, 1=High — affects SmartPlay ordering tiebreaks

    // HTTP Authentication (per-feed for premium/paywalled feeds)
    // Original BeyondPod stored per-feed passwords in SharedPreferences under the key
    //   "PRIVATE_FEED_DATA:{feedId}" where feedId was a UUID string.
    // Revival stores authUsername in FeedEntity (plaintext — username is not sensitive) and
    // authPassword in EncryptedSharedPreferences under key "feed_password_{feedId}" where
    // feedId is the Revival Long primary key. Never store plaintext passwords in Room.
    val authUsername: String? = null,
    // authPassword is NOT stored in FeedEntity — it lives in EncryptedSharedPreferences.
    // FeedEntity carries only a flag indicating that a password is set.
    val hasAuthPassword: Boolean = false,

    // Update settings (per-feed, can override global)
    val autoUpdate: Boolean? = null,          // null = use global setting
    val updateIntervalMinutes: Int? = null,   // null = use category/global
    val updateSchedule: String? = null,       // JSON: scheduled update times

    // Download settings (per-feed)
    // downloadStrategy controls how auto-download works for this feed
    val downloadStrategy: DownloadStrategy = DownloadStrategy.GLOBAL,
    val downloadCount: Int? = null,           // null = use global setting
    val maxEpisodesToKeep: Int? = null,       // null = use global; 0 = keep all
    val downloadOnlyOnWifi: Boolean? = null,  // null = use global
    val allowCleanupForManual: Boolean = false, // Apply cleanup to manually-downloaded episodes too

    // Playback settings (per-feed override)
    val playbackSpeed: Float? = null,         // null = use global; range 0.5x–4.0x
    val skipIntroSeconds: Int = 0,            // Skip first N seconds of every episode
    val skipOutroSeconds: Int = 0,            // Skip last N seconds
    // Volume boost — implemented via Android LoudnessEnhancer (NOT player.volume > 1.0f).
    // 0 = use global setting, 1 = no boost (0dB), 2–10 = increasing gain (~1dB per step, max 10dB).
    // Map to LoudnessEnhancer.setTargetGain(gainMillibels) in PlaybackService. See §7.6.
    val playbackVolumeBoost: Int = 0,

    // Episode sort order override (null = use global preference)
    val episodeSortOrder: EpisodeSortOrder? = null,

    // Display
    val displayType: Int = 0,                 // 0=Card Type 1, 1=Card Type 2, 2=Card Type 3
    val showDescription: Boolean = true,
    val useCustomImage: Boolean = false,
    val customImagePath: String? = null,

    // Feed state
    val lastUpdated: Long = 0L,               // epoch millis
    val lastUpdateFailed: Boolean = false,
    val lastUpdateError: String? = null,
    val useGoogleProxy: Boolean = false,

    // Episode retention / age limits (per-feed, override global)
    // maxTrackAgeDays: max age in days before episodes are eligible for cleanup.
    // 99999 = keep forever (BeyondPod's sentinel value — confirmed in real DB).
    // Interacts with maxEpisodesToKeep: BOTH must be satisfied to trigger cleanup.
    val maxTrackAgeDays: Int = 99999,

    // Feed fingerprinting / duplicate detection method (from original DB fingerprintType field)
    //   -1 = virtual folder feed (no fingerprinting)
    //    1 = hash-based fingerprinting (byte content hash)
    //    2 = GUID-based fingerprinting (RSS GUID field)
    // Revival default is GUID-based (2) for RSS feeds; virtual feeds use -1.
    val fingerprintType: Int = 2,

    // Playback speed store — mirrors original audioSettings field format "speedIndex|"
    // speedIndex is a 0-based index into the speed lookup table:
    //   [0.5, 0.6, 0.7, 0.8, 0.9, 1.0, 1.1, 1.2, 1.3, 1.4, 1.5, 1.75, 2.0, 2.5, 3.0]
    // -1 = use global setting (stored in playbackSpeed field as Float for convenience)
    val audioSettingsSpeedIndex: Int = -1,

    // Tracking
    val createdAt: Long = System.currentTimeMillis(),
    val isHidden: Boolean = false,

    // gpodder sync
    val gpodderSubscribed: Boolean = true
)

/**
 * Download strategy controls how this feed's episodes are auto-downloaded.
 *
 * GLOBAL            — inherit from global/category settings
 * DOWNLOAD_NEWEST   — always get the N most recent episodes (good for news feeds).
 *                     Episode cleanup runs BEFORE downloading new ones.
 * DOWNLOAD_IN_ORDER — start with OLDEST episodes and work forward (good for serial content,
 *                     audiobooks, courses). Cleanup does NOT run automatically.
 * STREAM_NEWEST     — create streamable references instead of downloading files. Episodes
 *                     play from URL on-demand; requires internet connection.
 * MANUAL            — no automatic downloading; user triggers all downloads manually.
 *                     Cleanup can optionally be enabled via allowCleanupForManual.
 */
enum class DownloadStrategy {
    GLOBAL, DOWNLOAD_NEWEST, DOWNLOAD_IN_ORDER, STREAM_NEWEST, MANUAL
}
```

#### FeedCategoryCrossRef

```kotlin
/**
 * Supports BeyondPod's original feature: each feed can belong to up to 2 categories.
 * Primary category (isPrimary = true) drives navigator placement.
 * Secondary category makes the feed visible in a second category's episode list.
 *
 * FK policy: CASCADE on feedId (feed deleted → remove cross-ref rows).
 * NO cascade on categoryId — deleting a category must NOT delete feeds or cross-ref rows
 * automatically. The CategoryRepository must manually delete cross-ref rows before deleting
 * the CategoryEntity, then null out primaryCategoryId/secondaryCategoryId on affected FeedEntity
 * rows. This is the "move to Uncategorized" behaviour. See §7.3 Category Deletion. (QA #3a.)
 */
@Entity(
    tableName = "feed_category_cross_ref",
    primaryKeys = ["feedId", "categoryId"],
    foreignKeys = [ForeignKey(
        entity = FeedEntity::class,
        parentColumns = ["id"],
        childColumns = ["feedId"],
        onDelete = ForeignKey.CASCADE   // Cross-ref removed when feed is deleted — correct
    )]
    // NOTE: No FK on categoryId — deliberate. Category deletion is handled manually in
    // CategoryRepository.deleteCategory() to preserve feeds (move to Uncategorized).
)
data class FeedCategoryCrossRef(
    val feedId: Long,
    val categoryId: Long,
    val isPrimary: Boolean = true
)
```

#### EpisodeEntity

```kotlin
@Entity(
    tableName = "episodes",
    foreignKeys = [ForeignKey(
        entity = FeedEntity::class,
        parentColumns = ["id"],
        childColumns = ["feedId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [
        Index("feedId"),
        Index("guid"),
        Index("pubDate"),
        Index("playState")
    ]
)
data class EpisodeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val feedId: Long,
    val guid: String,                         // RSS GUID — uniqueness key
    val title: String,
    val description: String = "",
    val htmlDescription: String = "",         // Raw HTML show notes
    val pubDate: Long = 0L,                   // epoch millis
    val url: String,                          // Enclosure / stream URL
    val mimeType: String = "audio/mpeg",
    val fileSizeBytes: Long = 0L,
    val duration: Long = 0L,                  // millis, 0 = unknown
    val imageUrl: String? = null,             // Episode artwork (overrides feed art)
    val author: String = "",
    val chapterUrl: String? = null,           // Podcast namespace chapters URL
    val transcriptUrl: String? = null,

    // Playback state
    // ARCHIVED = episode no longer appears in feed XML but was previously downloaded/known.
    //            Archived episodes can still be played but cannot be marked read/starred.
    val playState: PlayState = PlayState.NEW,
    val playPosition: Long = 0L,              // millis resume position (saved every 5s + on pause)
    val playedFraction: Float = 0f,           // 0.0–1.0 — used for "Played Portion" sort order
    val playCount: Int = 0,
    val lastPlayed: Long? = null,
    val isStarred: Boolean = false,           // "Favourite" in v4+ (was "Lock" in v3)
    // isProtected = the original BeyondPod "locked" flag (tracks.locked column in original DB).
    // TRUE = never auto-delete under any circumstances, regardless of cleanup rules or episode age.
    // Set by user explicitly. Overrides ALL cleanup policies. Survives "delete all played" actions.
    // Must be honoured absolutely — this is a user promise, not a hint.
    val isProtected: Boolean = false,
    val isArchived: Boolean = false,          // Episode no longer in feed; local copy still exists
    val lastAccessed: Long? = null,           // Last time file was accessed (for storage analytics)

    // Download state
    val downloadState: DownloadStateEnum = DownloadStateEnum.NOT_DOWNLOADED,
    val localFilePath: String? = null,
    val downloadedAt: Long? = null,
    val downloadId: Long? = null,             // WorkManager job ID (UUID as String via TypeConverter)
    val downloadProgress: Int = 0,            // 0–100 (derived from downloadBytesDownloaded/downloadTotalBytes)
    // Partial download tracking — enables resume via HTTP Range header (OkHttp).
    // downloadBytesDownloaded: exact bytes written to disk so far (NOT a percentage).
    //   This maps to original DB column `downloadPortion` which also stores raw bytes.
    //   When downloadBytesDownloaded == fileSizeBytes, the download is complete.
    //   On resume: send `Range: bytes={downloadBytesDownloaded}-` header.
    val downloadBytesDownloaded: Long = 0L,
    val downloadTotalBytes: Long = 0L,        // 0 if unknown (server didn't provide Content-Length)

    // INTENTIONALLY NO isInQueue / queuePosition FIELDS ON THIS ENTITY.
    // The active queue is owned exclusively by QueueSnapshotEntity + QueueSnapshotItemEntity.
    // Episode-level queue flags are a contradiction of the snapshot model: they would become
    // stale the instant a new snapshot is generated, creating a permanent consistency hazard.
    // To determine whether an episode is in the current queue, query the active snapshot:
    //   SELECT * FROM queue_snapshot_items WHERE snapshotId = (active) AND episodeId = :id
    // Do not re-introduce these fields. (QA finding #1, confirmed correct.)

    // Metadata
    val isInMyEpisodes: Boolean = false,
    val addedToMyEpisodes: Long? = null,
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * Episode play states. Transitions:
 *   NEW → IN_PROGRESS (playback starts)
 *   IN_PROGRESS → PLAYED (playback reaches ≥90% or user marks played)
 *   IN_PROGRESS → NEW (user marks unplayed)
 *   PLAYED → NEW (user marks unplayed — fully reversible)
 *   ANY → SKIPPED (user explicitly skips without playing)
 * Archived is a separate flag (isArchived), not a play state.
 * All transitions are reversible. None are permanent.
 */
enum class PlayState { NEW, IN_PROGRESS, PLAYED, SKIPPED }

enum class DownloadStateEnum {
    NOT_DOWNLOADED,
    QUEUED,           // In WorkManager queue, not started
    DOWNLOADING,      // Actively downloading (progress tracked)
    DOWNLOADED,       // Complete, localFilePath is valid
    FAILED,           // Last attempt failed; retryCount tracked in DownloadQueueEntity
    DELETED           // Was downloaded, file explicitly deleted; record kept for history
}
```

#### Episode Identity & Deduplication Strategy

> **Critical** (QA finding #4): Podcast GUIDs are notoriously unreliable in the wild — publishers reuse them, omit them, or change them when re-uploading episodes. The original BeyondPod supported multiple fingerprinting modes (`fingerprintType` field: -1=virtual, 1=hash, 2=GUID). Revival must implement a priority-ordered multi-key deduplication strategy at every episode ingestion point (feed refresh, OPML import, legacy backup import).

**Identity resolution priority order (applied in `EpisodeRepository.upsertEpisode()`):**

```
Priority 1 — GUID match (most reliable when present and stable)
  Match: episodes.guid == incoming.guid AND episodes.feedId == feedId
  Use when: fingerprintType == 2 (GUID-based) AND guid is non-empty AND guid != url

Priority 2 — URL match (episode enclosure URL is usually stable)
  Match: episodes.url == incoming.url AND episodes.feedId == feedId
  Use when: GUID match fails OR guid is absent/empty
  Note: some publishers host files on CDNs with changing URLs — this will miss those

Priority 3 — Title + Duration heuristic (fallback for unreliable GUIDs)
  Match: normalise(episodes.title) == normalise(incoming.title)
         AND feedId == feedId
         AND ABS(episodes.duration - incoming.duration) < 5000ms (5-second tolerance)
  Use when: Priority 1 and 2 both fail
  Risk: may produce false positives for serial content with same-named episodes

Priority 4 — File hash (strongest identity, highest cost — virtual feeds only)
  Match: SHA-256 of local file == SHA-256 of incoming file
  Use when: fingerprintType == 1 (hash-based) AND local file exists
  Only applies to virtual feeds (isVirtualFeed = true)

Priority 5 — No match → treat as new episode
  Create new EpisodeEntity record
```

**`EpisodeEntity.guid` population rules:**
- If RSS item has `<guid>` element: use it as-is
- If `<guid>` is absent: use the enclosure URL as guid (common fallback)
- If no enclosure URL: use SHA-256 of (feedId + title + pubDate) as synthetic guid
- Never leave guid empty — it is the primary dedup index

**Normalisation for title comparison:**
- Lowercase, trim whitespace, collapse internal whitespace to single space
- Strip common prefixes: episode numbers e.g. "#123", "EP123", "Ep. 123 —"
- Strip HTML entities

This strategy mirrors what the original BeyondPod's `fingerprintType` field controlled, but makes the decision logic explicit rather than leaving it as an opaque integer.

#### CategoryEntity

```kotlin
@Entity(tableName = "categories")
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val color: Int = 0xFF2196F3.toInt(),      // ARGB
    val sortOrder: Int = 0,
    val isExpanded: Boolean = true,           // In nav drawer
    val parentCategoryId: Long? = null,       // For nested categories (future)

    // Per-category update schedule (overrides global)
    val autoUpdate: Boolean = true,
    val updateIntervalMinutes: Int? = null,   // null = use global
    val updateSchedule: String? = null,       // JSON schedule
    val downloadOnlyOnWifi: Boolean? = null,

    // Category-level auto-download defaults
    val autoDownload: Boolean? = null,
    val downloadCount: Int? = null,
    val maxEpisodesToKeep: Int? = null
)
```

#### SmartPlaylistEntity

```kotlin
@Entity(tableName = "smart_playlists")
data class SmartPlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val sortOrder: Int = 0,
    val isDefault: Boolean = false,           // The "My Episodes" default playlist

    // IMPORTANT — legacy import note for SmartPlaylist:
    // In original BeyondPod's `smartplaylist` table, the `categoryId` column stores the
    // CATEGORY NAME as a string (e.g., "News"), NOT an integer ID. This is a quirk of the
    // original implementation. During legacy .bpbak import, resolve category name → Revival
    // CategoryEntity.id before populating SmartPlaylistBlock.sourceId.
    // In Revival's own data model, all IDs are Long primary keys (no name-as-ID).

    // Rules JSON — see SmartPlaylistRule below. Two modes:
    //
    //   SEQUENTIAL_BLOCKS — PRIMARY / LEGACY-COMPATIBLE MODE.
    //     This is how the original BeyondPod SmartPlay worked. Confirmed in real DB:
    //     `smartplaylist` table has numEpisodes, sortOrder, feedId, categoryId — all
    //     Sequential Block semantics. No evidence of filter-predicate fields in original.
    //     Imported legacy playlists ALWAYS use this mode. It is the "programmable radio" model.
    //     Users familiar with BeyondPod will recognise this. Show it first in the editor UI.
    //
    //   FILTER_RULES — ADVANCED / REVIVAL-NATIVE MODE.
    //     More expressive predicate-based system. Not in original BeyondPod. Revival extension.
    //     New playlists created from scratch in Revival default to SEQUENTIAL_BLOCKS for
    //     backward-compatible mental model, but users can switch to FILTER_RULES in the editor.
    //
    // UI labeling: present SEQUENTIAL_BLOCKS as "Standard" and FILTER_RULES as "Advanced".
    // ruleMode defaults to SEQUENTIAL_BLOCKS so the first-run experience mirrors original app.
    val ruleMode: PlaylistRuleMode = PlaylistRuleMode.SEQUENTIAL_BLOCKS,
    val rulesJson: String = "[]",

    val maxItems: Int = 0,                    // 0 = unlimited
    val episodeSortOrder: EpisodeSortOrder = EpisodeSortOrder.PUB_DATE_DESC,
    val autoPlay: Boolean = false,
    val continueOnComplete: Boolean = true,   // Auto-advance to next episode
    val onEmptyAction: OnEmptyAction = OnEmptyAction.DO_NOTHING,
    val iconResName: String? = null,

    // Per-playlist playback overrides (if non-null, override global/feed settings for this session)
    val playbackSpeedOverride: Float? = null,
    val volumeBoostOverride: Int? = null,     // 0 = no boost, 1–10
    val skipSilenceOverride: Boolean? = null
)

/**
 * SEQUENTIAL_BLOCKS = "Standard" mode. Original BeyondPod's model. Primary/legacy-compatible.
 *   Execution: blocks run in order, each contributing N episodes from a source.
 *   Mental model: "programmable radio schedule" — block 1 plays, then block 2, etc.
 *   Use this for: all imported legacy playlists; all new user playlists by default.
 *
 * FILTER_RULES = "Advanced" mode. Revival extension. Predicate-based episode pool filtering.
 *   Execution: all rules evaluated against episode pool, result sorted by episodeSortOrder.
 *   Mental model: "smart query" — returns the N episodes that match all conditions.
 *   Use this for: power users who need AND/OR predicate logic across feed/date/state/duration.
 *
 * QA finding #1: Sequential Blocks is the proven original model. Filter Rules is an enhancement.
 * Label them "Standard" and "Advanced" in the UI. Never call Sequential Blocks "legacy" to users.
 */
enum class PlaylistRuleMode { SEQUENTIAL_BLOCKS, FILTER_RULES }

/**
 * What to do when SmartPlay rule evaluation returns zero episodes.
 */
enum class OnEmptyAction {
    DO_NOTHING,          // Leave queue empty, show empty state
    FALLBACK_ALL_UNPLAYED // Fall back to all unplayed episodes across all feeds
}

enum class EpisodeSortOrder {
    PUB_DATE_DESC, PUB_DATE_ASC,
    DURATION_DESC, DURATION_ASC,
    FEED_TITLE_ASC, FEED_TITLE_DESC,
    TITLE_ASC, TITLE_DESC,
    DOWNLOAD_DATE_DESC,
    PLAYED_PORTION_ASC,  // Episodes closest to completion first (resume listening)
    FILE_NAME_ASC,
    MANUAL               // User-defined order in ManualPlaylistEpisodeCrossRef
}
```

#### SmartPlaylistRuleEntity (embedded in rulesJson)

**Two rule models exist. Both are supported.**

**Model A — Sequential Blocks (original BeyondPod SmartPlay)**

This is how the original SmartPlay worked. Rules are ordered execution blocks. Each block says "get N episodes from [source] in [order]". The queue is built by executing blocks in sequence. This is the "programmable radio" model.

```kotlin
/**
 * One block in a sequential SmartPlay programme.
 * Example programme: [
 *   SmartPlaylistBlock(count=2, source=CATEGORY, sourceId=3, order=NEWEST),  // 2 newest from Tech
 *   SmartPlaylistBlock(count=1, source=FEED, sourceId=12, order=OLDEST),     // 1 oldest from BBC
 *   SmartPlaylistBlock(count=3, source=ALL, sourceId=null, order=RANDOM),    // 3 random
 * ]
 */
data class SmartPlaylistBlock(
    val count: Int,                           // Number of episodes to get
    val source: BlockSource,
    val sourceId: Long? = null,               // feedId or categoryId; null if source=ALL
    val order: BlockEpisodeOrder,
    val onlyDownloaded: Boolean = false       // Only include downloaded episodes
)

enum class BlockSource { ALL_FEEDS, FEED, CATEGORY }
enum class BlockEpisodeOrder { NEWEST, OLDEST, RANDOM }
```

**Model B — Filter Rules (enhanced, this implementation's default)**

More expressive. Rules are predicates combined with AND/OR. The episode pool matching all rules is sorted by `EpisodeSortOrder` and limited by `maxItems`. This model is a superset of Model A's capabilities.

```kotlin
data class SmartPlaylistRule(
    val field: RuleField,
    val operator: RuleOperator,
    val value: String,
    val logicalConnector: LogicalConnector = LogicalConnector.AND
)

enum class RuleField {
    PLAY_STATE,          // Value: "NEW" | "IN_PROGRESS" | "PLAYED" | "SKIPPED"
    IS_STARRED,          // Value: "true" | "false"
    IS_DOWNLOADED,       // Value: "true" | "false"
    IS_PROTECTED,        // Value: "true" | "false"
    FEED_ID,             // Value: feed.id as string
    CATEGORY_ID,         // Value: category.id as string
    PUB_DATE,            // Value: ISO date string "YYYY-MM-DD"
    DURATION,            // Value: seconds as string
    IS_IN_MY_EPISODES,   // Value: "true" | "false"
    TITLE_CONTAINS,      // Value: substring
    FILE_TYPE,           // Value: "audio" | "video"
    PLAYED_FRACTION      // Value: float 0.0–1.0 as string
}

enum class RuleOperator {
    IS, IS_NOT,
    IS_BEFORE, IS_AFTER,           // For dates
    CONTAINS, DOES_NOT_CONTAIN,    // For strings
    GREATER_THAN, LESS_THAN        // For numbers/duration
}

enum class LogicalConnector { AND, OR }
```

#### QueueSnapshotEntity (Queue Immutability — Critical)

```kotlin
/**
 * A queue snapshot is an immutable ordered list of episode IDs generated at a point in time.
 *
 * DESIGN PRINCIPLE (§0.1): The active queue is NEVER a live database query. It is always
 * a frozen snapshot. Feed updates, new downloads, and rule changes DO NOT affect the
 * in-progress queue. The user must explicitly trigger "Regenerate Queue" to rebuild it.
 *
 * There is only ever ONE active snapshot (the currently playing queue).
 * Previous snapshots are deleted when a new one is generated.
 */
@Entity(tableName = "queue_snapshots")
data class QueueSnapshotEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourcePlaylistId: Long? = null,       // Which SmartPlaylist generated this (null = manual)
    val generatedAt: Long = System.currentTimeMillis(),
    val isActive: Boolean = true,             // Only one snapshot is active at a time
    val currentItemIndex: Int = 0,            // Index into QueueSnapshotItemEntity list
    val currentItemPositionMs: Long = 0L      // Playback position in current item
)

@Entity(
    tableName = "queue_snapshot_items",
    foreignKeys = [ForeignKey(
        entity = QueueSnapshotEntity::class,
        parentColumns = ["id"],
        childColumns = ["snapshotId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("snapshotId"), Index("position")]
)
/**
 * One item in a queue snapshot.
 *
 * FILE-BACKED QUEUE RESILIENCE (QA finding #2):
 * The original BeyondPod stored the queue as absolute device file paths (PlayList.bin.autobak),
 * not episode IDs. This was not a design choice to emulate — it's an implementation artefact.
 * However, it had an important emergent property: the queue was resilient to metadata drift.
 * If a feed's GUID changed, or the episode record was corrupted, the queue still played the file.
 *
 * Revival uses episode IDs internally (correct modernisation), but replicates the resilience via
 * localFilePathSnapshot: a cached copy of the file path at snapshot generation time.
 *
 * Playback fallback logic in PlaybackService:
 *   1. Try to resolve episodeId → EpisodeEntity → localFilePath (happy path)
 *   2. If episode not found OR localFilePath is null/missing:
 *      try localFilePathSnapshot (file was downloaded at snapshot time, episode DB record stale)
 *   3. If both fail: mark item as unplayable, skip to next, show "File not available" snackbar
 *
 * This matches original BeyondPod's queue durability characteristics.
 */
data class QueueSnapshotItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val snapshotId: Long,
    val episodeId: Long,                      // References episodes.id (NOT a FK — episode may be deleted)
    val position: Int,                        // 0-based order in queue
    val episodeTitleSnapshot: String = "",    // Cached title — survives episode deletion/metadata change
    val feedTitleSnapshot: String = "",       // Cached feed title — survives feed deletion
    val localFilePathSnapshot: String? = null,// Cached file path at snapshot creation time.
                                              // Used as fallback if episode DB record goes stale.
                                              // Set to null for stream-only (no local file) episodes.
    val episodeUrlSnapshot: String = ""       // Cached stream URL — fallback for stream-only episodes
)
```

#### ManualPlaylistEpisodeCrossRef

```kotlin
@Entity(
    tableName = "manual_playlist_episodes",
    primaryKeys = ["playlistId", "episodeId"]
)
data class ManualPlaylistEpisodeCrossRef(
    val playlistId: Long,
    val episodeId: Long,
    val position: Int
)
```

#### DownloadQueueEntity

```kotlin
@Entity(tableName = "download_queue")
data class DownloadQueueEntity(
    @PrimaryKey val episodeId: Long,
    val queuePosition: Int,
    val addedAt: Long = System.currentTimeMillis(),
    val retryCount: Int = 0
)
```

#### SyncStateEntity

```kotlin
@Entity(tableName = "sync_state")
data class SyncStateEntity(
    @PrimaryKey val id: Int = 1,             // Singleton row
    val provider: SyncProvider = SyncProvider.NONE,
    val username: String? = null,
    val serverUrl: String = "https://gpodder.net",
    val deviceId: String = "",
    val lastSyncTimestamp: Long = 0L,
    val syncEnabled: Boolean = false,
    val syncEpisodeState: Boolean = true,    // Sync play position/state
    val syncSubscriptions: Boolean = true
)

enum class SyncProvider { NONE, GPODDER, NEXTCLOUD_GPODDERSYNC }
```

#### EpisodePlayHistoryEntity

```kotlin
/**
 * Audit log of play events. One row per play/pause/complete event.
 * Maps to the original BeyondPod `episode_history` table.
 * Used for:
 *   - "Recently Played" lists
 *   - gPodder sync episode actions (play/pause/complete must be timestamped)
 *   - Future scrobbling (Last.fm / ListenBrainz)
 *
 * entryType values (mirror original BeyondPod constants):
 *   0 = PLAY_START   — user started playback
 *   1 = PLAY_PAUSE   — user paused
 *   2 = PLAY_END     — reached end of episode (PLAYED state transition)
 *   3 = PLAY_SEEK    — user seeked (optional, for analytics)
 */
@Entity(
    tableName = "episode_history",
    indices = [Index("episodeId"), Index("eventTimestamp")]
)
data class EpisodePlayHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val episodeId: Long,                      // References episodes.id (NOT a FK — survive episode delete)
    val episodeGuid: String,                  // Cached GUID for gPodder sync matching
    val feedUrl: String,                      // Cached feed URL for gPodder sync matching
    val episodeUrl: String,                   // Cached episode URL for gPodder sync
    val eventTimestamp: Long = System.currentTimeMillis(), // Epoch millis
    val entryType: Int = 0,                   // 0=START, 1=PAUSE, 2=END, 3=SEEK
    val positionMs: Long = 0L                 // Playback position at time of event (millis)
)
```

#### ChangeHistoryEntity

```kotlin
/**
 * Records subscription changes (subscribe/unsubscribe) for gPodder sync diff upload.
 * Original BeyondPod had a `change_history` table for this purpose (confirmed in backup DB).
 * Changes accumulate until next sync, then are uploaded and cleared.
 *
 * changeType: "ADD" = subscribed, "REMOVE" = unsubscribed
 */
@Entity(tableName = "change_history")
data class ChangeHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val feedUrl: String,
    val changeType: String,                   // "ADD" | "REMOVE"
    val changedAt: Long = System.currentTimeMillis(),
    val syncedAt: Long? = null                // null = pending upload; set when successfully synced
)
```

#### ScheduledTaskEntity

```kotlin
/**
 * Stores user-defined scheduled update tasks separate from WorkManager's periodic constraints.
 * Original BeyondPod had a `scheduled_tasks` table (confirmed in backup DB, though 0 rows in
 * user's backup — feature existed but wasn't used by this user).
 *
 * Revival delegates actual execution to WorkManager, but stores user intent here so it can be
 * preserved across WorkManager cancellations (e.g., app updates, device reboots).
 *
 * taskType: "FEED_UPDATE" | "CATEGORY_UPDATE" | "FULL_UPDATE"
 * cronExpression: standard 5-field cron (e.g., "0 6 * * *" = daily at 06:00)
 */
@Entity(tableName = "scheduled_tasks")
data class ScheduledTaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val taskType: String = "FULL_UPDATE",
    val targetId: Long? = null,               // feedId or categoryId; null = all feeds
    val cronExpression: String? = null,       // null = use global update interval from settings
    val isEnabled: Boolean = true,
    val lastExecutedAt: Long? = null,
    val workManagerTag: String? = null        // WorkManager unique work name for cancellation
)
```

### 5.2 Room DAOs

#### FeedDao

```kotlin
@Dao
interface FeedDao {
    @Query("SELECT * FROM feeds WHERE isHidden = 0 ORDER BY sortOrder ASC, title ASC")
    fun getAllFeeds(): Flow<List<FeedEntity>>

    // Feeds in a category = feeds where this is primary OR secondary category
    @Query("""
        SELECT f.* FROM feeds f
        INNER JOIN feed_category_cross_ref x ON f.id = x.feedId
        WHERE x.categoryId = :categoryId AND f.isHidden = 0
        ORDER BY f.sortOrder ASC, f.title ASC
    """)
    fun getFeedsByCategory(categoryId: Long): Flow<List<FeedEntity>>

    @Query("SELECT * FROM feeds WHERE id = :feedId")
    suspend fun getFeedById(feedId: Long): FeedEntity?

    @Query("SELECT * FROM feeds WHERE url = :url LIMIT 1")
    suspend fun getFeedByUrl(url: String): FeedEntity?

    @Upsert
    suspend fun upsertFeed(feed: FeedEntity): Long

    @Delete
    suspend fun deleteFeed(feed: FeedEntity)

    @Query("UPDATE feeds SET lastUpdated = :timestamp, lastUpdateFailed = 0 WHERE id = :feedId")
    suspend fun markFeedUpdated(feedId: Long, timestamp: Long)

    @Query("UPDATE feeds SET sortOrder = :sortOrder WHERE id = :feedId")
    suspend fun updateSortOrder(feedId: Long, sortOrder: Int)

    // Category cross-ref operations
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFeedCategoryRef(ref: FeedCategoryCrossRef)

    @Query("DELETE FROM feed_category_cross_ref WHERE feedId = :feedId")
    suspend fun clearFeedCategories(feedId: Long)

    @Query("SELECT * FROM feed_category_cross_ref WHERE feedId = :feedId")
    suspend fun getCategoriesForFeed(feedId: Long): List<FeedCategoryCrossRef>
}
```

#### EpisodeDao

```kotlin
@Dao
interface EpisodeDao {
    @Query("SELECT * FROM episodes WHERE feedId = :feedId ORDER BY pubDate DESC")
    fun getEpisodesForFeed(feedId: Long): Flow<List<EpisodeEntity>>

    @Query("""
        SELECT * FROM episodes
        WHERE feedId = :feedId AND playState != 'PLAYED'
        ORDER BY pubDate DESC
        LIMIT :limit
    """)
    fun getUnplayedEpisodesForFeed(feedId: Long, limit: Int = 50): Flow<List<EpisodeEntity>>

    @Query("SELECT * FROM episodes WHERE id = :episodeId")
    suspend fun getEpisodeById(episodeId: Long): EpisodeEntity?

    @Query("SELECT * FROM episodes WHERE guid = :guid AND feedId = :feedId LIMIT 1")
    suspend fun getEpisodeByGuid(guid: String, feedId: Long): EpisodeEntity?

    @Upsert
    suspend fun upsertEpisode(episode: EpisodeEntity): Long

    @Query("UPDATE episodes SET playState = :state WHERE id = :episodeId")
    suspend fun updatePlayState(episodeId: Long, state: PlayState)

    @Query("UPDATE episodes SET playPosition = :position WHERE id = :episodeId")
    suspend fun updatePlayPosition(episodeId: Long, position: Long)

    // NOTE: updateQueueState() and getQueuedEpisodes() using isInQueue/queuePosition have been
    // REMOVED. Queue membership is determined solely by querying the active QueueSnapshotEntity.
    // Use QueueSnapshotDao for all queue read/write operations. (QA finding #1.)

    @Query("UPDATE episodes SET downloadState = :state, localFilePath = :path WHERE id = :episodeId")
    suspend fun updateDownloadState(episodeId: Long, state: DownloadStateEnum, path: String?)

    // Queue membership query — join through snapshot, not episode-level flags
    @Query("""
        SELECT e.* FROM episodes e
        INNER JOIN queue_snapshot_items qi ON e.id = qi.episodeId
        INNER JOIN queue_snapshots qs ON qi.snapshotId = qs.id
        WHERE qs.isActive = 1
        ORDER BY qi.position ASC
    """)
    fun getQueuedEpisodes(): Flow<List<EpisodeEntity>>

    // Check if a specific episode is in the active queue
    @Query("""
        SELECT COUNT(*) FROM queue_snapshot_items qi
        INNER JOIN queue_snapshots qs ON qi.snapshotId = qs.id
        WHERE qs.isActive = 1 AND qi.episodeId = :episodeId
    """)
    suspend fun isEpisodeInActiveQueue(episodeId: Long): Int  // > 0 means in queue

    @Query("SELECT * FROM episodes WHERE downloadState = 'DOWNLOADED' ORDER BY downloadedAt DESC")
    fun getDownloadedEpisodes(): Flow<List<EpisodeEntity>>

    @Query("SELECT * FROM episodes WHERE isStarred = 1 ORDER BY pubDate DESC")
    fun getStarredEpisodes(): Flow<List<EpisodeEntity>>

    @Query("""
        SELECT e.* FROM episodes e
        WHERE e.playState = 'NEW'
        AND e.feedId IN (
            SELECT f.id FROM feeds f
            INNER JOIN feed_category_cross_ref x ON f.id = x.feedId
            WHERE x.categoryId = :categoryId
        )
        ORDER BY e.pubDate DESC
        LIMIT :limit
    """)
    fun getNewEpisodesForCategory(categoryId: Long, limit: Int = 100): Flow<List<EpisodeEntity>>

    @Query("DELETE FROM episodes WHERE feedId = :feedId AND playState = 'PLAYED' AND localFilePath IS NULL")
    suspend fun deletePlayedNonDownloadedEpisodes(feedId: Long)

    @Query("""
        DELETE FROM episodes WHERE feedId = :feedId
        AND downloadState = 'DOWNLOADED'
        AND isProtected = 0
        AND id NOT IN (
            SELECT id FROM episodes WHERE feedId = :feedId
            ORDER BY pubDate DESC LIMIT :keepCount
        )
    """)
    suspend fun trimOldDownloads(feedId: Long, keepCount: Int)

    // Partial download resume support
    @Query("UPDATE episodes SET downloadBytesDownloaded = :bytes WHERE id = :episodeId")
    suspend fun updateDownloadProgress(episodeId: Long, bytes: Long)

    // Mark as archived (episode no longer appears in feed RSS)
    @Query("UPDATE episodes SET isArchived = 1 WHERE feedId = :feedId AND id NOT IN (:activeGuids)")
    suspend fun archiveRemovedEpisodes(feedId: Long, activeGuids: List<Long>)

    // Played fraction update (for "Played Portion" sort)
    @Query("UPDATE episodes SET playedFraction = :fraction WHERE id = :episodeId")
    suspend fun updatePlayedFraction(episodeId: Long, fraction: Float)

    // Batch operations support
    @Query("UPDATE episodes SET playState = :state WHERE id IN (:episodeIds)")
    suspend fun batchUpdatePlayState(episodeIds: List<Long>, state: PlayState)

    // Batch remove from queue — operates on queue_snapshot_items, not episode fields
    // Use QueueSnapshotDao.removeItemsFromActiveSnapshot(episodeIds) instead.
    // (batchRemoveFromQueue with isInQueue/queuePosition has been removed — QA finding #1.)

    // Duplicate detection: find episodes with same feedId + title + approximately same duration
    @Query("""
        SELECT * FROM episodes
        WHERE feedId = :feedId AND title = :title
        AND ABS(duration - :durationMs) < 5000
        LIMIT 2
    """)
    suspend fun findPotentialDuplicates(feedId: Long, title: String, durationMs: Long): List<EpisodeEntity>
}
```

#### CategoryDao

```kotlin
@Dao
interface CategoryDao {
    @Query("SELECT * FROM categories ORDER BY sortOrder ASC, name ASC")
    fun getAllCategories(): Flow<List<CategoryEntity>>

    @Upsert
    suspend fun upsertCategory(category: CategoryEntity): Long

    @Delete
    suspend fun deleteCategory(category: CategoryEntity)

    @Query("SELECT * FROM categories WHERE id = :id")
    suspend fun getCategoryById(id: Long): CategoryEntity?
}
```

#### SmartPlaylistDao

```kotlin
@Dao
interface SmartPlaylistDao {
    @Query("SELECT * FROM smart_playlists ORDER BY sortOrder ASC, name ASC")
    fun getAllPlaylists(): Flow<List<SmartPlaylistEntity>>

    @Query("SELECT * FROM smart_playlists WHERE id = :id")
    suspend fun getPlaylistById(id: Long): SmartPlaylistEntity?

    @Upsert
    suspend fun upsertPlaylist(playlist: SmartPlaylistEntity): Long

    @Delete
    suspend fun deletePlaylist(playlist: SmartPlaylistEntity)
}
```

#### QueueSnapshotDao

```kotlin
/**
 * All queue mutations go through this DAO. EpisodeDao must never write queue state.
 * The queue is ALWAYS a snapshot — never a live query over episodes.
 */
@Dao
interface QueueSnapshotDao {
    // Get the active snapshot + its ordered items
    @Query("SELECT * FROM queue_snapshots WHERE isActive = 1 LIMIT 1")
    fun getActiveSnapshot(): Flow<QueueSnapshotEntity?>

    @Query("""
        SELECT * FROM queue_snapshot_items
        WHERE snapshotId = :snapshotId
        ORDER BY position ASC
    """)
    fun getSnapshotItems(snapshotId: Long): Flow<List<QueueSnapshotItemEntity>>

    // Atomic snapshot replacement — deactivate all old snapshots, insert new one + items
    @Transaction
    suspend fun replaceActiveSnapshot(
        snapshot: QueueSnapshotEntity,
        items: List<QueueSnapshotItemEntity>
    ) {
        deactivateAllSnapshots()
        val newId = insertSnapshot(snapshot)
        insertItems(items.map { it.copy(snapshotId = newId) })
    }

    @Query("UPDATE queue_snapshots SET isActive = 0")
    suspend fun deactivateAllSnapshots()

    @Insert
    suspend fun insertSnapshot(snapshot: QueueSnapshotEntity): Long

    @Insert
    suspend fun insertItems(items: List<QueueSnapshotItemEntity>)

    // Update current position pointer (called every 5s during playback)
    @Query("""
        UPDATE queue_snapshots
        SET currentItemIndex = :index, currentItemPositionMs = :positionMs
        WHERE isActive = 1
    """)
    suspend fun updatePlaybackPosition(index: Int, positionMs: Long)

    // Remove specific episodes from the active snapshot (e.g., user swipes to remove)
    @Query("""
        DELETE FROM queue_snapshot_items
        WHERE snapshotId = (SELECT id FROM queue_snapshots WHERE isActive = 1 LIMIT 1)
        AND episodeId IN (:episodeIds)
    """)
    suspend fun removeItemsFromActiveSnapshot(episodeIds: List<Long>)

    // Reorder: update position values for all items in active snapshot
    @Query("UPDATE queue_snapshot_items SET position = :position WHERE id = :itemId")
    suspend fun updateItemPosition(itemId: Long, position: Int)
}
```

### 5.3 BeyondPodDatabase

```kotlin
@Database(
    entities = [
        FeedEntity::class,
        FeedCategoryCrossRef::class,
        EpisodeEntity::class,
        CategoryEntity::class,
        SmartPlaylistEntity::class,
        ManualPlaylistEpisodeCrossRef::class,
        QueueSnapshotEntity::class,
        QueueSnapshotItemEntity::class,
        DownloadQueueEntity::class,
        SyncStateEntity::class,
        EpisodePlayHistoryEntity::class,      // Play event audit log (episode_history)
        ChangeHistoryEntity::class,           // Subscription change log (gPodder sync diffs)
        ScheduledTaskEntity::class            // User-defined scheduled update tasks
    ],
    version = 1,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class BeyondPodDatabase : RoomDatabase() {
    abstract fun feedDao(): FeedDao
    abstract fun episodeDao(): EpisodeDao
    abstract fun categoryDao(): CategoryDao
    abstract fun smartPlaylistDao(): SmartPlaylistDao
    abstract fun queueSnapshotDao(): QueueSnapshotDao  // All queue mutations go here

    companion object {
        const val DATABASE_NAME = "beyondpod.db"
    }
}
```

---

## 6. Repository Layer

Each repository is the single source of truth for its domain. Repositories talk to both local (Room) and remote (network) data sources and coordinate between them.

### FeedRepository

```
Interface responsibilities:
- subscribeToFeed(url: String): Result<Feed>         → fetch, parse, store feed
- getAllFeeds(): Flow<List<Feed>>
- getFeed(id: Long): Flow<Feed?>
- updateFeedProperties(feed: Feed): Result<Unit>
- deleteFeed(id: Long, deleteDownloads: Boolean)
- refreshFeed(id: Long): Result<Unit>               → re-fetch RSS, upsert episodes
- refreshAllFeeds(): Result<Unit>
- moveFeedToCategory(feedId: Long, categoryId: Long?)
- importFromOpml(opmlContent: String): Result<Int>  → returns count imported
- exportToOpml(): Result<String>
```

### EpisodeRepository

```
- getEpisodesForFeed(feedId: Long): Flow<List<Episode>>
- getEpisode(id: Long): Flow<Episode?>
- markPlayed(episodeId: Long)
- markUnplayed(episodeId: Long)
- savePlayPosition(episodeId: Long, positionMs: Long)
- toggleStar(episodeId: Long)
- toggleProtected(episodeId: Long)            // Set/unset isProtected flag
- addToQueue(episodeId: Long)
- removeFromQueue(episodeId: Long)
- reorderQueue(episodeIds: List<Long>)
- getQueue(): Flow<List<Episode>>
- batchMarkPlayed(episodeIds: List<Long>)
- batchMarkUnplayed(episodeIds: List<Long>)
- batchAddToQueue(episodeIds: List<Long>)
- batchDeleteFiles(episodeIds: List<Long>)
- buildQueueSnapshot(playlist: SmartPlaylist): Result<QueueSnapshot>  // Creates frozen snapshot
- getActiveQueueSnapshot(): Flow<QueueSnapshot?>
- getSmartPlaylistEpisodes(playlist: SmartPlaylist): Flow<List<Episode>>
- deleteEpisodeFile(episodeId: Long)
- searchEpisodes(query: String): Flow<List<Episode>>  // Title + description search
```

### DownloadRepository

```
- enqueueDownload(episodeId: Long)
- cancelDownload(episodeId: Long)
- getDownloadQueue(): Flow<List<Episode>>
- getDownloadedEpisodes(): Flow<List<Episode>>
- deleteDownload(episodeId: Long)
- batchEnqueueDownloads(episodeIds: List<Long>)
- batchDeleteDownloads(episodeIds: List<Long>)     // Respects isProtected
- moveDownloadsToFolder(targetPath: String): Result<Int>
- autoDownloadNewEpisodes(feedId: Long)            // Runs cleanup FIRST, then downloads
- getStorageStats(): Flow<StorageStats>            // Per-feed disk usage for analytics
- getTotalDownloadedBytes(): Flow<Long>
```

### SyncRepository

```
- getSyncState(): Flow<SyncState>
- configureSyncProvider(provider: SyncProvider, username: String, password: String, serverUrl: String?): Result<Unit>
- syncNow(): Result<SyncResult>
- uploadSubscriptions(): Result<Unit>
- downloadSubscriptions(): Result<List<String>>    → returns feed URLs
- uploadEpisodeActions(): Result<Unit>
- downloadEpisodeActions(): Result<Unit>
```

---

## 7. Feature Specifications

### 7.1 Feed (Podcast) Management

#### Subscribing

1. User can subscribe by:
   - Entering a URL directly (RSS, Atom, `.xml`, `.rss`)
   - Searching the built-in podcast directory (iTunes Search API: `https://itunes.apple.com/search?media=podcast&term=...`)
   - Handling `itpc://`, `pcast://`, `feed://`, `rss://`, `beyondpod://` URI schemes
   - OPML file import
   - "Subscribe on Android" (`subscribeonandroid.com`) links

2. On subscribing:
   - Validate URL. If not a direct feed, attempt feed discovery (check `<link rel="alternate">` tags, common paths like `/feed`, `/rss`)
   - Fetch and parse feed
   - Store `FeedEntity` + all episodes as `EpisodeEntity` (all initial episodes come in as `PlayState.NEW`)
   - Show feed preview dialog before confirming subscription

3. Feed discovery search UI (AddFeedView):
   - Search field at top
   - Results grid showing podcast artwork + title + author
   - Tapping a result shows FeedPreview dialog
   - FeedPreview shows: artwork, title, description, recent episode list, Subscribe button

#### Feed List (Navigator / Left Panel)

The main navigation structure is a **two-panel layout** on tablets and a **drawer + main panel** on phones.

**Navigator (drawer on phones, left panel on tablets) contains:**
- "My Episodes" shortcut (top, always visible)
- "All Published" shortcut
- Separator
- List of Categories, each expandable to show feeds
- "Add Podcast" button at bottom
- Category management (reorder, add, edit via long-press)

**Feed display in navigator:**
- Feed thumbnail (50dp circle)
- Feed title
- Badge showing count of new episodes (red pill)
- Long-press context menu: Edit, Move to Category, Unsubscribe, Mark All Played, Refresh

#### Feed Properties (FeedPropertiesView)

Three tabs: **General**, **Advanced**, **Schedule**

**General tab:**
- Feed title (editable)
- Feed URL (read-only + copy button)
- Primary category (dropdown)
- Secondary category (dropdown, optional — original BeyondPod supported 2 categories per feed)
- Custom artwork (tap to pick image or use `.feedimage` file in folder for virtual feeds)
- Description (read-only)
- Website link
- Feed priority: Normal / High (affects SmartPlay tiebreaking)

**Authentication section (General tab, collapsible):**
- HTTP/HTTPS Username
- HTTP/HTTPS Password
- (For premium feeds requiring Basic Auth)

**Advanced tab:**
- Override global update settings (toggle)
  - Update interval (dropdown: Manual, 15 min, 30 min, 1h, 2h, 4h, 6h, 12h, 24h)
- Download strategy (dropdown, replaces simple auto-download toggle):
  - Global Default
  - Download Newest — always get the N most recent
  - Download In Order — start from oldest, work forward
  - Stream Newest — create streamable refs, no local files
  - Manual — user controls all downloads
- Episodes to download: 1–20 spinner (for Download Newest / In Order)
- Episodes to keep: 1–50 / Keep All (for Download Newest only)
- Download only on WiFi (toggle)
- Allow cleanup for manual downloads (toggle, Advanced) — apply cleanup rules to manually-downloaded episodes
- Override playback settings
  - Playback speed (0.5x–4.0x in 0.1 steps; original range was 0.3x–3.0x, extended here)
  - Skip intro seconds (0–120)
  - Skip outro seconds (0–120)
  - Volume boost: 1 (no boost) to 10 (max boost) — requires speed adjustment enabled
  - Episode sort order override: Global / Newest / Oldest / Name / Duration / Played Portion / File Name
- Card display type (Type 1 / Type 2 / Type 3)
- Mark new episodes as: New / In Progress / Played
- Use Google proxy for feed (toggle) — helps with Cloudflare-blocked feeds

**Schedule tab:**
- Per-feed update schedule (days of week × time slots grid)
- Independent from global schedule

#### Virtual Feeds

A Virtual Feed is a folder on the device's storage that BeyondPod monitors and treats as a podcast feed. Any audio/video files dropped into the folder appear as episodes.

- Created via "Add Folder as Virtual Feed"
- User picks a folder path
- BeyondPod scans that folder periodically (on update) and creates `EpisodeEntity` records from files
- Episodes from virtual feeds can be added to playlists, marked played, etc.
- No RSS involved; `FeedEntity.isVirtualFeed = true`, `virtualFeedFolderPath` set

**Virtual Feed Scanner — file integrity (QA finding #3b):**

When the background `FeedUpdateWorker` scans a virtual feed folder, it must perform a **bidirectional reconciliation**, not just a one-way scan for new files:

```
VirtualFeedScanner.reconcile(feedId: Long, folderPath: String):

  Step 1 — Discover new files:
    List all audio/video files in folderPath (filter by extension: .mp3, .m4a, .ogg, .opus, .aac, .flac, .wav, .mp4, .mkv, .webm)
    For each file not already in episodes (matched by localFilePath or filename):
      → Create new EpisodeEntity with downloadState = DOWNLOADED, localFilePath = filePath
      → Set title = filename (strip extension), pubDate = file lastModified timestamp
      → Set isVirtualFeed = true on episode

  Step 2 — Verify existing virtual feed episodes:
    For each EpisodeEntity WHERE feedId = :feedId AND downloadState = DOWNLOADED:
      Check if localFilePath exists on disk (File(localFilePath).exists())
      If NOT exists:
        → Set downloadState = DELETED   ← CRITICAL: file was externally removed
        → Set localFilePath = null
        → Do NOT change playState or isProtected (preserve listening history)
        → Log: "Virtual feed file removed externally: {filename}"
    This prevents permanently stale "Downloaded" badges on files that no longer exist.
```

Note: `DownloadStateEnum.DELETED` means "was downloaded, file is gone — record kept for history". This is distinct from `NOT_DOWNLOADED` (never downloaded) and `FAILED` (download attempt failed). The DELETED state must be visually distinguished in the UI (greyed-out download badge, no play button).

### 7.2 Episode Management

#### Episode Cards

Three card display styles per feed (configurable in Feed Properties):

**Type 1 (Standard):** Two lines — title + pub date + feed name. Download/play button on right. Three sizes: Small, Medium, Large.

**Type 2 (Compact):** Single dense row. Title + status icons. Good for high-density lists.

**Type 3 (Large/Article):** Shows episode artwork, title, description excerpt, and action buttons. Best for content-heavy podcast discovery.

#### Episode Context Menu (long-press or swipe)

- Play
- Add to My Episodes / Remove from My Episodes
- Download / Cancel Download / Delete Download
- Mark as Played / Mark as Unplayed
- Star / Unstar
- **Protect / Unprotect** — protects episode from ALL automatic deletion (toggle; shows padlock icon when protected)
- Share (share URL)
- Open Show Notes
- Go to Feed

#### Episode States (Complete)

| State | Description |
|---|---|
| **New** | Blue dot indicator; not yet played |
| **In Progress** | Partially played; shows progress arc on icon |
| **Played** | Fully played (≥90% or manually marked) |
| **Skipped** | User explicitly skipped without playing |
| **Archived** | No longer in feed RSS but local copy exists; greyed label, cannot be starred |
| **Protected** | Additional flag (any state); gold padlock icon; immune to all auto-deletion |

#### Batch Operations (Multi-Select)

Long-press any episode card to enter **multi-select mode**. A selection count badge appears in the action bar.

Batch actions (applied to all selected):
- Download selected
- Delete files for selected
- Mark all as Played
- Mark all as Unplayed
- Add all to My Episodes queue
- Remove all from My Episodes queue
- Protect / Unprotect all

"Select All" and "Select None" buttons appear in action bar.

#### Pinch-to-Zoom Gesture (Episode List)

- **Pinch out (expand)**: switch filter to "Show All" episodes
- **Pinch in (contract)**: switch filter to "New/Unplayed Only"

This matches original BeyondPod behaviour and provides a quick toggle without opening the filter menu.

#### Mark as Read on Scroll (Advanced Setting, off by default)

When enabled (Settings > Advanced), episodes are automatically marked as "read/played" when scrolled past in the list if they have been visible for more than a configurable threshold (default: 2 seconds visible at normal scroll speed). This applies to text-heavy / news feeds where a user skims without listening.

#### Episode List Filters (per feed)

- All Episodes
- New Only
- Played
- Downloaded
- Starred
- In Progress

#### Episode List Sort (per feed)

- Newest First (default)
- Oldest First
- Duration (short/long)
- Title A–Z / Z–A

#### Swipe Actions (configurable)

Left swipe and right swipe actions are configurable per-user in Settings. Default:
- Left swipe: Mark Played/Unplayed
- Right swipe: Add to My Episodes

### 7.3 Categories & Virtual Folders

#### Categories

Categories are top-level groupings for feeds (equivalent to folders). Every feed is in at most one category. Uncategorised feeds appear under an implicit "Uncategorised" group.

**Category Properties (CategoryPropertiesActivity):**
- Name
- Color (color picker — 16 preset colors + custom)
- Update schedule (days × times grid, independent of global)
- Per-category defaults for auto-download, keep count, WiFi-only

**Category operations:**
- Create, rename, reorder (drag in navigator), delete
- "Mark All Played" in category context menu
- "Download All" for category

**Category Deletion — edge case behaviour (QA finding #3a):**

When a category is deleted, the feeds it contained are **never deleted**. They are reassigned to the system "Uncategorized" group. Deletion must not cascade to feeds — only the `CategoryEntity` row and the `FeedCategoryCrossRef` rows for that category are removed.

```
On category delete:
  1. DELETE FROM feed_category_cross_ref WHERE categoryId = :deletedCategoryId
  2. Any FeedEntity whose primaryCategoryId = :deletedCategoryId gets primaryCategoryId = null
     (and secondaryCategoryId = null if it also matched)
  3. DELETE FROM categories WHERE id = :deletedCategoryId
  4. Feeds with no remaining category membership appear under "Uncategorized" in the navigator
```

The confirmation dialog when deleting a category must state: "This will remove the category and move its N feeds to Uncategorized. Downloaded episodes and subscriptions will not be affected." Never use the word "delete" in reference to feeds in this dialog — it must be clear feeds are preserved.

#### Navigator Hierarchy

```
📁 Category A          ← CategoryEntity (expandable)
  🎙️ Feed 1  [3]      ← FeedEntity (badge = new episode count)
  🎙️ Feed 2
📁 Category B
  🎙️ Feed 3  [1]
🎙️ Uncategorised Feed  ← feed with no category
```

#### Category View

Tapping a category (not a feed) opens an episode list showing new episodes across all feeds in that category. Same filter/sort options as per-feed list.

### 7.4 Smart Playlists

Smart Playlists are **rule-based, dynamically populated** episode lists. They are one of BeyondPod's signature features.

#### Rule Engine

Rules are evaluated against `EpisodeEntity` records in Room. A SmartPlaylist holds an array of `SmartPlaylistRule` objects (stored as JSON).

**Available rule fields and operators:**

| Field | Operators | Value type |
|---|---|---|
| `PLAY_STATE` | IS, IS_NOT | NEW, IN_PROGRESS, PLAYED, SKIPPED |
| `IS_STARRED` | IS | true/false |
| `IS_DOWNLOADED` | IS | true/false |
| `FEED_ID` | IS, IS_NOT | feed ID |
| `CATEGORY_ID` | IS, IS_NOT | category ID |
| `PUB_DATE` | IS_BEFORE, IS_AFTER | ISO date string |
| `DURATION` | GREATER_THAN, LESS_THAN | seconds |
| `IS_IN_MY_EPISODES` | IS | true/false |
| `TITLE_CONTAINS` | CONTAINS, DOES_NOT_CONTAIN | string |
| `FILE_TYPE` | IS | audio, video |

Rules combine with AND/OR logical connectors. Multiple rules form a chain evaluated left-to-right with short-circuit logic.

**Example: "New Tech Episodes" playlist:**
```
PLAY_STATE IS NEW
AND CATEGORY_ID IS 2  (Tech category)
AND IS_DOWNLOADED IS true
```

#### Smart Playlist Editor (SmartPlaylistEditorView)

- Playlist name (editable text field)
- Rule list (each rule is an `SmartPlaylistEntryItem` row: field picker + operator picker + value field + delete button)
- "Add Rule" button
- Max items (number field; 0 = unlimited)
- Sort order (dropdown)
- Auto-play toggle
- Continue on complete toggle
- Preview button — shows a count of matching episodes

#### Built-in Default Playlists (pre-seeded on first run)

1. **My Episodes** — `IS_IN_MY_EPISODES IS true`, sorted by date added
2. **New Episodes** — `PLAY_STATE IS NEW`, sorted by pub date desc
3. **In Progress** — `PLAY_STATE IS IN_PROGRESS`, sorted by last played desc
4. **Downloaded** — `IS_DOWNLOADED IS true`, sorted by download date desc
5. **Starred** — `IS_STARRED IS true`, sorted by pub date desc

These are `SmartPlaylistEntity` records with `isDefault = true`. Users can edit and delete all of them except "My Episodes".

#### Smart Playlist Display

Smart playlists appear in:
- The main Navigation Drawer (below categories section, under "Playlists" header)
- As shortcuts on the home screen (user can add)

Tapping a playlist opens `PlaylistView` which shows the live-evaluated episode list with all standard episode actions.

#### Queue Immutability (Critical — §0.1)

When a SmartPlaylist is triggered for playback, the system calls `EpisodeRepository.buildQueueSnapshot()` which:

1. Evaluates the rules against the current database state
2. Produces an ordered list of episode IDs
3. Persists this as a `QueueSnapshotEntity` + `QueueSnapshotItemEntity` rows
4. Sets the snapshot as active
5. Deletes any previous snapshot

**From this point forward, the active queue is the snapshot — not a live query.** Feed updates, new downloads, rule changes, or new episodes from network sync DO NOT alter the snapshot. The user sees a stable, predictable queue.

To update the queue with fresh data the user must explicitly tap "Regenerate Queue" in the playlist header. This destroys and rebuilds the snapshot.

**Manual edits to the queue (reorder, delete, insert) modify the snapshot rows directly.** They do not affect the SmartPlaylist rules. The system never "fights back" to restore the rule-generated order.

#### SmartPlay Rule Edge Cases

If rule evaluation returns zero episodes:
- `OnEmptyAction.DO_NOTHING`: queue is built as empty. Show "No episodes match" empty state with option to edit rules.
- `OnEmptyAction.FALLBACK_ALL_UNPLAYED`: fall back to all unplayed episodes across all feeds, sorted by pub date desc.

Individual rule blocks (Sequential Block mode) that return zero results are silently skipped — the playlist is built from the remaining blocks that do match.

#### Playlist Auto-Play

When `autoPlay = true` on a SmartPlaylist, BeyondPod will auto-start playback of the first episode when the playlist is opened and not currently playing. When an episode finishes, if `continueOnComplete = true`, it advances to the next item in the snapshot.

### 7.5 My Episodes Queue

> **First-class system entity — not just a SmartPlaylist** (QA finding #5)

**My Episodes** is implemented via `SmartPlaylistEntity(isDefault = true)` but its *behaviour* is fundamentally different from regular SmartPlaylists in five critical ways. Claude Code must honour all five distinctions — treating My Episodes as a plain SmartPlaylist will break the core BeyondPod user experience.

#### What Makes My Episodes Different

**1. Manual curation, not rule-driven**
My Episodes accepts manual episode additions ("Add to My Episodes" action). It is a user-curated, ordered list. It can *also* be populated by rules (if the user configures it that way), but it is never *only* rule-driven. A regular SmartPlaylist is purely rule-derived — its contents cannot be manually edited.

**2. It is the active playback queue**
My Episodes IS the queue. When you play My Episodes, the `QueueSnapshotEntity` is generated from its current contents *in order*. Its episode order defines the playback order. No other SmartPlaylist can claim this role — only the `isDefault = true` playlist drives the queue.

**3. It is indestructible**
My Episodes cannot be deleted by the user. The "Delete playlist" action must be disabled/hidden for `isDefault = true` entries. Attempting to delete it programmatically must throw an `IllegalStateException`.

**4. It persists across queue regeneration**
Regular SmartPlaylist queue snapshots are ephemeral — regenerating the queue replaces them entirely. My Episodes has a *persistent* state: it remembers which episodes the user added and their order, even across queue rebuilds. Think of it as the "source of truth" manual list; the `QueueSnapshotEntity` is a derived execution of it.

**5. Auto-population from new downloads**
When a feed's `downloadStrategy = DOWNLOAD_NEWEST` and a new episode is downloaded, it is optionally auto-added to My Episodes (controlled by a per-feed toggle: "Automatically add new downloads to My Episodes"). Regular SmartPlaylists do not receive episodes automatically.

#### My Episodes Data Model Clarification

The `isInMyEpisodes: Boolean` and `addedToMyEpisodes: Long?` fields on `EpisodeEntity` are the source of truth for My Episodes membership. The `ManualPlaylistEpisodeCrossRef` table tracks order within My Episodes.

```
My Episodes membership = EpisodeEntity.isInMyEpisodes == true
My Episodes order      = ManualPlaylistEpisodeCrossRef.position WHERE playlistId = MY_EPISODES_ID
Queue execution        = QueueSnapshotEntity generated from My Episodes contents in order
```

#### Behavioural Distinction: My Episodes vs SmartPlaylist vs Queue

| Dimension | My Episodes | Regular SmartPlaylist | Active Queue (Snapshot) |
|---|---|---|---|
| Populated by | Manual + optional auto-add | Rules only | Generated from either |
| User-editable order | Yes (drag to reorder) | No (order = sort rule) | Yes (drag = reorder snapshot) |
| Persists after rebuild | Yes (it IS the source) | N/A (re-evaluated) | No (replaced on rebuild) |
| Deletable | No | Yes | Implicitly on regeneration |
| Can be deleted by rule changes | No | Contents change on re-eval | No (frozen snapshot) |
| Drives playback | Yes (via snapshot) | Yes (via snapshot) | Yes (directly) |

#### Queue Operations (My Episodes)

- Drag to reorder (updates `ManualPlaylistEpisodeCrossRef.position` and `QueueSnapshotItemEntity.position` in active snapshot atomically)
- Swipe to remove from queue — removes from `ManualPlaylistEpisodeCrossRef` AND sets `EpisodeEntity.isInMyEpisodes = false`. Does NOT delete the episode.
- "Clear Queue" — clears all `ManualPlaylistEpisodeCrossRef` rows for My Episodes + deactivates active snapshot. Sets `isInMyEpisodes = false` for all affected episodes.
- "Add all new from feed X" — batch-inserts eligible episodes into `ManualPlaylistEpisodeCrossRef` + sets `isInMyEpisodes = true`

**Queue persistence:**
Queue order is stored in `QueueSnapshotItemEntity.position` (integer) within the active `QueueSnapshotEntity`. Reordering calls `QueueSnapshotDao.updateItemPosition()` for each affected item. EpisodeEntity carries no queue-position state. (QA finding #1.)

**Playlist header actions (PlaylistHeaderActionBar):**
- Play All (from top)
- Shuffle (generates snapshot in shuffled order — does NOT reorder My Episodes itself)
- Clear Queue
- Add More (opens feed/category picker)

### 7.6 Playback Engine

#### Media3 / ExoPlayer Integration

Playback is handled by `PlaybackService` which extends `MediaSessionService`. This enables:
- Background playback
- Lock screen controls
- Notification controls
- Android Auto integration
- Bluetooth AVRCP

`PlaybackService` maintains a `MediaSession` and `ExoPlayer` instance. The `ExoPlayer` queue mirrors the current Smart Playlist or manual queue.

#### Playback Features

**Variable Speed Playback:**
- Range: 0.5x, 0.6x, 0.7x, 0.8x, 0.9x, 1.0x, 1.1x, 1.2x, 1.3x, 1.4x, 1.5x, 1.75x, 2.0x, 2.5x, 3.0x, 4.0x
  *(Note: original BeyondPod range was 0.3x–3.0x. This spec extends it for modern users.)*
- Per-feed speed override supported
- Per-playlist speed override supported (SmartPlaylistEntity.playbackSpeedOverride)
- Speed persists across restarts (saved in AppPreferences)
- **Three configurable preset speed buttons** in the speed popup (user-configurable, default: 1.0x / 1.5x / 2.0x)
- Shown as "Speed" charm/button on full player

**Skip Silence:**
- Uses ExoPlayer `SilenceSkippingAudioProcessor`
- Toggle in player controls (and in Settings > Playback)
- Saved as user preference; per-playlist override available
- **Configurable parameters (in Settings > Playback > Skip Silence Options):**
  - Minimum silence duration: 500ms / 1000ms / 1500ms / 2000ms (default 1000ms)
  - Aggressiveness: Low / Medium / High (maps to silence threshold amplitude: -60dB / -50dB / -40dB)

**Sleep Timer:**
- End of current episode
- 5, 10, 15, 30, 45, 60, 90, 120 minutes
- Custom duration (number picker)
- Timer shown as countdown in player
- "Shake to reset" option (accelerometer-based, opt-in)

**Skip/Rewind:**
- Configurable forward/rewind intervals: 10, 15, 20, 30, 45, 60, 90, 120 seconds
- Double-tap skip on player
- Headset button configuration (single press / double press / long press → configurable action)

**Headset Button Actions (configurable per press type):**
- Play/Pause
- Next Episode
- Previous Episode
- Skip Forward
- Rewind
- Speed Up
- Speed Down

**Volume Boost (LoudnessEnhancer):**

> **Implementation directive (QA finding #2):** ExoPlayer/Media3's `player.volume` property maxes at 1.0f (100%). Setting it above 1.0 causes clipping distortion and is not supported. Software gain beyond 0dB **must** be implemented using Android's `LoudnessEnhancer` audio effect.

Implementation:
```kotlin
// In PlaybackService, after ExoPlayer is prepared and audio session is available:
val loudnessEnhancer = LoudnessEnhancer(exoPlayer.audioSessionId)

fun applyVolumeBoost(boostLevel: Int) {
    // boostLevel: 0 = global default, 1 = no boost (0dB), 2–10 = increasing gain
    // Map to millibels: level 1 → 0mB (0dB), level 10 → 1000mB (10dB)
    // Linear mapping: gainMillibels = (boostLevel - 1) * 111 (≈1dB per step from level 2)
    val gainMillibels = if (boostLevel <= 1) 0 else ((boostLevel - 1) * 111).coerceAtMost(1000)
    loudnessEnhancer.setTargetGain(gainMillibels)
    loudnessEnhancer.enabled = gainMillibels > 0
}
```

Scale definition:
| UI level | Gain (dB) | Gain (mB) |
|---|---|---|
| 1 (no boost) | 0 dB | 0 |
| 2 | ~1.1 dB | 111 |
| 5 | ~4.4 dB | 444 |
| 10 (max) | ~10 dB | 1000 |

Notes:
- `LoudnessEnhancer` requires API 19+ (satisfied by our API 26 min)
- Must be re-created when `exoPlayer.audioSessionId` changes (e.g., after `prepare()`)
- Dispose with `loudnessEnhancer.release()` in `onDestroy()`
- The `LoudnessEnhancer` applies a perceptual loudness model (ITU-R BS.1770), which is better than naive linear gain — lower risk of clipping than `player.volume = 2.0f`
- At boost level 10 (+10dB) there is still a clipping risk with very loud source material; document this in the UI tooltip ("High boost levels may distort with loud audio")
- Re-apply boost after every `player.prepare()` call since audio session ID may change

**Volume Navigation:**
- Volume up/down hardware buttons can optionally control playback position (skip forward/back) instead of volume
- Toggle in Settings

**Audio Focus:**
- Follows Android audio focus guidelines
- Duck on notification sounds (reduce volume, don't pause) — configurable; "Pause on Notification" option available
- Pause on incoming call
- Resume after call ends (configurable)
- Pause when headphones disconnected (configurable)
- **Resume on headphone reconnect** (configurable): when wired headphones are reconnected, auto-resume after a configurable delay (0 = disabled, or 1–30 seconds)
- **Resume on Bluetooth reconnect** (configurable): when a Bluetooth device reconnects, auto-resume after configurable delay (default 5 seconds; accounts for Bluetooth handshake time)

**Playback Failure Handling:**

The player must never crash silently. For every failure type there is a defined recovery path:

| Failure | Recovery |
|---|---|
| Local file missing/not found | Mark episode `downloadState = FAILED`, log error on episode, skip to next in queue, show snackbar "File not found, skipped to next" |
| Local file corrupt (decode error) | Same as file missing |
| Stream URL unreachable (no network) | Pause playback, show "No connection" UI; retry when network available (NetworkCallback) |
| Stream URL returns 4xx/5xx | Log error on episode, skip to next, show error |
| Stream fails mid-playback | Pause, show retry prompt; after 3 auto-retries, skip to next |
| Download fails | Increment `DownloadQueueEntity.retryCount`; retry up to 3 times with exponential backoff; after 3 failures, mark `FAILED` and notify user |
| Partial file detected on download start | Resume from `downloadBytesDownloaded` via `Range` header if server supports it |

**Metadata Publishing (Bluetooth / AVRCP / Scrobbling):**
- Publish current episode title + feed name + artwork to Bluetooth AVRCP 1.3 (Android 4.3+ native via MediaSession)
- "Publish Current Episode" setting (Advanced): legacy method for older devices/car stereos
- Last.FM scrobbling: when enabled, publish episode play events to Last.FM via their API (configurable in Settings > Advanced)

**Continuous Play:**
- When episode ends, auto-advance to next in current playlist/queue
- Configurable "end of playlist" action: Stop / Loop / Play Next Playlist

**Chapter Support:**
- Parses `<podcast:chapters>` namespace URL
- Fetches chapters JSON
- Shows chapter list in full player
- Skip to next/previous chapter

**Video Playback:**
- Full-screen video player (`MovieView`)
- "Play audio only" option for video podcasts (strips video, plays audio via ExoPlayer)
- Remembers preference per feed

#### Player UI

**Mini Player (persistent bottom bar):**
- Appears when an episode is loaded (even paused)
- Shows: episode artwork (32dp), title (1 line, truncated), feed name, play/pause button, skip forward button
- Tap → expands to full player
- Swipe up → expand to full player
- Swipe down → dismiss (stops playback, confirms via snackbar with undo)

**Full Screen Player (player_full_screen):**
- Large album art (square, with parallax on scroll)
- Episode title (2 lines)
- Feed name (1 line, tappable → goes to feed)
- Seekbar with current position + total duration
- Transport controls row: Rewind | Previous | Play/Pause | Next | Forward
- Speed button (shows current speed, tap to cycle through or long-press for picker)
- Sleep timer button (charm)
- Star button
- Overflow menu: Add to playlist, Share, Mark played, Go to feed, Show notes
- Chapters button (appears if chapters available)

**Transport Controls Car Mode (player_transport_controls_car_mode):**
- Simplified, larger buttons: Rewind 30 | Play/Pause | Skip Forward 30 | Next Episode
- No seekbar (safety)
- Adapts automatically when Android Driving Mode is active

### 7.7 Download Manager

#### Download Architecture

Downloads run as `DownloadWorker` (HiltWorker, `CoroutineWorker`). WorkManager manages queueing, retries, and constraints.

Each download job:
1. Sets `downloadState = QUEUED` on the episode
2. WorkManager picks it up when constraints are met (WiFi if required, not low battery if configured)
3. Downloads via OkHttp with progress tracking
4. Writes to `/Android/data/mobi.beyondpod.revival/files/podcasts/{feedTitle}/{episodeTitle}.mp3`
5. Updates `downloadState = DOWNLOADED`, `localFilePath`, `downloadedAt`
6. Updates notification progress bar

#### Download Constraints

Per feed/category/global setting:
- **WiFi only** — `NetworkType.UNMETERED` constraint
- **Not while charging** — optional
- **Not on low battery** — optional (< 20%)

#### Download Queue (UpdateAndDownloadQueueActivityDialog)

- List of queued + active downloads
- Shows: episode title, feed name, progress bar (%), file size, estimated time
- Per-item: cancel, pause (future), move up/down in queue
- "Cancel All" button
- "Pause All" / "Resume All" (future)

#### Auto-Download Rules

Evaluated each time a feed is updated. **Critical ordering: cleanup runs BEFORE new downloads.**

```
For each feed after update:
  1. Determine effective download strategy (feed > category > global)

  DOWNLOAD_NEWEST strategy:
    a. [CLEANUP FIRST] Delete oldest played downloaded episodes beyond maxEpisodesToKeep
       (NEVER delete isProtected episodes, NEVER delete currently-playing episode)
    b. Fetch up to downloadCount newest episodes that are NOT_DOWNLOADED
    c. Enqueue them as WorkManager DownloadWorker jobs

  DOWNLOAD_IN_ORDER strategy:
    a. NO automatic cleanup (user manages manually, or Delete After Played setting)
    b. Find oldest N episodes (by pubDate ASC) that are NOT_DOWNLOADED
    c. Enqueue up to downloadCount

  STREAM_NEWEST strategy:
    a. Mark latest downloadCount episodes as downloadState = QUEUED (streamable)
    b. No actual file download; just ensure URL is known

  MANUAL strategy:
    a. No automatic action
    b. If allowCleanupForManual = true, still run cleanup step
```

**Partial Download Resume:**
When a download is interrupted (network drop, app killed, user cancelled):
1. `downloadBytesDownloaded` is persisted to the database before the download worker exits
2. On retry, `DownloadWorker` sends `Range: bytes=N-` header in the OkHttp request
3. If server returns 206 Partial Content, resume from byte N
4. If server returns 200 (doesn't support range), restart from beginning

**Duplicate Detection:**
When inserting an episode from RSS parse:
1. Check by `guid` + `feedId` first (authoritative)
2. If no GUID match, check by `title` + `feedId` + duration within ±5 seconds
3. If duplicate found, update metadata only — do not create new record
4. Log any GUID collisions across different feeds (cross-feed dedup is informational only)

#### Storage Management

- Default folder: app-specific external storage
- User can change folder via folder picker
- Move files dialog: moves all existing downloads to new folder, updates `localFilePath` in DB
- "Free Space" indicator in Settings showing available storage

### 7.8 QuickPlay — Voice & Shortcut Playback

QuickPlay allows users to instantly start a SmartPlay-like session using a spoken or typed phrase, without navigating the UI. It is activated by:
- Voice search (Google Assistant / `MEDIA_PLAY_FROM_SEARCH` intent)
- Home screen shortcuts
- Third-party automation apps (Tasker, Llama) via broadcast intent
- In-app quick search bar

#### QuickPlay Syntax

Phrases are parsed against a simple grammar:

```
[order] in [source]
examples:
  "Newest in Technology"   → newest episodes from the Technology category
  "Oldest in BBC News"     → oldest episodes from the feed named "BBC News"
  "Random in all"          → random episodes from all feeds
  "Play episode stem cells"→ search all episodes for "stem cells" in title, play first match
```

Parsing logic:
1. Detect order word: "newest" | "latest" | "oldest" | "random" (default: newest)
2. Detect source after "in": match against category names, then feed names, then "all"
3. If "episode" keyword present: perform title search across all episodes
4. Build queue snapshot and start playback

#### QuickPlay Shortcuts

Users can create named home screen shortcuts (via long-press → "Add Shortcut"):
- Shortcut label (user-defined)
- Action: start specific SmartPlay list OR QuickPlay phrase
- Icon: customisable from material icon set

Shortcuts fire `Intent.ACTION_VIEW` with `beyondpod://quickplay?phrase=...` which is handled by `ShortcutHandler` activity.

**Tasker / Automation Integration:**
BeyondPod listens for `mobi.beyondpod.command.QUICK_PLAY` broadcast with extra `phrase` (String). This enables full automation of playlist creation from external apps.

### 7.9 Feed Discovery & Search

#### iTunes Podcast Directory Search

URL: `https://itunes.apple.com/search?media=podcast&term={query}&limit=25`

Returns JSON with `results[]` containing `feedUrl`, `trackName`, `artistName`, `artworkUrl600`, `genres`, `country`, `primaryGenreName`.

UI (AddFeedView):
- Search bar with voice search support
- Results in scrollable grid (2 columns on phone, 4 on tablet)
- Each card: artwork + podcast name + author
- Tapping card → FeedPreviewActivity

#### Browse by Category (Publishers)

Powered by iTunes categories API or a curated hardcoded list of popular feeds per genre.

Grid of genre chips: Technology, News, Comedy, True Crime, Business, Education, Health, Science, Arts, Sports, Society, Religion, Music, Kids, TV & Film, Government.

Tapping genre → publisher/feed list for that genre.

#### FeedPreviewActivity

Shows a preview of the podcast before subscribing:
- Header: artwork, title, author, website link
- Description
- Episode list (5 most recent, non-interactive)
- Subscribe button → triggers `FeedRepository.subscribeToFeed(url)`

### 7.9 Import / Export

#### OPML Import

- Open `.opml` file (via file picker or intent)
- Parse OPML XML
- Extract `<outline type="rss">` elements with `xmlUrl` attribute
- For each URL, call `FeedRepository.subscribeToFeed()` concurrently (up to 4 parallel)
- Progress dialog showing X of Y feeds imported
- Import into existing categories if OPML has `<outline>` groupings that match existing category names
- Save imported OPML to `BeyondPodFeeds.opml` in Downloads

#### OPML Export

- Generate standard OPML 2.0 XML
- Group feeds under `<outline>` elements matching category names
- Save to `BeyondPodFeeds.opml` in Documents folder
- Share intent (share file, email, etc.)

#### Backup / Restore (.bpbak) — Revival Format

A Revival `.bpbak` file is a **ZIP archive** containing structured JSON + metadata. This is the native Revival backup format.

**.bpbak internal structure (Revival format):**
```
BeyondPod_backup_YYYYMMDD_HHMMSS.bpbak (ZIP)
├── BackupManifest.txt         # Plain text: format version, app version, timestamp, device, file list
├── feeds.json                 # FeedEntity array + auth credentials (passwords AES-GCM encrypted)
├── feed_categories.json       # FeedCategoryCrossRef array
├── categories.json            # CategoryEntity array
├── smart_playlists.json       # SmartPlaylistEntity array (rules JSON embedded)
├── episode_states.json        # Play state per episode keyed by feedUrl+guid
│                              #   {feedUrl, guid, playState, playPositionMs, isStarred, isProtected}
├── episode_history.json       # EpisodePlayHistoryEntity array (play event audit log)
├── queue_snapshot.json        # Active QueueSnapshotEntity + QueueSnapshotItemEntity array
└── settings.json              # All DataStore Preferences key/value pairs (non-sensitive only)
```

**BackupManifest.txt format (Revival):**
```
BeyondPodRevivalVersion=5.0.0
BackupFormatVersion=1
BackupDate=2026-01-07T16:53:53Z
DeviceModel=Samsung SM-S911B
FeedCount=44
EpisodeCount=948
```

**Backup process:**
1. Serialise all `FeedEntity` + `FeedCategoryCrossRef` to JSON. Encrypt per-feed passwords with AES-GCM before writing.
2. Serialise `CategoryEntity` list
3. Serialise `SmartPlaylistEntity` list (rules JSON included)
4. Serialise `EpisodeEntity` play states keyed by `feedUrl + guid` (playState, playPositionMs, isStarred, isProtected)
5. Serialise `EpisodePlayHistoryEntity` audit log entries
6. Serialise active `QueueSnapshotEntity` + items (so queue survives device change)
7. Serialise app settings from DataStore (skip EncryptedSharedPreferences — credentials handled per-feed above)
8. Write `BackupManifest.txt`
9. Zip into `.bpbak` archive named `BeyondPod_backup_YYYYMMDD_HHMMSS.bpbak`

**Restore process:**
1. User opens `.bpbak` (handled by intent filter for `.bpbak`, MIME `application/bpbak` / `application/x-bpbak`)
2. Inspect `BackupManifest.txt` — detect format version. If `BeyondPodRevivalVersion` key is absent → route to Legacy Importer (see below).
3. Show confirmation dialog: backup date, device, feed count
4. Option: **Replace all** (wipe current data) or **Merge** (only add feeds/playlists not already present by URL)
5. Restore subscriptions → trigger background refresh of all feeds
6. Apply episode states to matching episodes (matched by feedUrl + guid)
7. Restore queue snapshot — items whose files are not present on this device are kept with `localFilePath = null`

**Cloud Sync support** for backup (v1.0 manual only — user shares file via standard Android share sheet to Dropbox, Drive, etc.):
- Automated cloud sync is `[FUTURE]`

---

#### Legacy BeyondPod 4.x Import (.bpbak)

> **Required for migration**: The original BeyondPod app used an entirely different internal `.bpbak` format — SQLite database + XML + binary files. Users migrating from the abandoned app will have backups in this format. The Revival app **must** import them.

**Detection**: If the `.bpbak` ZIP contains `beyondpod.db.autobak` (not `BackupManifest.txt` with `BeyondPodRevivalVersion` key), treat as a legacy import.

**Original BeyondPod .bpbak internal structure** (confirmed from real backup — `BeyondPod_Backup_2026-01-07.bpbak`):
```
BeyondPod_Backup_YYYYMMDD_HHMMSS.bpbak (ZIP)
├── BackupManifest.txt            # Plain text: version, date, device, file list
├── beyondpod.db.autobak          # Full SQLite database (copy of the live app database)
├── Settings.xml.autobak          # XML SharedPreferences: all app settings as key/value pairs
├── PlayList.bin.autobak          # Binary: active queue stored as device-absolute file paths
└── beyondpod_widget_info.autobak # Binary widget state (ignore on import)
```

**Legacy BackupManifest.txt format:**
```
BackupDate=Wed, 07 Jan 2026 16:53:53 GMT+01:00
BeyondPodVersion=4.3.321
DeviceModel=Samsung SM-S911B
BackupFiles=beyondpod.db.autobak|Settings.xml.autobak|PlayList.bin.autobak|...
```

**Legacy SQLite schema mapping** (`beyondpod.db.autobak` → Revival entities):

| Legacy table.column | Revival entity.field | Transform |
|---|---|---|
| `feeds.feedId` (UUID string) | — | Temp import key only; not stored in Revival |
| `feeds.name` | `FeedEntity.title` | Direct copy |
| `feeds.url` | `FeedEntity.url` | Canonical RSS URL; dedup key |
| `feeds.imageUrl` | `FeedEntity.imageUrl` | |
| `feeds.category` | `FeedCategoryCrossRef` | Pipe-string `"Primary\|Secondary"` → split on `\|` → look up category by name → insert cross-ref rows |
| `feeds.custDownload` | `FeedEntity.downloadStrategy` | Integer → `DownloadStrategy` enum |
| `feeds.maxDownload` | `FeedEntity.downloadCount` | |
| `feeds.maxTracks` | `FeedEntity.maxEpisodesToKeep` | |
| `feeds.maxTrackAge` | `FeedEntity.maxTrackAgeDays` | 99999 = keep forever |
| `feeds.trackSort` | `FeedEntity.episodeSortOrder` | Integer → `EpisodeSortOrder` enum |
| `feeds.fingerprintType` | `FeedEntity.fingerprintType` | -1=virtual, 1=hash, 2=GUID |
| `feeds.audioSettings` | `FeedEntity.playbackSpeed` | Format: `"speedIndex\|"` — speedIndex is 0-based index into speed table `[0.5, 0.6, 0.7, 0.8, 0.9, 1.0, 1.1, 1.2, 1.3, 1.4, 1.5, 1.75, 2.0, 2.5, 3.0]` |
| `feeds.username` | `FeedEntity.authUsername` | |
| `feeds.type` | `FeedEntity.isVirtualFeed` | 1=virtual folder, 3=RSS |
| `feeds.path` | `FeedEntity.virtualFeedFolderPath` | Only when type=1 |
| `tracks.orgRssItemID` | `EpisodeEntity.guid` | RSS GUID; primary dedup key |
| `tracks.name` | `EpisodeEntity.title` | |
| `tracks.url` | `EpisodeEntity.url` | Stream URL |
| `tracks.path` | `EpisodeEntity.localFilePath` | Absolute device path → mark `downloadState=DOWNLOADED` if path non-null, else `NOT_DOWNLOADED` |
| `tracks.totalTime` | `EpisodeEntity.duration` | Seconds → millis (×1000) |
| `tracks.playedTime` | `EpisodeEntity.playPosition` | Seconds → millis (×1000) |
| `tracks.played` | `EpisodeEntity.playState` | 0 + playedTime=0 → NEW; 0 + playedTime>0 → IN_PROGRESS; 1 → PLAYED |
| `tracks.downloadSize` | `EpisodeEntity.fileSizeBytes` | Bytes |
| `tracks.downloadPortion` | `EpisodeEntity.downloadBytesDownloaded` | Bytes downloaded; equals `downloadSize` when fully downloaded (partial download resume) |
| `tracks.locked` | `EpisodeEntity.isProtected` | 0=false, 1=true. **Honour this**: locked episodes must never be auto-deleted. |
| `tracks.showNotes` | `EpisodeEntity.htmlDescription` | Raw HTML |
| `tracks.description` | `EpisodeEntity.description` | Plain text |
| `tracks.pubDate` | `EpisodeEntity.pubDate` | Epoch millis |
| `tracks.parentFeedID` | join key | UUID string → resolve to Revival `FeedEntity.id` via UUID→Long mapping built during import |
| `categories` | `CategoryEntity` | Single table row, pipe-delimited: `"name^sortOrder\|"` — split on `\|`, then `^`, create one `CategoryEntity` per segment |
| `smartplaylist.playlistName` | `SmartPlaylistEntity.name` | |
| `smartplaylist.feedId` | `SmartPlaylistBlock.sourceId` | null = all feeds in category; UUID → resolve to Revival feed PK |
| `smartplaylist.categoryId` | `SmartPlaylistBlock.sourceId` | **Stored as category NAME string** (not integer); resolve to Revival `CategoryEntity.id` via name lookup (see Category Name Resolution below) |
| `smartplaylist.numEpisodes` | `SmartPlaylistBlock.count` | |
| `smartplaylist.sortOrder` | `SmartPlaylistBlock.order` | Map to `BlockEpisodeOrder` enum |
| `episode_history` | `EpisodePlayHistoryEntity` | Import all rows (play event audit log) |

**Category Name Resolution at import time (QA finding #3):**

Because the original `smartplaylist.categoryId` stores a category NAME (not an integer), category renaming creates a mapping fragility. The resolution algorithm at import time must be:

```
fun resolveCategoryByName(rawName: String, importedCategories: List<CategoryEntity>): Long? {
    // Step 1: Exact match (fastest, handles most cases)
    importedCategories.firstOrNull { it.name == rawName }?.id?.let { return it }

    // Step 2: Case-insensitive match (handles BeyondPod's inconsistent casing)
    importedCategories.firstOrNull {
        it.name.equals(rawName, ignoreCase = true)
    }?.id?.let { return it }

    // Step 3: Normalised match (strip leading/trailing whitespace + tabs)
    val normalisedRaw = rawName.trim().replace("\\t".toRegex(), " ")
    importedCategories.firstOrNull {
        it.name.trim().replace("\\t".toRegex(), " ").equals(normalisedRaw, ignoreCase = true)
    }?.id?.let { return it }

    // Step 4: Fuzzy match — if exactly one category contains the name as a substring
    val fuzzyMatches = importedCategories.filter {
        it.name.contains(rawName.trim(), ignoreCase = true)
    }
    if (fuzzyMatches.size == 1) return fuzzyMatches[0].id

    // No match: log warning, return null (playlist rule will have no category filter)
    Log.w("LegacyImport", "Could not resolve category name: '$rawName'")
    return null
}
```

**Post-import safety:** Once the import is complete and SmartPlaylistBlock records hold Revival `Long` category IDs (not names), renaming a category is perfectly safe. The FK is stable. The name-as-ID problem is confined entirely to the import path. Document this to future maintainers: the category name resolver is only ever called by `LegacyBpbakImporter`, never at runtime.

**Legacy Settings.xml.autobak mapping** (Android SharedPreferences XML format):

| Settings.xml key | DataStore key | Notes |
|---|---|---|
| `ScrobbleEnabled` | `scrobble_enabled` | boolean |
| `PauseOnNotification` | `pause_on_notification` | boolean |
| `TurnWiFiDuringUpdate` | `turn_wifi_during_update` | boolean — force WiFi on during feed updates |
| `BackwardSkipInterval` | `skip_back_seconds` | int (seconds) |
| `ForwardSkipInterval` | `skip_forward_seconds` | int (seconds) |
| `BTNextButtonAction` | `bt_next_button_action` | int → `BluetoothButtonAction` enum |
| `primarySmartplayId` | `primary_smartplaylist_id` | long; resolve legacy playlist ID to Revival playlist PK by name matching |
| `PRIVATE_FEED_DATA:{feedId}` | EncryptedSharedPreferences | Per-feed password; original key includes UUID feedId. During import: read value → store in Revival EncryptedSharedPreferences under `feed_password_{revivalFeedId}`, set `FeedEntity.hasAuthPassword = true` |
| `userNotificationPreferences` | `notification_preferences` | Pipe-delimited 20-boolean flags string — see Notification Preferences bit-field (§7.14) |

**PlayList.bin.autobak — queue migration:**

The original BeyondPod queue is stored as a sequence of absolute device file paths (ASCII strings). These paths are device-specific and will be invalid on the importing device.

Import procedure:
1. Parse `PlayList.bin.autobak`: extract all ASCII strings that look like file paths (start with `/`, length > 10)
2. For each path, extract the filename component (after last `/`)
3. Match filename against imported `EpisodeEntity.localFilePath` values
4. Build `QueueSnapshotEntity` from matched episodes in original path order
5. Log unmatched paths as "queue item not found on device" — skip silently, do not error
6. Show post-import summary: "Restored N of M queue items — files not present on this device were skipped"

**Legacy import UI flow:**
1. User opens a `.bpbak` file → app detects legacy format
2. Show dialog: "This is a BeyondPod 4.x backup. Import it into BeyondPod Revival?" with preview (feed count, episode count, backup date)
3. User confirms → `LegacyBpbakImporter` coroutine runs with progress dialog
4. On completion → show import summary: feeds imported, episodes imported, queue items restored, settings applied
5. Trigger background refresh of all imported feeds

### 7.10 Cross-Device Sync (gpodder.net / Nextcloud)

#### Provider Support

1. **gpodder.net** (default) — `https://gpodder.net`
2. **Nextcloud GPodder Sync plugin** — custom server URL

Both use the gPodder Sync API:
- `POST /api/2/auth/{username}/login.json` — authenticate
- `GET /api/2/subscriptions/{username}/{deviceId}.json?since={timestamp}` — get subscription changes
- `POST /api/2/subscriptions/{username}/{deviceId}.json` — upload subscription changes
- `GET /api/2/episodes/{username}.json?since={timestamp}` — get episode actions
- `POST /api/2/episodes/{username}.json` — upload episode actions

#### Sync Settings (CrossDeviceSyncSettingsFragment)

- Provider selector (gpodder.net / Nextcloud)
- Server URL field (for Nextcloud)
- Username + Password
- Device name / Device ID
- Sync subscriptions toggle
- Sync episode state toggle (play position, played status)
- Sync interval: Manual / 1h / 6h / 24h
- "Sync Now" button
- Last sync timestamp display
- "Disconnect" button

#### Sync Logic

**Subscription sync:**
1. Download remote subscription list
2. Compute diff vs local: new_remote (subscribe), removed_remote (unsubscribe)
3. Apply changes to local database
4. Upload local subscription list if changed since last sync

**Episode action sync:**
1. Download episode actions since `lastSyncTimestamp`
2. For each action: `play`, `download`, `delete`, `flattr` — update matching local episode
3. Upload local episode actions (played, position saves) since `lastSyncTimestamp`
4. Update `lastSyncTimestamp`

#### SyncWorker

Runs as a periodic `WorkManager` worker. Interval configurable. Requires `NetworkType.CONNECTED`.

### 7.11 Home Screen Widgets

Seven widget sizes, all built with `AppWidgetProvider` + `RemoteViews`. Widgets do **not** use Compose (not supported in widgets until later API levels — use XML RemoteViews).

| Widget | Layout file | Description |
|---|---|---|
| Mini | `bp_appwidget_mini` | Single play/pause button + artwork (1×1) |
| Small | `bp_appwidget_small` | Episode title + play/pause + skip (2×1) |
| Medium | `bp_appwidget_med` | Artwork + title + transport controls (2×2) |
| XMedium | `bp_appwidget_xmed` | Medium + feed name + progress (3×2) |
| Large | `bp_appwidget_large` | Full controls + seekbar + episode list (4×2) |
| XLarge | `bp_appwidget_xlarge` | All controls + upcoming episodes queue (4×4) |
| Lockscreen | `bp_appwidget_lockscreen` | Legacy lockscreen widget (API < 21) |

**Widget configuration (WidgetPreferences):**
- Widget size (if ambiguous)
- Background: transparent, dark, light, theme color
- Show/hide episode artwork
- Action buttons: configurable (e.g., button 1 = skip back, button 2 = speed)

**Widget actions:**
Each button in widget fires a `PendingIntent` to `WidgetUpdateReceiver` or directly to `PlaybackService`.

Available widget button actions (`WidgetActionPicker`):
- Play/Pause
- Next Episode
- Previous Episode
- Skip Forward (configurable seconds)
- Skip Back (configurable seconds)
- Open BeyondPod
- Open My Episodes
- Speed Up / Speed Down

**Widget update trigger:**
Widgets update when `PlaybackService` broadcasts `ACTION_PLAYBACK_STATE_CHANGED`. Also update on episode download complete, and on a 60-second interval (for seekbar position).

### 7.12 Android Auto / Car Mode

**Android Auto:**
Register `MediaBrowserServiceCompat` in manifest. `PlaybackService` extends both `MediaSessionService` and `MediaBrowserServiceCompat`.

Media browser tree:
```
Root
├── My Episodes (playlist)
├── Smart Playlists
│   ├── [Playlist 1]
│   └── [Playlist 2]
└── Recent Episodes
```

Auto UI uses the standard Android Auto media template. BeyondPod provides:
- `MediaItem` list from current queue
- Playback controls via `MediaSession`
- Skip forward/back actions as custom media buttons

**Car Mode (manual):**
When Android Driving Mode is active, `player_transport_controls_car_mode` layout is used — larger buttons, no seekbar, simplified UI.

### 7.13 Notifications

#### Playback Notification

`PlaybackNotificationManager` uses Media3's `DefaultMediaNotificationProvider` extended with custom actions.

Notification channels:
- `CHANNEL_PLAYBACK` — ongoing, foreground service notification
- `CHANNEL_DOWNLOADS` — download progress (low priority)
- `CHANNEL_UPDATE` — feed update complete (default priority, dismissable)

Playback notification buttons:
- Rewind (configurable seconds)
- Play / Pause
- Skip Forward (configurable seconds)
- Next Episode
- Close (stop playback)

#### Download Notification

Progress notification during download showing:
- Episode title
- Progress bar (0–100%)
- Cancel button

Collapses to a summary when multiple downloads active.

#### New Episodes Notification

When feed update completes and new episodes are found:
- Single notification: "X new episodes from Y feeds"
- Expanded: list of feeds with new episode count
- Tap → opens My Episodes
- Per-feed notification override (optional): "3 new episodes from [Feed Name]"

### 7.14 Settings

Settings are implemented as Jetpack Compose screens navigated hierarchically. The root settings screen has the following sections:

#### General Settings

- Default sort order for episode list: Newest First / Oldest First
- Default filter: All / New / Downloaded
- Mark episodes as played when: Never / Episode ends / Seekbar passes 90%
- Auto-open player when episode starts (toggle)
- Show episode thumbnails (toggle)
- Keep played episodes: Forever / 1 day / 3 days / 7 days / 14 days / 30 days / Delete immediately
- Screen orientation lock: System / Portrait / Landscape

#### Feed Update Settings

- Auto-update feeds (toggle)
- Update interval: 15 min / 30 min / 1h / 2h / 4h / 6h / 12h / 24h / Manual
- Update on WiFi only (toggle)
- Update on start (toggle)
- Update only between hours: [from] and [to] (time range picker)
- Background update notification (toggle)
- Maximum concurrent updates: 1 / 2 / 4
- **Enable WiFi during update** (toggle) — `turn_wifi_during_update` DataStore key.
  If enabled: before starting a scheduled feed update, programmatically enable WiFi if it is off.
  After update completes, restore WiFi to its previous state.
  Original BeyondPod key: `TurnWiFiDuringUpdate`. Note: Android 10+ restricts programmatic WiFi
  enable to system apps — on API 29+, show a `WifiManager.ACTION_REQUEST_ENABLE` intent instead
  and document the limitation in the UI. On API 28 and below, `WifiManager.setWifiEnabled()` works.

#### Download Settings

- Auto-download new episodes (toggle, global default)
- Episodes to download per feed: 1–20 (spinner)
- Episodes to keep per feed: 1–50 / Keep All
- Download on WiFi only (toggle)
- Download folder (folder picker)
- Delete played episodes (toggle)
- Delete played after: Immediately / 1 day / 3 days / 7 days
- Auto-delete when storage low (toggle)

#### Playback Settings

- Default playback speed: 0.5x – 4.0x (original BeyondPod: 0.3x–3.0x)
- Preset speed buttons 1 / 2 / 3: configurable values (default: 1.0x / 1.5x / 2.0x)
- Skip silence (toggle)
  - Minimum silence duration: 500ms / 1000ms / 1500ms / 2000ms
  - Aggressiveness: Low / Medium / High
- Rewind on pause: 0 / 2 / 5 / 10 / 15 / 20 / 30 sec
- Skip back duration: 10 / 15 / 20 / 30 / 45 / 60 / 90 / 120 sec
- Skip forward duration: same
- Default volume boost: 1 (no boost, 0dB) to 10 (max, ~10dB) — implemented via `LoudnessEnhancer`; do NOT use `player.volume > 1.0f` (clips). See §7.6 Volume Boost.
- Continue after call (toggle)
- Pause on headset disconnect (toggle)
- Resume on headset reconnect delay: Disabled / 1s / 3s / 5s / 10s
- Pause on Bluetooth disconnect (toggle)
- Resume on Bluetooth reconnect delay: Disabled / 3s / 5s / 10s / 30s
- Duck on notifications (toggle — reduce volume instead of pausing)
- Pause on notification: toggle (pause playback on any notification sound)
- Use hardware volume buttons for seeking (toggle — repurposes vol+/vol- as skip forward/back)
- Enable fade-in on play (toggle)
- Keep 'Paused' notification (toggle — when off, notification disappears on pause)
- After playing: Do Nothing / Delete Episode / Delete and Play Next

#### Headset Button Settings

Long Press action:
  - None / Play-Pause / Next / Skip Forward / Speed Toggle / Sleep Timer

Double Click action:
  - None / Play-Pause / Next / Previous / Skip Forward / Rewind

Triple Click action:
  - None / Play-Pause / Next / Previous

#### Player Integration Settings

- Default player: Internal / External audio player / External video player
- External audio player package name (if selected)
- External video player package name (if selected)
- Episodes visible in Music Player (toggle — writes to MediaStore)

#### Widget Settings

- Widget background style: Transparent / Dark / Light / Theme
- Widget button 1–4 action (per button action picker)

#### Notification Settings (OS settings deep link + BeyondPod overrides)

- New episode notification (toggle)
- Show notification per feed (toggle)
- Download complete notification (toggle)
- Update log notification (toggle)

**Notification preferences bit-field** — stored as `notification_preferences` in DataStore as a pipe-delimited string of 20 boolean flags (0 or 1). Matches the original BeyondPod `userNotificationPreferences` SharedPreferences key format: `"1|0|0|1|1|1|0|0|1|1|1|1|0|1|0|0|0|0|0|0|"`.

| Flag index | Setting | DataStore key |
|---|---|---|
| 0 | New episodes available (heads-up notification) | `notif_new_episodes` |
| 1 | New episodes — show when screen is off only | `notif_new_screen_off_only` |
| 2 | New episodes — vibrate | `notif_new_vibrate` |
| 3 | Download complete notification | `notif_download_complete` |
| 4 | Download complete — vibrate | `notif_download_vibrate` |
| 5 | Update log notification | `notif_update_log` |
| 6 | Update log — show only on errors | `notif_update_errors_only` |
| 7 | Update log — vibrate | `notif_update_vibrate` |
| 8 | Playback notification visible during playback | `notif_playback` |
| 9 | Playback notification — persistent (survives swipe dismiss) | `notif_playback_persistent` |
| 10 | Playback notification — show on lock screen | `notif_playback_lockscreen` |
| 11 | Playback notification — show artwork | `notif_playback_artwork` |
| 12 | Playback notification — compact actions include skip | `notif_playback_skip_action` |
| 13 | New episode notification per-feed (show which feed) | `notif_per_feed` |
| 14 | LED notification light | `notif_led` |
| 15–19 | Reserved for future flags | — |

Store internally as `List<Boolean>` (size 20). Serialise to pipe string for legacy backup/restore compatibility. Provide individual DataStore keys for each flag — do NOT store as a single string in DataStore (the string form is only for backup interchange and legacy import).

#### Synchronization Settings

→ Navigates to CrossDeviceSyncSettingsFragment (see §7.10)

#### Backup & Restore

→ Navigates to BackupRestoreActivity (see §7.9)

#### Feed Update Settings (additions)

- Update feeds on WiFi only (toggle) — **separate from download WiFi restriction**
  - Allows updating feed metadata on mobile but restricting file downloads to WiFi
- Maximum concurrent feed updates: 1 / 2 / 4

#### Advanced / Debug Settings

- Enable detailed logging (toggle)
- View BeyondPod log (log viewer composable)
- View Android log
- Allow streaming on mobile data (toggle)
- Use mobile data for updates (toggle)
- Allow cleanup for manual downloads (global toggle — applies to feeds with MANUAL strategy)
- Mark read on scroll (toggle, default OFF) — auto-marks episodes as read when scrolled past
- Custom HTTP User Agent string (text field; empty = default BeyondPod agent)
- Connection timeout: 10s / 30s / 60s / 120s
- Publish current episode (toggle) — legacy Bluetooth metadata publishing for older car stereos
- Last.FM scrobbling: enable toggle + username/password fields
- Analytics opt-in (toggle) — disabled by default, opt-in only
- Reset all settings to default

#### Storage Settings (new section)

- Download folder (folder picker)
- Storage usage display: list of feeds sorted by disk usage, each showing MB used + episode count
- Per-feed "Clean up" action from this list
- "Free up space" one-tap: delete all PLAYED downloaded episodes (respects isProtected)
- Total downloaded: X GB / Available: Y GB (visual bar)

#### About

- Version info
- Open source licenses
- MIT License text
- GitHub link
- Changelog / What's New

---

## 8. UI Screens & Navigation

### Navigation Graph

```
Splash
  └── FirstRun (if first launch)
       └── → Main
  └── → Main (if returning user)

Main (MasterView) [DrawerLayout on phone]
  ├── Drawer (NavigationDrawer)
  │   ├── My Episodes → PlaylistView(playlistId=MY_EPISODES)
  │   ├── All Published → AllPublishedView
  │   ├── [Category] → CategoryEpisodeView(categoryId)
  │   │   └── [Feed] → FeedEpisodeView(feedId)
  │   ├── [SmartPlaylist] → PlaylistView(playlistId)
  │   ├── Add Podcast → AddFeedView
  │   └── Settings → SettingsRoot
  │
  └── Content Panel
      ├── FeedEpisodeView (default: last-viewed feed)
      └── Mini Player (persistent bottom)

FeedEpisodeView(feedId)
  └── EpisodeDetail(episodeId) [swipe-through pager]

PlaylistView(playlistId)
  └── Episode context → EpisodeDetail

FullScreenPlayer
  ├── Chapters list (bottom sheet)
  └── Show Notes (bottom sheet)

AddFeedView
  ├── FeedPreview(feedUrl) → subscribe
  └── PublisherResultsView(genre)

FeedPropertiesView(feedId)

CategoryPropertiesActivity(categoryId)

SmartPlaylistEditorView(playlistId)

SettingsRoot
  ├── SettingsGeneral
  ├── SettingsFeedUpdate
  ├── SettingsDownload
  ├── SettingsPlayback
  ├── SettingsHeadsetButtons
  ├── SettingsWidgets
  ├── SettingsNotifications
  ├── SettingsSync → CrossDeviceSyncSettings
  ├── SettingsBackupRestore → BackupRestoreView
  ├── SettingsAdvanced
  └── SettingsAbout

ImportFeedsView (OPML import)
DownloadQueueView
```

### Key UI Patterns

**Two-pane on tablets (sw600dp+):**
- Navigation drawer is permanently open left panel
- Feed list is a middle panel
- Episode list / content is right panel
- All three panels visible simultaneously on landscape tablet

**Episode list scrolling:**
- Header pinned at top showing feed artwork, title, episode count, Last Updated
- Sticky "filter bar" below header with filter chips and sort dropdown
- Episodes in infinite scroll (lazy column)

**Swipe-to-action:**
Swipeable episode rows. Use `SwipeToDismiss` from Material3. Left and right actions configurable.

**Drag-to-reorder:**
Used in: My Episodes queue, Navigator category ordering, Playlist editor. Use `LazyColumn` with `reorderable` (ReorderableColumn from `sh.calvin.reorderable` library).

---

## 9. Services & Background Work

### PlaybackService

```kotlin
@AndroidEntryPoint
class PlaybackService : MediaSessionService() {
    // Inject ExoPlayer via Hilt
    // Maintain MediaSession
    // Handle: play, pause, seek, skip, speed, queue management
    // Broadcast state changes to widgets
    // Handle audio focus
    // Handle headset button events (via MediaSession callbacks)
    // Handle sleep timer
    // Save play position to DB every 5 seconds during playback
    // On episode complete: mark played, advance queue, auto-download next
}
```

Foreground notification: must be shown within 5 seconds of `onStartCommand`.

### FeedUpdateWorker

```kotlin
@HiltWorker
class FeedUpdateWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val feedRepository: FeedRepository
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        // inputData: FEED_ID (single feed) or ALL_FEEDS flag
        // Steps MUST run in this order — cleanup before download is critical for DOWNLOAD_NEWEST:

        // 1. Fetch + parse feed XML
        // 2. Run episode deduplication (see Episode Identity Strategy §5.1)
        // 3. Upsert new episodes (PlayState.NEW for all truly new episodes)
        // 4. Archive removed episodes (no longer in feed XML, isArchived = true)
        //
        // ── POST-UPDATE CLEANUP (QA finding #9 — AUTOMATIC, NOT OPTIONAL) ──────────────
        // 5. Enforce maxTrackAgeDays: mark episodes older than feed.maxTrackAgeDays as eligible
        //    for cleanup (unless isProtected = true — isProtected is always an absolute veto).
        // 6. Enforce maxEpisodesToKeep: if feed has downloadStrategy == DOWNLOAD_NEWEST,
        //    delete (or mark DELETED) oldest downloaded episodes beyond maxEpisodesToKeep,
        //    respecting isProtected. Cleanup runs BEFORE step 7 (download new episodes).
        //    Rationale: BeyondPod enforced retention limits post-update automatically.
        //    Deferring cleanup to a separate task creates a window where storage fills up.
        // 7. Trigger auto-download if feed downloadStrategy ≠ MANUAL:
        //    - DOWNLOAD_NEWEST: download up to (downloadCount) newest undownloaded episodes
        //    - DOWNLOAD_IN_ORDER: download next (downloadCount) episodes in chronological order
        //    - STREAM_NEWEST: create EpisodeEntity with downloadState = NOT_DOWNLOADED (streamable)
        // ────────────────────────────────────────────────────────────────────────────────
        //
        // 8. Auto-add newly downloaded episodes to My Episodes if per-feed toggle is enabled
        // 9. Send notification if new episodes found (respects notification preferences)
        // 10. Return Result.success() or Result.retry() on network error (max 3 retries)
    }
}
```

Scheduled as `PeriodicWorkRequest` with frequency from Settings. Also enqueued as `OneTimeWorkRequest` when user manually refreshes.

### DownloadWorker

```kotlin
@HiltWorker
class DownloadWorker @AssistedInject constructor(...) : CoroutineWorker(...) {
    override suspend fun doWork(): Result {
        // episodeId from inputData
        // 1. Get episode URL (local or stream)
        // 2. OkHttp stream download with progress
        // 3. Write to file (create dirs if needed)
        // 4. Update episode.downloadState, localFilePath
        // 5. Update notification progress
        // 6. Return Result.success() or Result.retry()
    }
}
```

Max concurrency: 2 downloads at once (WorkManager `KEEP` policy on existing, `APPEND_OR_REPLACE` for new).

### BootReceiver

```kotlin
class BootReceiver : BroadcastReceiver() {
    // ACTION_BOOT_COMPLETED
    // Re-enqueue periodic FeedUpdateWorker (WorkManager already handles this,
    // but belt-and-suspenders for edge cases)
    // Re-apply any alarm-based schedules
}
```

### SyncWorker

```kotlin
@HiltWorker
class SyncWorker @AssistedInject constructor(...) : CoroutineWorker(...) {
    override suspend fun doWork(): Result {
        // 1. Check sync is enabled and credentials valid
        // 2. syncRepository.syncNow()
        // 3. Return success or retry on transient error
    }
}
```

---

## 10. Permissions & Intent Filters

### AndroidManifest.xml Permissions

```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK" />
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
<uses-permission android:name="android.permission.WAKE_LOCK" />
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
<uses-permission android:name="android.permission.VIBRATE" />
<uses-permission android:name="android.permission.BLUETOOTH" android:maxSdkVersion="30" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<!-- Storage: request at runtime on API < 29 -->
<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" android:maxSdkVersion="32" />
<uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" android:maxSdkVersion="28" />
<!-- Android 13+ granular media -->
<uses-permission android:name="android.permission.READ_MEDIA_AUDIO" />
```

### MasterView Intent Filters

Handle the following URI schemes to allow direct subscription from podcast websites and other apps:
- `itpc://`, `pcast://`, `feed://`, `rss://`
- `beyondpod://` (deep links)
- HTTP/HTTPS URLs with `.xml`, `.rss` paths
- `subscribeonandroid.com` host
- FeedBurner domains (`feeds.feedburner.com`, etc.)
- MIME types: `application/rss+xml`, `application/atom+xml`, `text/xml`
- `android.intent.action.SEND` with `text/plain` (share URL to subscribe)
- `android.media.action.MEDIA_PLAY_FROM_SEARCH` (voice search)

### BackupRestoreActivity Intent Filter

- Files with `.bpbak` extension
- MIME `application/bpbak`, `application/x-bpbak`

---

## 11. First-Run Onboarding Flow

Triggered if `AppPreferences.isFirstRun == true`. Multi-step wizard:

**Step 1: Welcome**
- BeyondPod logo animation
- "Welcome to BeyondPod" headline
- Brief description
- "Get Started" button

**Step 2: Sample Content**
- Grid of suggested podcasts (curated hardcoded list in `assets/sample_feeds.json`)
- User can tap to select (multi-select)
- "None of these" option
- "Next" button

**Step 3: Auto-Update Config**
- Toggle for "Auto-update feeds"
- Update frequency dropdown
- WiFi-only toggle
- "Next" button

**Step 4: Ready**
- "You're all set!"
- Brief feature highlights (smart playlists, offline listening, sync)
- "Start Listening" → marks `isFirstRun = false`, navigates to Main

If user selected sample feeds in Step 2, subscribe in background (non-blocking) and navigate to Main.

---

## 12. Theming & Design System

### Color Palette

```kotlin
// Color.kt
val BeyondPodBlue = Color(0xFF1565C0)
val BeyondPodBlueDark = Color(0xFF003c8f)
val BeyondPodBlueLight = Color(0xFF5e92f3)
val BeyondPodOrange = Color(0xFFFF6D00)    // Accent / action
val SurfaceDark = Color(0xFF121212)
val SurfaceVariantDark = Color(0xFF1E1E1E)
val OnSurfaceDark = Color(0xFFE0E0E0)
```

**Default theme: Dark.** The original BeyondPod had a distinctive dark UI. Implement dark as the default; offer light and system-default options in Settings.

### Typography

Use `Inter` font (Google Fonts, bundled in `assets/fonts/`).

| Style | Size | Weight | Usage |
|---|---|---|---|
| Display | 22sp | SemiBold | Feed title in header |
| Headline | 18sp | Medium | Episode title in cards |
| Body | 14sp | Normal | Episode description |
| Caption | 12sp | Normal | Date, duration, feed name |
| Label | 11sp | Medium | Badges, chips |

### Episode Card Visual States

| State | Visual Indicator |
|---|---|
| NEW | Blue left border (4dp), bold title |
| IN_PROGRESS | Orange left border, progress indicator below title |
| PLAYED | Greyed-out title (40% alpha), no border |
| STARRED | Gold star icon |
| DOWNLOADED | Download complete icon (green checkmark) |
| DOWNLOADING | Animated download icon + % progress |

### Navigation Drawer Styling

- Dark background (`#1A1A1A`)
- Category headers: category color accent on left
- Feed rows: thumbnail, title, new-episode badge (Material3 `Badge`)
- Selected feed: highlighted row (`#2A2A2A` background)

---

## 13. Performance & Scalability Requirements

The app must handle large libraries without degrading.

### Scale Targets

| Metric | Minimum Target | Stress Target |
|---|---|---|
| Feeds | 200 | 500+ |
| Episodes per feed | 500 | 5,000+ |
| Total episodes in DB | 10,000 | 50,000+ |
| Downloaded files | 500 | 2,000+ |

### Mandatory Implementation Patterns

**Pagination everywhere**: Every episode list uses `Pager` + `PagingSource` from AndroidX Paging 3. Never load all episodes into memory. Page size: 30.

**Database indices**: All query fields are indexed. The following indices are mandatory (in addition to those on EpisodeEntity):
```
episodes: (feedId, pubDate), (feedId, playState), (feedId, downloadState)
queue_snapshot_items: (snapshotId, position)   ← replaces the removed isInQueue/queuePosition index
feeds: (primaryCategoryId), (downloadStrategy)
```

**Lazy image loading**: Feed artwork and episode artwork are loaded by Coil on-demand. No pre-loading of artwork for off-screen items.

**Batched WorkManager**: Feed updates for multiple feeds are batched into a single WorkManager chain, not N individual workers. Maximum 4 concurrent feed update jobs.

**No synchronous DB on main thread**: Room `@Query` functions must only be called from coroutines. Zero `runBlocking` on the main thread.

**Battery optimisation:**
- Wake locks held only during active download/playback; released immediately on completion
- Use `PowerManager.PARTIAL_WAKE_LOCK` only during active download
- Feed updates coalesced: if multiple feeds are due for update within a 5-minute window, update all in one batch job
- Exponential backoff on all network retries to avoid battery drain from repeated failures

## 14. Testing Strategy

### Unit Tests

- All `UseCase` classes: full unit tests with mock repositories
- `SmartPlaylist` rule engine: parameterised tests covering all rule field/operator combinations
- `FeedParser`: test against fixture RSS/Atom XML files (store in `test/resources/feeds/`)
- `OpmlParser`: test OPML import/export round-trip
- `.bpbak` Revival backup/restore serialisation round-trip (JSON format)
- `LegacyBpbakImporter`: integration test using the real `BeyondPod_Backup_2026-01-07.bpbak` fixture (store in `test/resources/fixtures/`) — verify 44 feeds, 948 episodes, correct category assignments, queue restoration, settings migration
- `DownloadWorker`: test with fake WorkManager
- `SyncRepository`: mock gpodder API responses

### Integration Tests (Room)

- `FeedDao`, `EpisodeDao`, `CategoryDao`, `SmartPlaylistDao`: test against in-memory Room database
- Queue ordering, episode state transitions, cascade deletes

### Compose UI Tests

- `PlaylistView`: verify episode list renders, swipe actions work
- `SmartPlaylistEditorView`: add/remove rules, save
- `SettingsScreens`: verify preference toggles persist
- `FirstRunFlow`: step through wizard to completion

### Snapshot Tests (Optional)

- Use `Paparazzi` for composable snapshot tests of episode cards (all 3 types × all states)

---

## Appendix A: Sample Feeds for First Run

Store in `assets/sample_feeds.json`:

```json
[
  {"title": "The Daily", "url": "https://feeds.simplecast.com/54nAGcIl", "imageUrl": "...", "category": "News"},
  {"title": "Radiolab", "url": "https://feeds.wnyc.org/radiolab", "imageUrl": "...", "category": "Science"},
  {"title": "How I Built This", "url": "https://feeds.npr.org/510313/podcast.xml", "imageUrl": "...", "category": "Business"},
  {"title": "Serial", "url": "https://feeds.thisamericanlife.org/serial", "imageUrl": "...", "category": "True Crime"},
  {"title": "Stuff You Should Know", "url": "https://feeds.megaphone.fm/stuffyoushouldknow", "imageUrl": "...", "category": "Education"},
  {"title": "Lex Fridman Podcast", "url": "https://lexfridman.com/feed/podcast/", "imageUrl": "...", "category": "Technology"},
  {"title": "99% Invisible", "url": "https://feeds.simplecast.com/BqbsxVfO", "imageUrl": "...", "category": "Design"},
  {"title": "Conan O'Brien Needs a Friend", "url": "https://feeds.simplecast.com/dHoohVNH", "imageUrl": "...", "category": "Comedy"}
]
```

---

## Appendix B: gPodder API Reference

Base URL: `https://gpodder.net` (or custom Nextcloud URL)

| Endpoint | Method | Purpose |
|---|---|---|
| `/api/2/auth/{username}/login.json` | POST | Authenticate, get session cookie |
| `/api/2/auth/{username}/logout.json` | POST | Logout |
| `/api/2/devices/{username}.json` | GET | List devices |
| `/api/2/devices/{username}/{deviceId}.json` | POST | Update device info |
| `/api/2/subscriptions/{username}/{deviceId}.json` | GET `?since={ts}` | Get subscription changes |
| `/api/2/subscriptions/{username}/{deviceId}.json` | POST | Upload subscription changes |
| `/api/2/episodes/{username}.json` | GET `?since={ts}` | Get episode actions |
| `/api/2/episodes/{username}.json` | POST | Upload episode actions |

Episode action types: `play`, `download`, `delete`, `new`, `flattr`

Episode action payload:
```json
{
  "podcast": "https://feed.url/podcast.xml",
  "episode": "https://episode.url/ep123.mp3",
  "action": "play",
  "timestamp": "2026-01-15T10:30:00",
  "started": 0,
  "position": 1234,
  "total": 3600
}
```

---

## Appendix C: Features NOT in Original BeyondPod — v2.0 Scope

These features did not exist in BeyondPod 4.x. They are **explicitly out of scope for v1.0** but are documented here so the community knows the direction of travel. Do not build these until the core feature set is stable and community-validated.

### C.1 Transcript Support

- Display episode transcripts (from `<podcast:transcript>` namespace or AI-generated)
- Two sources:
  1. **Podcast namespace**: `<podcast:transcript url="..." type="text/vtt"/>` — parse and display VTT/SRT
  2. **AI-generated** `[FUTURE]`: on-device Whisper model for auto-transcription of downloaded episodes
- UI: "Transcript" tab in Show Notes bottom sheet
- Search within transcript
- Tap a transcript line → seek to that position in audio

### C.2 Semantic Search Across Episodes

- Full-text search across episode titles and descriptions (already in SQLite FTS4/5)
- `[FUTURE]` Semantic/embedding search: generate text embeddings for episode descriptions, enable "find episodes about topic X" even when X isn't literally in the title
- Implementation: on-device embedding model or optional cloud API (user opt-in)

### C.3 Intelligent "Continue Listening" Suggestions

- Non-invasive: shown as a dedicated section, never as notifications
- Logic: surface in-progress episodes (playState = IN_PROGRESS) sorted by `lastPlayed` desc
- Already largely covered by the built-in "In Progress" smart playlist
- `[FUTURE]` Enhancement: detect episodes where the user almost finished (playedFraction > 0.8) and surface them separately as "Nearly Done"

### C.4 Sharing & Social

- "Share clip": share a 30-second audio clip of current position via standard Android share sheet
- "Share episode with timestamp": generate `beyondpod://play?feedUrl=...&episodeGuid=...&t=1234` link
- Both `[FUTURE]`

### C.5 Chapter Images & Enhanced Chapter Support

- `<podcast:chapters>` JSON spec already in v1.0
- `[FUTURE]` ID3v2 CHAP/CTOC frame parsing for older MP3s (BBC, older tech podcasts)
- `[FUTURE]` Chapter artwork display in player as episode progresses

---

## Appendix D: Known Behaviours & Deviations from Original

This table documents deliberate deviations from original BeyondPod 4.x behaviour.

| Feature | Original Behaviour | This Spec | Reason |
|---|---|---|---|
| SmartPlay rules | Sequential blocks (count + source + order) | Both models supported (SEQUENTIAL_BLOCKS + FILTER_RULES) | Filter rules are more powerful; sequential blocks preserved for parity |
| Speed range | 0.3x – 3.0x | 0.5x – 4.0x | 0.3x is barely useful; 4.0x useful for fast readers |
| Volume boost scale | 1–10 | 1–10 | Unchanged |
| Sync backend | Proprietary EpisodeSync (server shut down) | gPodder.net + Nextcloud | EpisodeSync servers are gone; gPodder is open standard |
| Feedly integration | Full read/star sync | Not implemented v1.0 | Feedly free API deprecated |
| Category membership | Up to 2 categories per feed | Up to 2 categories per feed | Preserved |
| Skip silence | Not supported in original | Supported via Media3 | ExoPlayer makes this trivial |
| Chapter support | Not supported in original | Supported via podcast namespace | Media3 makes this straightforward |
| Theme default | Light | Dark | Dark is more appropriate for listening contexts |
| Min SDK | 21 (Android 5.0) | 26 (Android 8.0) | Simplifies audio focus API, background work, file access |

---

*End of Specification — BeyondPod Revival v5.0.0*
*Source: Analysis of BeyondPod 4.3.321 APK (build 40333) + official documentation + community research*
*Cross-referenced against RevivePod Extended Functional Specification*
*License: MIT*
