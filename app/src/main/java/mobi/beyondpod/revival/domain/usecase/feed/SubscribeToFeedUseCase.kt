package mobi.beyondpod.revival.domain.usecase.feed

import mobi.beyondpod.revival.data.local.entity.FeedEntity
import mobi.beyondpod.revival.data.repository.FeedRepository
import javax.inject.Inject

class SubscribeToFeedUseCase @Inject constructor(
    private val feedRepository: FeedRepository
) {
    suspend operator fun invoke(url: String): Result<FeedEntity> =
        feedRepository.subscribeToFeed(url)
}
