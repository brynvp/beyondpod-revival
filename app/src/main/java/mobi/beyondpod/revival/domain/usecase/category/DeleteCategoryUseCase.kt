package mobi.beyondpod.revival.domain.usecase.category

import mobi.beyondpod.revival.data.repository.CategoryRepository
import javax.inject.Inject

/**
 * Safely delete a category without cascading to its feeds.
 *
 * Deletion sequence (§7.3, CLAUDE.md rule #7):
 *   1. Delete feed_category_cross_ref rows for this category
 *   2. Null out primaryCategoryId on affected feeds
 *   3. Null out secondaryCategoryId on affected feeds
 *   4. Delete the CategoryEntity
 *
 * Feeds are moved to "Uncategorized" — they are NEVER deleted.
 */
class DeleteCategoryUseCase @Inject constructor(
    private val categoryRepository: CategoryRepository
) {
    suspend operator fun invoke(categoryId: Long) =
        categoryRepository.deleteCategory(categoryId)
}
