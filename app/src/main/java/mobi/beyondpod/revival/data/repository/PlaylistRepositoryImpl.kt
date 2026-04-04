package mobi.beyondpod.revival.data.repository

import com.google.gson.Gson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import mobi.beyondpod.revival.data.local.dao.EpisodeDao
import mobi.beyondpod.revival.data.local.dao.FeedDao
import mobi.beyondpod.revival.data.local.dao.SmartPlaylistDao
import mobi.beyondpod.revival.data.local.entity.BlockEpisodeOrder
import mobi.beyondpod.revival.data.local.entity.BlockSource
import mobi.beyondpod.revival.data.local.entity.DownloadStateEnum
import mobi.beyondpod.revival.data.local.entity.EpisodeEntity
import mobi.beyondpod.revival.data.local.entity.EpisodeSortOrder
import mobi.beyondpod.revival.data.local.entity.LogicalConnector
import mobi.beyondpod.revival.data.local.entity.OnEmptyAction
import mobi.beyondpod.revival.data.local.entity.PlayState
import mobi.beyondpod.revival.data.local.entity.PlaylistRuleMode
import mobi.beyondpod.revival.data.local.entity.RuleField
import mobi.beyondpod.revival.data.local.entity.RuleOperator
import mobi.beyondpod.revival.data.local.entity.SmartPlaylistBlock
import mobi.beyondpod.revival.data.local.entity.SmartPlaylistEntity
import mobi.beyondpod.revival.data.local.entity.SmartPlaylistRule
import java.util.Calendar
import java.util.TimeZone
import javax.inject.Inject

