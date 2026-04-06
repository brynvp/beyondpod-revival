package mobi.beyondpod.revival.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import mobi.beyondpod.revival.data.local.entity.DownloadStateEnum
import mobi.beyondpod.revival.data.local.entity.EpisodeEntity
import mobi.beyondpod.revival.data.local.entity.PlayState

@Dao
interface EpisodeDao {
    @Query("SELECT * FROM episodes WHERE feedId = :feedId ORDER BY pubDate DESC")
    fun getEpisodesForFeed(feedId: Long): Flow<List<EpisodeEntity>>

    @Query("SELECT * FROM episodes WHERE feedId = :feedId ORDER BY pubDate DESC")
    suspend fun getEpisodesForFeedList(feedId: Long): List<EpisodeEntity>

    @Query("""
        SELECT * FROM episodes
        WHERE feedId = :feedId AND playState != 'PLAYED'
        ORDER BY pubDate DESC
        LIMIT :limit
    """)
    fun getUnplayedEpisodesForFeed(feedId: Long, limit: Int = 50): Flow<List<EpisodeEntity>>

    @Query("SELECT * FROM episodes ORDER BY pubDate DESC")
    fun getAllEpisodes(): Flow<List<EpisodeEntity>>

    @Query("SELECT * FROM episodes WHERE id = :episodeId")
    suspend fun getEpisodeById(episodeId: Long): EpisodeEntity?

    @Query("SELECT * FROM episodes WHERE guid = :guid AND feedId = :feedId LIMIT 1")
    suspend fun getEpisodeByGuid(guid: String, feedId: Long): EpisodeEntity?

    @Query("SELECT * FROM episodes WHERE url = :url AND feedId = :feedId LIMIT 1")
    suspend fun getEpisodeByUrl(url: String, feedId: Long): EpisodeEntity?

    @Query("SELECT * FROM episodes WHERE localFilePath = :path LIMIT 1")
    suspend fun getEpisodeByLocalPath(path: String): EpisodeEntity?

    // Widget query — recent NEW or IN_PROGRESS episodes, newest first
    @Query("""
        SELECT * FROM episodes
        WHERE playState IN ('NEW', 'IN_PROGRESS')
        ORDER BY pubDate DESC
        LIMIT :limit
    """)
    suspend fun getRecentUnplayedForWidget(limit: Int): List<EpisodeEntity>

    @Upsert
    suspend fun upsertEpisode(episode: EpisodeEntity): Long

    @Query("UPDATE episodes SET playState = :state WHERE id = :episodeId")
    suspend fun updatePlayState(episodeId: Long, state: PlayState)

    @Query("UPDATE episodes SET playPosition = :position WHERE id = :episodeId")
    suspend fun updatePlayPosition(episodeId: Long, position: Long)

    @Query("UPDATE episodes SET playPosition = :position, playedFraction = :fraction WHERE id = :episodeId")
    suspend fun updatePlayPositionAndFraction(episodeId: Long, position: Long, fraction: Float)

    @Query("UPDATE episodes SET isStarred = :starred WHERE id = :episodeId")
    suspend fun updateIsStarred(episodeId: Long, starred: Boolean)

    @Query("UPDATE episodes SET isProtected = :protected WHERE id = :episodeId")
    suspend fun updateIsProtected(episodeId: Long, protected: Boolean)

    @Query("UPDATE episodes SET isInMyEpisodes = :inMyEpisodes, addedToMyEpisodes = :addedAt WHERE id = :episodeId")
    suspend fun updateIsInMyEpisodes(episodeId: Long, inMyEpisodes: Boolean, addedAt: Long?)

    @Query("UPDATE episodes SET isInMyEpisodes = 0, addedToMyEpisodes = NULL WHERE isInMyEpisodes = 1")
    suspend fun clearAllMyEpisodesFlags()

    // NOTE: No updateQueueState() or isInQueue/queuePosition operations here.
    // All queue read/write goes through QueueSnapshotDao. (QA finding #1.)

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

    // Full-text search across title and description
    @Query("""
        SELECT * FROM episodes
        WHERE title LIKE '%' || :query || '%'
        OR description LIKE '%' || :query || '%'
        ORDER BY pubDate DESC
    """)
    fun searchEpisodes(query: String): Flow<List<EpisodeEntity>>

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

    // Returns played downloaded non-protected episodes eligible for cleanup, oldest first.
    // isProtected = 0 is enforced here; the caller must NOT delete protected episodes.
    @Query("""
        SELECT * FROM episodes
        WHERE feedId = :feedId
        AND downloadState = 'DOWNLOADED'
        AND isProtected = 0
        AND playState = 'PLAYED'
        ORDER BY pubDate ASC
    """)
    suspend fun getPlayedDownloadedForCleanup(feedId: Long): List<EpisodeEntity>

    // Episodes available for auto-download (not yet downloaded), newest first
    @Query("""
        SELECT * FROM episodes
        WHERE feedId = :feedId AND downloadState = 'NOT_DOWNLOADED'
        ORDER BY pubDate DESC LIMIT :count
    """)
    suspend fun getNotDownloadedNewest(feedId: Long, count: Int): List<EpisodeEntity>

    // Episodes available for auto-download in serial order, oldest first
    @Query("""
        SELECT * FROM episodes
        WHERE feedId = :feedId AND downloadState = 'NOT_DOWNLOADED'
        ORDER BY pubDate ASC LIMIT :count
    """)
    suspend fun getNotDownloadedOldest(feedId: Long, count: Int): List<EpisodeEntity>

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

    // Duplicate detection: find episodes with same feedId + title + approximately same duration
    @Query("""
        SELECT * FROM episodes
        WHERE feedId = :feedId AND title = :title
        AND ABS(duration - :durationMs) < 5000
        LIMIT 2
    """)
    suspend fun findPotentialDuplicates(feedId: Long, title: String, durationMs: Long): List<EpisodeEntity>
}
