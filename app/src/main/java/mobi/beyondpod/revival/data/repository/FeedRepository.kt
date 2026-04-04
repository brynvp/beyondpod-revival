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

    /** Re-fetch the RSS feed and upsert episodes. Stub until Phase 3. */
    suspend fun refreshFeed(id: Long): Result<Unit>

    /** Refresh all feeds sequentially. Stub until Phase 3. */
    suspend fun refreshAllFeeds(): Result<Unit>

    /**
     * Move a feed into a category. Pass [categoryId] = null to move to Uncategorized.
     * [isPrimary] determines whether this becomes the primary or secondary category slot.
     */
    suspend fun moveFeedToCategory(feedId: Long, categoryId: Long?, isPrimary: Boolean = true)

    /**
     * Parse an OPML string and subscribe to all feeds found. Returns the count of
     * successfully imported feeds. Stub — full SAX parsing is Phase 7.
     */
    suspend fun importFromOpml(opmlContent: String): Result<Int>

    /** Serialize all feed subscriptions to an OPML XML string. */
    suspend fun exportToOpml(): Result<String>
}
