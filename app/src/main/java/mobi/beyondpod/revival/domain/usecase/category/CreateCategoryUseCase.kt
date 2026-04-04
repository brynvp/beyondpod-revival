package mobi.beyondpod.revival.domain.usecase.category

import mobi.beyondpod.revival.data.local.entity.CategoryEntity
import mobi.beyondpod.revival.data.repository.CategoryRepository
import javax.inject.Inject

class CreateCategoryUseCase @Inject constructor(
    private val categoryRepository: CategoryRepository
) {
    suspend operator fun invoke(category: CategoryEntity): Long =
        categoryRepository.createCategory(category)
}
