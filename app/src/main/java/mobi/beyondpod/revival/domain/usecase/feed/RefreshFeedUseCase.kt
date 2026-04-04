package mobi.beyondpod.revival.domain.usecase.feed

import mobi.beyondpod.revival.data.repository.FeedRepository
import javax.inject.Inject

class RefreshFeedUseCase @Inject constructor(
    private val feedRepository: FeedRepository
) {
    suspend operator fun invoke(feedId: Long): Result<Unit> =
        feedRepository.refreshFeed(feedId)
}
