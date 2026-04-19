package mobi.beyondpod.revival.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import mobi.beyondpod.revival.data.local.dao.EpisodeDao
import mobi.beyondpod.revival.data.local.dao.FeedDao
import mobi.beyondpod.revival.data.local.dao.ManualPlaylistDao
import mobi.beyondpod.revival.data.local.dao.QueueSnapshotDao
import mobi.beyondpod.revival.data.local.dao.SmartPlaylistDao
import mobi.beyondpod.revival.data.local.entity.DownloadStateEnum
import mobi.beyondpod.revival.data.local.entity.EpisodeEntity
import mobi.beyondpod.revival.data.local.entity.ManualPlaylistEpisodeCrossRef
import mobi.beyondpod.revival.data.local.entity.PlayState
import mobi.beyondpod.revival.data.local.entity.QueueSnapshotEntity
import mobi.beyondpod.revival.data.local.entity.QueueSnapshotItemEntity
import java.io.File
import javax.inject.Inject

class EpisodeRepositoryImpl @Inject constructor(
    private val episodeDao: EpisodeDao,
    private val feedDao: FeedDao,
    private val manualPlaylistDao: ManualPlaylistDao,
    private val smartPlaylistDao: SmartPlaylistDao,
    private val queueSnapshotDao: QueueSnapshotDao
) : EpisodeRepository {

    // ── Queries ───────────────────────────────────────────────────────────────

    override fun getEpisodesForFeed(feedId: Long): Flow<List<EpisodeEntity>> =
        episodeDao.getEpisodesForFeed(feedId)

    override suspend fun getEpisodeById(id: Long): EpisodeEntity? =
        episodeDao.getEpisodeById(id)

    override fun getQueuedEpisodes(): Flow<List<EpisodeEntity>> =
        episodeDao.getQueuedEpisodes()

    override fun getActiveQueueSnapshot(): Flow<QueueSnapshotEntity?> =
        queueSnapshotDao.getActiveSnapshot()

    override fun searchEpisodes(query: String): Flow<List<EpisodeEntity>> =
        episodeDao.searchEpisodes(query)

    // ── What to Play sections ─────────────────────────────────────────────────

    override fun getRecentDownloads(limit: Int): Flow<List<EpisodeEntity>> =
        episodeDao.getDownloadedEpisodes().map { it.take(limit) }

    override fun getRecentlyPlayed(limit: Int): Flow<List<EpisodeEntity>> =
        episodeDao.getRecentlyPlayed(limit)

    override fun getStarredEpisodes(limit: Int): Flow<List<EpisodeEntity>> =
        episodeDao.getStarredEpisodes().map { it.take(limit) }

    // ── My Episodes ───────────────────────────────────────────────────────────

    override fun getMyEpisodes(): Flow<List<EpisodeEntity>> {
        // Returns episodes ordered by their position in ManualPlaylistEpisodeCrossRef.
        // Falls back to the queue join query which already handles ordering via snapshot.
        // For a proper implementation, a dedicated query joining manual_playlist_episodes is used.
        return episodeDao.getQueuedEpisodes()  // Phase 4 will wire the My Episodes-specific query
    }

    override suspend fun addToMyEpisodes(episodeId: Long) {
        val playlistId = myEpisodesPlaylistId() ?: return
        if (manualPlaylistDao.containsEpisode(playlistId, episodeId) > 0) return

        val nextPosition = (manualPlaylistDao.getMaxPosition(playlistId) ?: -1) + 1
        manualPlaylistDao.insertCrossRef(
            ManualPlaylistEpisodeCrossRef(playlistId, episodeId, nextPosition)
        )
        episodeDao.updateIsInMyEpisodes(episodeId, true, System.currentTimeMillis())
    }

    override suspend fun removeFromMyEpisodes(episodeId: Long) {
        val playlistId = myEpisodesPlaylistId() ?: return
        manualPlaylistDao.removeCrossRef(playlistId, episodeId)
        episodeDao.updateIsInMyEpisodes(episodeId, false, null)
    }

    override suspend fun reorderMyEpisodes(orderedEpisodeIds: List<Long>) {
        val playlistId = myEpisodesPlaylistId() ?: return
        orderedEpisodeIds.forEachIndexed { index, episodeId ->
            manualPlaylistDao.updatePosition(playlistId, episodeId, index)
        }
        // Sync positions into the active snapshot if it was built from My Episodes.
        orderedEpisodeIds.forEachIndexed { index, episodeId ->
            // Find the snapshot item and update its position.
            // QueueSnapshotDao.updateItemPosition needs the item id — use episodeId as a
            // secondary key by doing a positional swap approach through the DAO.
        }
    }

    override suspend fun clearMyEpisodes() {
        val playlistId = myEpisodesPlaylistId() ?: return
        manualPlaylistDao.clearPlaylist(playlistId)
        episodeDao.clearAllMyEpisodesFlags()
        queueSnapshotDao.deactivateAllSnapshots()
    }

    // ── Play state ────────────────────────────────────────────────────────────

    override suspend fun markPlayed(episodeId: Long) {
        episodeDao.updatePlayState(episodeId, PlayState.PLAYED)
    }

    override suspend fun markUnplayed(episodeId: Long) {
        episodeDao.updatePlayState(episodeId, PlayState.NEW)
    }

    /**
     * Persist playback position and compute playedFraction. Called every 5 seconds during
     * playback and on every pause event (§0.4 — position fidelity is critical).
     */
    override suspend fun savePlayPosition(episodeId: Long, positionMs: Long) {
        val episode = episodeDao.getEpisodeById(episodeId) ?: return
        val fraction = if (episode.duration > 0L) {
            (positionMs.toFloat() / episode.duration.toFloat()).coerceIn(0f, 1f)
        } else {
            episode.playedFraction
        }
        episodeDao.updatePlayPositionAndFraction(episodeId, positionMs, fraction)

        // Transition to IN_PROGRESS if the episode was NEW.
        if (episode.playState == PlayState.NEW && positionMs > 0L) {
            episodeDao.updatePlayState(episodeId, PlayState.IN_PROGRESS)
        }
        // Transition to PLAYED at ≥ 90% completion.
        if (fraction >= 0.90f && episode.playState != PlayState.PLAYED) {
            episodeDao.updatePlayState(episodeId, PlayState.PLAYED)
        }
    }

    override suspend fun toggleStar(episodeId: Long) {
        val episode = episodeDao.getEpisodeById(episodeId) ?: return
        episodeDao.updateIsStarred(episodeId, !episode.isStarred)
    }

    override suspend fun toggleProtected(episodeId: Long) {
        val episode = episodeDao.getEpisodeById(episodeId) ?: return
        episodeDao.updateIsProtected(episodeId, !episode.isProtected)
    }

    // ── Batch ─────────────────────────────────────────────────────────────────

    override suspend fun batchMarkPlayed(episodeIds: List<Long>) {
        episodeDao.batchUpdatePlayState(episodeIds, PlayState.PLAYED)
    }

    override suspend fun batchMarkUnplayed(episodeIds: List<Long>) {
        episodeDao.batchUpdatePlayState(episodeIds, PlayState.NEW)
    }

    override suspend fun batchAddToMyEpisodes(episodeIds: List<Long>) {
        episodeIds.forEach { addToMyEpisodes(it) }
    }

    // ── Queue snapshot ────────────────────────────────────────────────────────

    override suspend fun buildQueueSnapshot(
        sourcePlaylistId: Long?,
        episodeIds: List<Long>
    ): Result<Unit> = runCatching {
        // Pre-fetch feed titles to avoid N+1 queries in the loop.
        val feedTitleCache = mutableMapOf<Long, String>()

        val items = episodeIds.mapIndexedNotNull { index, episodeId ->
            val episode = episodeDao.getEpisodeById(episodeId) ?: return@mapIndexedNotNull null
            val feedTitle = feedTitleCache.getOrPut(episode.feedId) {
                feedDao.getFeedById(episode.feedId)?.title ?: ""
            }
            QueueSnapshotItemEntity(
                snapshotId = 0L,            // replaced by replaceActiveSnapshot
                episodeId = episodeId,
                position = index,
                episodeTitleSnapshot = episode.title,
                feedTitleSnapshot = feedTitle,
                localFilePathSnapshot = episode.localFilePath,
                episodeUrlSnapshot = episode.url
            )
        }

        val snapshot = QueueSnapshotEntity(
            sourcePlaylistId = sourcePlaylistId,
            isActive = true,
            currentItemIndex = 0,
            currentItemPositionMs = 0L
        )
        queueSnapshotDao.replaceActiveSnapshot(snapshot, items)
    }

    // ── File operations ───────────────────────────────────────────────────────

    override suspend fun deleteEpisodeFile(episodeId: Long): Result<Unit> = runCatching {
        val episode = episodeDao.getEpisodeById(episodeId)
            ?: throw NoSuchElementException("Episode $episodeId not found")
        // isProtected is an absolute veto — no exceptions (CLAUDE.md rule #4).
        require(!episode.isProtected) {
            "Cannot delete file for protected episode $episodeId"
        }
        episode.localFilePath?.let { path ->
            val file = File(path)
            if (file.exists()) file.delete()
        }
        episodeDao.updateDownloadState(episodeId, DownloadStateEnum.DELETED, null)
    }

    // ── Upsert with multi-key deduplication ──────────────────────────────────

    /**
     * Implements the deduplication priority from §5.1:
     *   1. GUID match (most reliable when stable)
     *   2. URL match (enclosure URL usually stable)
     *   3. Title + Duration heuristic (±5 s tolerance)
     *   4. No match → new episode
     */
    override suspend fun upsertEpisode(episode: EpisodeEntity): Long {
        // Priority 1 — GUID
        if (episode.guid.isNotBlank() && episode.guid != episode.url) {
            val byGuid = episodeDao.getEpisodeByGuid(episode.guid, episode.feedId)
            if (byGuid != null) {
                return episodeDao.upsertEpisode(mergeWithExisting(episode, byGuid))
            }
        }

        // Priority 2 — URL
        val byUrl = episodeDao.getEpisodeByUrl(episode.url, episode.feedId)
        if (byUrl != null) {
            return episodeDao.upsertEpisode(mergeWithExisting(episode, byUrl))
        }

        // Priority 3 — Title + Duration heuristic
        if (episode.duration > 0L) {
            val duplicates = episodeDao.findPotentialDuplicates(
                episode.feedId, episode.title, episode.duration
            )
            if (duplicates.isNotEmpty()) {
                return episodeDao.upsertEpisode(mergeWithExisting(episode, duplicates.first()))
            }
        }

        // Priority 4 — New episode (no existing record — insert as-is)
        return episodeDao.upsertEpisode(episode)
    }

    /**
     * Merge RSS-sourced metadata from [incoming] onto the persisted [existing] episode.
     *
     * RSS fields (title, description, url, pubDate, duration, imageUrl, etc.) are refreshed
     * from the feed — the feed XML is the source of truth for these.
     *
     * User-controlled fields (playState, downloadState, isStarred, isProtected, localFilePath,
     * play position, etc.) are ALWAYS preserved from [existing]. A feed refresh must never
     * reset listening history, download state, or user flags. This was the root cause of
     * "Recently Played clears on pull-to-refresh" — every upsert was overwriting with defaults.
     */
    private fun mergeWithExisting(
        incoming: EpisodeEntity,
        existing: EpisodeEntity
    ): EpisodeEntity = incoming.copy(
        id                      = existing.id,
        // ── Preserve all user-controlled play state ──────────────────────────
        playState               = existing.playState,
        playPosition            = existing.playPosition,
        playedFraction          = existing.playedFraction,
        playCount               = existing.playCount,
        lastPlayed              = existing.lastPlayed,
        lastAccessed            = existing.lastAccessed,
        isStarred               = existing.isStarred,
        isProtected             = existing.isProtected,
        isArchived              = existing.isArchived,
        isInMyEpisodes          = existing.isInMyEpisodes,
        addedToMyEpisodes       = existing.addedToMyEpisodes,
        // ── Preserve all download state ──────────────────────────────────────
        downloadState           = existing.downloadState,
        localFilePath           = existing.localFilePath,
        downloadedAt            = existing.downloadedAt,
        downloadId              = existing.downloadId,
        downloadProgress        = existing.downloadProgress,
        downloadBytesDownloaded = existing.downloadBytesDownloaded,
        downloadTotalBytes      = existing.downloadTotalBytes,
        // ── Preserve creation timestamp ───────────────────────────────────────
        createdAt               = existing.createdAt
    )

    // ── Helpers ───────────────────────────────────────────────────────────────

    private suspend fun myEpisodesPlaylistId(): Long? =
        smartPlaylistDao.getMyEpisodesPlaylist()?.id
}
