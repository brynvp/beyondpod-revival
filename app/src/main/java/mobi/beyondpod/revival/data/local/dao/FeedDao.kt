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

    @Query("SELECT * FROM feeds WHERE isHidden = 0 ORDER BY sortOrder ASC, title ASC")
    suspend fun getAllFeedsList(): List<FeedEntity>

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

    @Query("UPDATE feeds SET primaryCategoryId = :categoryId WHERE id = :feedId")
    suspend fun updatePrimaryCategory(feedId: Long, categoryId: Long?)

    @Query("UPDATE feeds SET secondaryCategoryId = :categoryId WHERE id = :feedId")
    suspend fun updateSecondaryCategory(feedId: Long, categoryId: Long?)

    // ── Category cross-ref operations ─────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFeedCategoryRef(ref: FeedCategoryCrossRef)

    @Query("DELETE FROM feed_category_cross_ref WHERE feedId = :feedId")
    suspend fun clearFeedCategories(feedId: Long)

    @Query("DELETE FROM feed_category_cross_ref WHERE feedId = :feedId AND isPrimary = :isPrimary")
    suspend fun deleteFeedCategoryRefByType(feedId: Long, isPrimary: Boolean)

    @Query("SELECT * FROM feed_category_cross_ref WHERE feedId = :feedId")
    suspend fun getCategoriesForFeed(feedId: Long): List<FeedCategoryCrossRef>

    // ── Category deletion helpers ─────────────────────────────────────────────
    // Used by CategoryRepository.deleteCategory() to implement the safe deletion
    // logic: never cascade to feeds; move them to Uncategorized instead.

    @Query("DELETE FROM feed_category_cross_ref WHERE categoryId = :categoryId")
    suspend fun deleteCrossRefsForCategory(categoryId: Long)

    @Query("UPDATE feeds SET primaryCategoryId = NULL WHERE primaryCategoryId = :categoryId")
    suspend fun nullifyPrimaryCategory(categoryId: Long)

    @Query("UPDATE feeds SET secondaryCategoryId = NULL WHERE secondaryCategoryId = :categoryId")
    suspend fun nullifySecondaryCategory(categoryId: Long)

    // ── SmartPlay evaluation helpers ──────────────────────────────────────────

    @Query("SELECT feedId FROM feed_category_cross_ref WHERE categoryId = :categoryId")
    suspend fun getCategoryFeedIds(categoryId: Long): List<Long>
}