class PlaylistRepositoryImpl @Inject constructor(
    private val smartPlaylistDao: SmartPlaylistDao,
    private val episodeDao: EpisodeDao,
    private val feedDao: FeedDao
) : PlaylistRepository {

    private val gson = Gson()

    override fun getAllPlaylists(): Flow<List<SmartPlaylistEntity>> =
        smartPlaylistDao.getAllPlaylists()

    override suspend fun getPlaylistById(id: Long): SmartPlaylistEntity? =
        smartPlaylistDao.getPlaylistById(id)

    override suspend fun getMyEpisodesPlaylist(): SmartPlaylistEntity? =
        smartPlaylistDao.getMyEpisodesPlaylist()

    override suspend fun createPlaylist(playlist: SmartPlaylistEntity): Long =
        smartPlaylistDao.upsertPlaylist(playlist)

    override suspend fun updatePlaylist(playlist: SmartPlaylistEntity) {
        smartPlaylistDao.upsertPlaylist(playlist)
    }

    /**
     * Delete a playlist. My Episodes (isDefault = true) is indestructible — §7.5 rule #3.
     */
    override suspend fun deletePlaylist(playlist: SmartPlaylistEntity) {
        check(!playlist.isDefault) {
            "My Episodes (isDefault = true) cannot be deleted — it is indestructible (§7.5)"
        }
        smartPlaylistDao.deletePlaylist(playlist)
    }

    /**
     * Evaluate the playlist rules against the live episode database and return a Flow
     * that re-emits whenever episode data changes.
     *
     * SEQUENTIAL_BLOCKS mode: each block contributes N episodes from a source in a given order.
     * FILTER_RULES mode: all rules are applied as predicates against the full episode pool.
     */
    override fun evaluateSmartPlaylist(playlist: SmartPlaylistEntity): Flow<List<EpisodeEntity>> {
        return episodeDao.getAllEpisodes().map { allEpisodes ->
            when (playlist.ruleMode) {
                PlaylistRuleMode.SEQUENTIAL_BLOCKS -> evaluateSequentialBlocks(playlist, allEpisodes)
                PlaylistRuleMode.FILTER_RULES -> evaluateFilterRules(playlist, allEpisodes)
            }
        }
    }

    /**
     * Seed the five default SmartPlaylists on first run. No-op if My Episodes already exists.
     *
     * Default playlists (§7.4):
     *   1. My Episodes   — isDefault=true, IS_IN_MY_EPISODES IS true
     *   2. New Episodes  — PLAY_STATE IS NEW
     *   3. In Progress   — PLAY_STATE IS IN_PROGRESS
     *   4. Downloaded    — IS_DOWNLOADED IS true
     *   5. Starred       — IS_STARRED IS true
     */
    override suspend fun seedDefaultPlaylistsIfNeeded() {
        if (smartPlaylistDao.getMyEpisodesPlaylist() != null) return

        fun rules(vararg rules: SmartPlaylistRule): String = gson.toJson(rules)

        smartPlaylistDao.upsertPlaylist(
            SmartPlaylistEntity(
                name = "My Episodes",
                sortOrder = 0,
                isDefault = true,
                ruleMode = PlaylistRuleMode.FILTER_RULES,
                rulesJson = rules(SmartPlaylistRule(RuleField.IS_IN_MY_EPISODES, RuleOperator.IS, "true")),
                episodeSortOrder = EpisodeSortOrder.PUB_DATE_ASC
            )
        )
        smartPlaylistDao.upsertPlaylist(
            SmartPlaylistEntity(
                name = "New Episodes",
                sortOrder = 1,
                isDefault = true,
                ruleMode = PlaylistRuleMode.FILTER_RULES,
                rulesJson = rules(SmartPlaylistRule(RuleField.PLAY_STATE, RuleOperator.IS, "NEW")),
                episodeSortOrder = EpisodeSortOrder.PUB_DATE_DESC
            )
        )
        smartPlaylistDao.upsertPlaylist(
            SmartPlaylistEntity(
                name = "In Progress",
                sortOrder = 2,
                isDefault = true,
                ruleMode = PlaylistRuleMode.FILTER_RULES,
                rulesJson = rules(SmartPlaylistRule(RuleField.PLAY_STATE, RuleOperator.IS, "IN_PROGRESS")),
                episodeSortOrder = EpisodeSortOrder.PUB_DATE_DESC
            )
        )
        smartPlaylistDao.upsertPlaylist(
            SmartPlaylistEntity(
                name = "Downloaded",
                sortOrder = 3,
                isDefault = true,
                ruleMode = PlaylistRuleMode.FILTER_RULES,
                rulesJson = rules(SmartPlaylistRule(RuleField.IS_DOWNLOADED, RuleOperator.IS, "true")),
                episodeSortOrder = EpisodeSortOrder.DOWNLOAD_DATE_DESC
            )
        )
        smartPlaylistDao.upsertPlaylist(
            SmartPlaylistEntity(
                name = "Starred",
                sortOrder = 4,
                isDefault = true,
                ruleMode = PlaylistRuleMode.FILTER_RULES,
                rulesJson = rules(SmartPlaylistRule(RuleField.IS_STARRED, RuleOperator.IS, "true")),
                episodeSortOrder = EpisodeSortOrder.PUB_DATE_DESC
            )
        )
    }

    // ── Sequential Blocks evaluation ──────────────────────────────────────────

    private suspend fun evaluateSequentialBlocks(
        playlist: SmartPlaylistEntity,
        allEpisodes: List<EpisodeEntity>
    ): List<EpisodeEntity> {
        val blocks = runCatching {
            gson.fromJson(playlist.rulesJson, Array<SmartPlaylistBlock>::class.java).toList()
        }.getOrElse { emptyList() }

        val result = mutableListOf<EpisodeEntity>()
        val usedIds = mutableSetOf<Long>()

        for (block in blocks) {
            val blockEpisodes = getEpisodesForBlock(block, allEpisodes, usedIds)
            result.addAll(blockEpisodes)
            usedIds.addAll(blockEpisodes.map { it.id })
        }

        return if (result.isEmpty() && playlist.onEmptyAction == OnEmptyAction.FALLBACK_ALL_UNPLAYED) {
            allEpisodes.filter { it.playState == PlayState.NEW }.sortedByDescending { it.pubDate }
        } else {
            result
        }
    }

    private suspend fun getEpisodesForBlock(
        block: SmartPlaylistBlock,
        allEpisodes: List<EpisodeEntity>,
        excludeIds: Set<Long>
    ): List<EpisodeEntity> {
        val feedIds: Set<Long>? = when (block.source) {
            BlockSource.ALL_FEEDS -> null
            BlockSource.FEED -> block.sourceId?.let { setOf(it) }
            BlockSource.CATEGORY -> block.sourceId
                ?.let { feedDao.getCategoryFeedIds(it).toSet() }
        }

        val candidates = allEpisodes.filter { ep ->
            ep.id !in excludeIds &&
            (feedIds == null || ep.feedId in feedIds) &&
            (!block.onlyDownloaded || ep.downloadState == DownloadStateEnum.DOWNLOADED)
        }

        val ordered = when (block.order) {
            BlockEpisodeOrder.NEWEST -> candidates.sortedByDescending { it.pubDate }
            BlockEpisodeOrder.OLDEST -> candidates.sortedBy { it.pubDate }
            BlockEpisodeOrder.RANDOM -> candidates.shuffled()
        }

        return ordered.take(block.count)
    }

    // ── Filter Rules evaluation ───────────────────────────────────────────────

    private fun evaluateFilterRules(
        playlist: SmartPlaylistEntity,
        allEpisodes: List<EpisodeEntity>
    ): List<EpisodeEntity> {
        val rules = runCatching {
            gson.fromJson(playlist.rulesJson, Array<SmartPlaylistRule>::class.java).toList()
        }.getOrElse { emptyList() }

        var result = if (rules.isEmpty()) {
            emptyList()
        } else {
            allEpisodes.filter { ep -> evaluateRuleChain(ep, rules) }
        }

        result = applySortOrder(result, playlist.episodeSortOrder)
        if (playlist.maxItems > 0) result = result.take(playlist.maxItems)

        return if (result.isEmpty() && playlist.onEmptyAction == OnEmptyAction.FALLBACK_ALL_UNPLAYED) {
            allEpisodes.filter { it.playState == PlayState.NEW }.sortedByDescending { it.pubDate }
        } else {
            result
        }
    }

    /** Evaluate [rules] against [episode] with left-to-right AND/OR short-circuit logic. */
    private fun evaluateRuleChain(episode: EpisodeEntity, rules: List<SmartPlaylistRule>): Boolean {
        var result = evaluateSingleRule(episode, rules[0])
        for (i in 1 until rules.size) {
            val rule = rules[i]
            result = when (rule.logicalConnector) {
                LogicalConnector.AND -> result && evaluateSingleRule(episode, rule)
                LogicalConnector.OR -> result || evaluateSingleRule(episode, rule)
            }
        }
        return result
    }

    private fun evaluateSingleRule(episode: EpisodeEntity, rule: SmartPlaylistRule): Boolean {
        return when (rule.field) {
            RuleField.PLAY_STATE -> when (rule.operator) {
                RuleOperator.IS -> episode.playState.name == rule.value
                RuleOperator.IS_NOT -> episode.playState.name != rule.value
                else -> false
            }
            RuleField.IS_STARRED -> when (rule.operator) {
                RuleOperator.IS -> episode.isStarred == rule.value.toBoolean()
                else -> false
            }
            RuleField.IS_DOWNLOADED -> when (rule.operator) {
                RuleOperator.IS -> (episode.downloadState == DownloadStateEnum.DOWNLOADED) == rule.value.toBoolean()
                else -> false
            }
            RuleField.IS_PROTECTED -> when (rule.operator) {
                RuleOperator.IS -> episode.isProtected == rule.value.toBoolean()
                else -> false
            }
            RuleField.FEED_ID -> when (rule.operator) {
                RuleOperator.IS -> episode.feedId == rule.value.toLongOrNull()
                RuleOperator.IS_NOT -> episode.feedId != rule.value.toLongOrNull()
                else -> false
            }
            RuleField.CATEGORY_ID -> false // Requires category lookup; defer to Phase 6 optimisation
            RuleField.PUB_DATE -> {
                val epochMs = parseDateToEpochMillis(rule.value) ?: return false
                when (rule.operator) {
                    RuleOperator.IS_BEFORE -> episode.pubDate < epochMs
                    RuleOperator.IS_AFTER -> episode.pubDate > epochMs
                    else -> false
                }
            }
            RuleField.DURATION -> {
                val ms = (rule.value.toLongOrNull() ?: return false) * 1000L
                when (rule.operator) {
                    RuleOperator.GREATER_THAN -> episode.duration > ms
                    RuleOperator.LESS_THAN -> episode.duration < ms
                    else -> false
                }
            }
            RuleField.IS_IN_MY_EPISODES -> when (rule.operator) {
                RuleOperator.IS -> episode.isInMyEpisodes == rule.value.toBoolean()
                else -> false
            }
            RuleField.TITLE_CONTAINS -> when (rule.operator) {
                RuleOperator.CONTAINS -> episode.title.contains(rule.value, ignoreCase = true)
                RuleOperator.DOES_NOT_CONTAIN -> !episode.title.contains(rule.value, ignoreCase = true)
                else -> false
            }
            RuleField.FILE_TYPE -> {
                val isAudio = episode.mimeType.startsWith("audio", ignoreCase = true)
                when (rule.operator) {
                    RuleOperator.IS -> when (rule.value) {
                        "audio" -> isAudio
                        "video" -> !isAudio
                        else -> false
                    }
                    else -> false
                }
            }
            RuleField.PLAYED_FRACTION -> {
                val threshold = rule.value.toFloatOrNull() ?: return false
                when (rule.operator) {
                    RuleOperator.GREATER_THAN -> episode.playedFraction > threshold
                    RuleOperator.LESS_THAN -> episode.playedFraction < threshold
                    else -> false
                }
            }
        }
    }

    private fun applySortOrder(
        episodes: List<EpisodeEntity>,
        sortOrder: EpisodeSortOrder
    ): List<EpisodeEntity> = when (sortOrder) {
        EpisodeSortOrder.PUB_DATE_DESC -> episodes.sortedByDescending { it.pubDate }
        EpisodeSortOrder.PUB_DATE_ASC -> episodes.sortedBy { it.pubDate }
        EpisodeSortOrder.DURATION_DESC -> episodes.sortedByDescending { it.duration }
        EpisodeSortOrder.DURATION_ASC -> episodes.sortedBy { it.duration }
        EpisodeSortOrder.FEED_TITLE_ASC -> episodes.sortedBy { it.feedId }
        EpisodeSortOrder.FEED_TITLE_DESC -> episodes.sortedByDescending { it.feedId }
        EpisodeSortOrder.TITLE_ASC -> episodes.sortedBy { it.title }
        EpisodeSortOrder.TITLE_DESC -> episodes.sortedByDescending { it.title }
        EpisodeSortOrder.DOWNLOAD_DATE_DESC -> episodes.sortedByDescending { it.downloadedAt ?: 0L }
        EpisodeSortOrder.PLAYED_PORTION_ASC -> episodes.sortedByDescending { it.playedFraction }
        EpisodeSortOrder.FILE_NAME_ASC -> episodes.sortedBy { it.localFilePath ?: it.title }
        EpisodeSortOrder.MANUAL -> episodes
    }

    /** Parse "YYYY-MM-DD" to epoch milliseconds at start of day UTC. */
    private fun parseDateToEpochMillis(dateStr: String): Long? = runCatching {
        val parts = dateStr.split("-")
        require(parts.size == 3)
        Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            set(parts[0].toInt(), parts[1].toInt() - 1, parts[2].toInt(), 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }.getOrNull()
}
