package mobi.beyondpod.revival.data.repository

import kotlinx.coroutines.flow.Flow
import mobi.beyondpod.revival.data.local.entity.CategoryEntity

interface CategoryRepository {
    fun getAllCategories(): Flow<List<CategoryEntity>>
    suspend fun getCategoryById(id: Long): CategoryEntity?

    suspend fun createCategory(category: CategoryEntity): Long
    suspend fun updateCategory(category: CategoryEntity)

    /**
     * Safe category deletion — feeds are NEVER deleted or cascaded.
     * The deletion sequence (§7.3):
     *   1. DELETE feed_category_cross_ref WHERE categoryId = deletedCategoryId
     *   2. NULL primaryCategoryId on feeds where it matched
     *   3. NULL secondaryCategoryId on feeds where it matched
     *   4. DELETE categories WHERE id = deletedCategoryId
     * Feeds with no remaining category appear under "Uncategorized" in the navigator.
     */
    suspend fun deleteCategory(categoryId: Long)

    suspend fun reorderCategory(categoryId: Long, newSortOrder: Int)
}
