package mobi.beyondpod.revival.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "smart_playlists")
data class SmartPlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val sortOrder: Int = 0,
    val isDefault: Boolean = false,           // The "My Episodes" default playlist

    // IMPORTANT — legacy import note:
    // In original BeyondPod's `smartplaylist` table, the `categoryId` column stores the
    // CATEGORY NAME as a string, NOT an integer ID. During legacy .bpbak import, resolve
    // category name → Revival CategoryEntity.id before populating SmartPlaylistBlock.sourceId.

    // Rules JSON. Two modes:
    //   SEQUENTIAL_BLOCKS — PRIMARY / LEGACY-COMPATIBLE MODE (original BeyondPod model).
    //   FILTER_RULES — ADVANCED / REVIVAL-NATIVE MODE (predicate-based).
    // UI labeling: "Standard" (SEQUENTIAL_BLOCKS) and "Advanced" (FILTER_RULES).
    // New playlists default to SEQUENTIAL_BLOCKS for backward-compatible mental model.
    val ruleMode: PlaylistRuleMode = PlaylistRuleMode.SEQUENTIAL_BLOCKS,
    val rulesJson: String = "[]",

    val maxItems: Int = 0,                    // 0 = unlimited
    val episodeSortOrder: EpisodeSortOrder = EpisodeSortOrder.PUB_DATE_DESC,
    val autoPlay: Boolean = false,
    val continueOnComplete: Boolean = true,   // Auto-advance to next episode
    val onEmptyAction: OnEmptyAction = OnEmptyAction.DO_NOTHING,
    val iconResName: String? = null,

    // Per-playlist playback overrides
    val playbackSpeedOverride: Float? = null,
    val volumeBoostOverride: Int? = null,     // 0 = no boost, 1–10
    val skipSilenceOverride: Boolean? = null
)

/**
 * SEQUENTIAL_BLOCKS = "Standard" mode. Original BeyondPod's model. Primary/legacy-compatible.
 * FILTER_RULES = "Advanced" mode. Revival extension. Predicate-based episode pool filtering.
 */
enum class PlaylistRuleMode { SEQUENTIAL_BLOCKS, FILTER_RULES }

/**
 * What to do when SmartPlay rule evaluation returns zero episodes.
 */
enum class OnEmptyAction {
    DO_NOTHING,
    FALLBACK_ALL_UNPLAYED
}

enum class EpisodeSortOrder {
    PUB_DATE_DESC, PUB_DATE_ASC,
    DURATION_DESC, DURATION_ASC,
    FEED_TITLE_ASC, FEED_TITLE_DESC,
    TITLE_ASC, TITLE_DESC,
    DOWNLOAD_DATE_DESC,
    PLAYED_PORTION_ASC,  // Episodes closest to completion first
    FILE_NAME_ASC,
    MANUAL               // User-defined order in ManualPlaylistEpisodeCrossRef
}

// ── Sequential Block model (original BeyondPod SmartPlay) ──────────────────

/**
 * One block in a sequential SmartPlay programme.
 * Example: [
 *   SmartPlaylistBlock(count=2, source=CATEGORY, sourceId=3, order=NEWEST),
 *   SmartPlaylistBlock(count=1, source=FEED, sourceId=12, order=OLDEST),
 *   SmartPlaylistBlock(count=3, source=ALL_FEEDS, sourceId=null, order=RANDOM),
 * ]
 */
data class SmartPlaylistBlock(
    val count: Int,                           // Number of episodes to get
    val source: BlockSource,
    val sourceId: Long? = null,               // feedId or categoryId; null if source=ALL_FEEDS
    val order: BlockEpisodeOrder,
    val onlyDownloaded: Boolean = false
)

enum class BlockSource { ALL_FEEDS, FEED, CATEGORY }
enum class BlockEpisodeOrder { NEWEST, OLDEST, RANDOM }

// ── Filter Rules model (Revival-native, advanced mode) ────────────────────

data class SmartPlaylistRule(
    val field: RuleField,
    val operator: RuleOperator,
    val value: String,
    val logicalConnector: LogicalConnector = LogicalConnector.AND
)

enum class RuleField {
    PLAY_STATE,
    IS_STARRED,
    IS_DOWNLOADED,
    IS_PROTECTED,
    FEED_ID,
    CATEGORY_ID,
    PUB_DATE,
    DURATION,
    IS_IN_MY_EPISODES,
    TITLE_CONTAINS,
    FILE_TYPE,
    PLAYED_FRACTION
}

enum class RuleOperator {
    IS, IS_NOT,
    IS_BEFORE, IS_AFTER,
    CONTAINS, DOES_NOT_CONTAIN,
    GREATER_THAN, LESS_THAN
}

enum class LogicalConnector { AND, OR }
