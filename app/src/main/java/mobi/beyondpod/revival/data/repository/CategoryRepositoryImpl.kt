package mobi.beyondpod.revival.data.repository

import com.google.gson.Gson
import kotlinx.coroutines.flow.Flow
import mobi.beyondpod.revival.data.local.dao.CategoryDao
import mobi.beyondpod.revival.data.local.dao.FeedDao
import mobi.beyondpod.revival.data.local.dao.SmartPlaylistDao
import mobi.beyondpod.revival.data.local.entity.BlockSource
import mobi.beyondpod.revival.data.local.entity.CategoryEntity
import mobi.beyondpod.revival.data.local.entity.PlaylistRuleMode
import mobi.beyondpod.revival.data.local.entity.SmartPlaylistBlock
import javax.inject.Inject

class CategoryRepositoryImpl @Inject constructor(
    private val categoryDao: CategoryDao,
    private val feedDao: FeedDao,
    private val smartPlaylistDao: SmartPlaylistDao,
    private val gson: Gson
) : CategoryRepository {

    override fun getAllCategories(): Flow<List<CategoryEntity>> = categoryDao.getAllCategories()

    override suspend fun getCategoryById(id: Long): CategoryEntity? = categoryDao.getCategoryById(id)

    override suspend fun createCategory(category: CategoryEntity): Long =
        categoryDao.upsertCategory(category)

    override suspend fun updateCategory(category: CategoryEntity) {
        categoryDao.upsertCategory(category)
    }

    /**
     * Safe category deletion — feeds are NEVER deleted (§7.3, CLAUDE.md rule #7).
     *
     * Sequence:
     *   1. Delete all feed_category_cross_ref rows for this category
     *   2. Null out primaryCategoryId on any feed that referenced this category
     *   3. Null out secondaryCategoryId on any feed that referenced this category
     *   4. Delete the CategoryEntity row
     *
     * After step 4, feeds with no remaining category membership appear under
     * "Uncategorized" in the navigator. No feeds or episodes are deleted.
     */
    override suspend fun deleteCategory(categoryId: Long) {
        feedDao.deleteCrossRefsForCategory(categoryId)
        feedDao.nullifyPrimaryCategory(categoryId)
        feedDao.nullifySecondaryCategory(categoryId)
        val category = categoryDao.getCategoryById(categoryId) ?: return
        categoryDao.deleteCategory(category)
        prunePlaylistBlocksForCategory(categoryId)
    }

    /**
     * Remove any SmartPlaylistBlock entries whose sourceId matches [categoryId].
     * Called after deleting a category so SEQUENTIAL_BLOCKS playlists don't reference a ghost category.
     */
    private suspend fun prunePlaylistBlocksForCategory(categoryId: Long) {
        smartPlaylistDao.getAllPlaylistsList().forEach { playlist ->
            if (playlist.ruleMode != PlaylistRuleMode.SEQUENTIAL_BLOCKS) return@forEach
            val blocks = runCatching {
                gson.fromJson(playlist.rulesJson, Array<SmartPlaylistBlock>::class.java).toList()
            }.getOrElse { return@forEach }
            val pruned = blocks.filter { it.source != BlockSource.CATEGORY || it.sourceId != categoryId }
            if (pruned.size != blocks.size) {
                smartPlaylistDao.upsertPlaylist(playlist.copy(rulesJson = gson.toJson(pruned)))
            }
        }
    }

    override suspend fun reorderCategory(categoryId: Long, newSortOrder: Int) {
        val category = categoryDao.getCategoryById(categoryId) ?: return
        categoryDao.upsertCategory(category.copy(sortOrder = newSortOrder))
    }
}
