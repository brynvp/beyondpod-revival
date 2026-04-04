package mobi.beyondpod.revival.domain.usecase.feed

import mobi.beyondpod.revival.data.repository.FeedRepository
import javax.inject.Inject

class MoveFeedToCategoryUseCase @Inject constructor(
    private val feedRepository: FeedRepository
) {
    suspend operator fun invoke(feedId: Long, categoryId: Long?, isPrimary: Boolean = true) {
        feedRepository.moveFeedToCategory(feedId, categoryId, isPrimary)
    }
}
