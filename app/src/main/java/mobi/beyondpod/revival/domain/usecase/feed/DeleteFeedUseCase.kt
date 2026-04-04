package mobi.beyondpod.revival.domain.usecase.feed

import mobi.beyondpod.revival.data.repository.FeedRepository
import javax.inject.Inject

class DeleteFeedUseCase @Inject constructor(
    private val feedRepository: FeedRepository
) {
    /** Delete a feed and optionally its downloaded files. */
    suspend operator fun invoke(feedId: Long, deleteDownloads: Boolean = true) {
        feedRepository.deleteFeed(feedId, deleteDownloads)
    }
}
