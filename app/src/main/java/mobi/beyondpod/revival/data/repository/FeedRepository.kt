package mobi.beyondpod.revival.data.repository

import kotlinx.coroutines.flow.Flow
import mobi.beyondpod.revival.data.local.entity.FeedEntity

interface FeedRepository {
    fun getAllFeeds(): Flow<List<FeedEntity>>
    fun getFeedsByCategory(categoryId: Long): Flow<List<FeedEntity>>
    suspend fun getFeedById(id: Long): FeedEntity?

    /**
     * Create a minimal feed record for [url]. Full RSS parsing happens in Phase 3
     * (FeedUpdateWorker). Returns the persisted FeedEntity on success.
     */
    suspend fun subscribeToFeed(url: String): Result<FeedEntity>

    suspend fun updateFeedProperties(feed: FeedEntity): Result<Unit>

    /**
     * Delete a feed. If [deleteDownloads] is true, all local episode files are
     * deleted from disk before removing the DB records (cascades via FK).
     */
    suspend fun deleteFeed(id: Long, deleteDownloads: Boolean)

    /**
     * Re-fetch the RSS feed and upsert episodes.
     * [markFailure] controls whether a fetch error is persisted to [FeedEntity.lastUpdateFailed].
     * Pass true (default) for manual pull-to-refresh so the user sees the error indicator.
     * Pass false for background worker runs — transient failures should be silent.
     */
    suspend fun refreshFeed(id: Long, markFailure: Boolean = true): Result<Unit>

    /** Refresh all feeds sequentially. Stub until Phase 3. */
    suspend fun refreshAllFeeds(): Result<Unit>

    /**
     * Move a feed into a category. Pass [categoryId] = null to move to Uncategorized.
     * [isPrimary] determines whether this becomes the primary or secondary category slot.
     */
    suspend fun moveFeedToCategory(feedId: Long, categoryId: Long?, isPrimary: Boolean = true)

    /**
     * Reset any stale lastUpdateFailed=true flags left by the pre-fix background worker.
     * Safe to call on every app launch — no-ops if no stale flags exist.
     */
    suspend fun clearStaleUpdateFailedFlags()

    /**
     * Parse an OPML string and subscribe to all feeds found. Returns the count of
     * successfully imported feeds. Stub — full SAX parsing is Phase 7.
     */
    suspend fun importFromOpml(opmlContent: String): Result<Int>

    /** Serialize all feed subscriptions to an OPML XML string. */
    suspend fun exportToOpml(): Result<String>

    /**
     * Create a virtual feed backed by a local folder (SAF content:// tree URI).
     * The folder will be scanned immediately after creation. Returns the new [FeedEntity].
     */
    suspend fun addFolderFeed(folderUri: String, displayName: String): Result<FeedEntity>

    /**
     * Scan a virtual folder feed for new audio files and upsert an [EpisodeEntity]
     * for each one not already in the DB. Returns count of newly added episodes.
     */
    suspend fun scanFolderFeed(feedId: Long): Result<Int>
}
