package mobi.beyondpod.revival.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import mobi.beyondpod.revival.data.local.entity.FeedCategoryCrossRef
import mobi.beyondpod.revival.data.local.entity.FeedEntity

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
