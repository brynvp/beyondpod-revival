package mobi.beyondpod.revival.data.repository

import kotlinx.coroutines.flow.Flow
import mobi.beyondpod.revival.data.local.entity.EpisodeEntity
import mobi.beyondpod.revival.data.local.entity.QueueSnapshotEntity

interface EpisodeRepository {
    fun getEpisodesForFeed(feedId: Long): Flow<List<EpisodeEntity>>
    suspend fun getEpisodeById(id: Long): EpisodeEntity?
    fun getQueuedEpisodes(): Flow<List<EpisodeEntity>>
    fun getActiveQueueSnapshot(): Flow<QueueSnapshotEntity?>
    fun searchEpisodes(query: String): Flow<List<EpisodeEntity>>

    // ── My Episodes ───────────────────────────────────────────────────────────
    /**
     * Returns My Episodes in the user-defined order stored in ManualPlaylistEpisodeCrossRef.
     */
    fun getMyEpisodes(): Flow<List<EpisodeEntity>>

    suspend fun addToMyEpisodes(episodeId: Long)
    suspend fun removeFromMyEpisodes(episodeId: Long)

    /**
     * Reorder My Episodes. [orderedEpisodeIds] is the new desired order (all IDs must
     * already be in My Episodes). Updates ManualPlaylistEpisodeCrossRef positions and
     * syncs the active QueueSnapshotEntity if it was generated from My Episodes.
     */
    suspend fun reorderMyEpisodes(orderedEpisodeIds: List<Long>)

    /**
     * Remove all episodes from My Episodes and deactivate the active queue snapshot.
     * Does NOT delete episodes or their files.
     */
    suspend fun clearMyEpisodes()

    // ── Play state ────────────────────────────────────────────────────────────
    suspend fun markPlayed(episodeId: Long)
    suspend fun markUnplayed(episodeId: Long)

    /**
     * Persist the current playback position. Also computes and stores [playedFraction].
     * Call every 5 seconds during playback and on every pause event.
     */
    suspend fun savePlayPosition(episodeId: Long, positionMs: Long)

    suspend fun toggleStar(episodeId: Long)
    suspend fun toggleProtected(episodeId: Long)

    // ── Batch ─────────────────────────────────────────────────────────────────
    suspend fun batchMarkPlayed(episodeIds: List<Long>)
    suspend fun batchMarkUnplayed(episodeIds: List<Long>)
    suspend fun batchAddToMyEpisodes(episodeIds: List<Long>)

    // ── Queue snapshot ────────────────────────────────────────────────────────
    /**
     * Build a frozen queue snapshot from [episodeIds] in the given order.
     * [sourcePlaylistId] identifies which SmartPlaylist generated this (null = manual).
     * Replaces any existing active snapshot atomically.
     */
    suspend fun buildQueueSnapshot(
        sourcePlaylistId: Long?,
        episodeIds: List<Long>
    ): Result<Unit>

    // ── File operations ───────────────────────────────────────────────────────
    /**
     * Delete the local downloaded file for an episode. Sets downloadState = DELETED.
     * Returns failure if the episode is protected (isProtected veto).
     */
    suspend fun deleteEpisodeFile(episodeId: Long): Result<Unit>

    // ── Upsert with multi-key deduplication ──────────────────────────────────
    /**
     * Upsert an episode using the deduplication priority:
     *   Priority 1 — GUID match
     *   Priority 2 — URL match
     *   Priority 3 — Title + Duration heuristic (±5 s tolerance)
     *   Priority 4 — Treat as new episode
     * See §5.1 Episode Identity Strategy.
     */
    suspend fun upsertEpisode(episode: EpisodeEntity): Long
}
