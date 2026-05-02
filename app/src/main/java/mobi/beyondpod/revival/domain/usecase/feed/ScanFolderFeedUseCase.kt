package mobi.beyondpod.revival.domain.usecase.feed

import mobi.beyondpod.revival.data.repository.FeedRepository
import javax.inject.Inject

/** Scan a virtual folder feed and upsert episodes for any new audio files found. */
class ScanFolderFeedUseCase @Inject constructor(
    private val feedRepository: FeedRepository
) {
    suspend operator fun invoke(feedId: Long): Result<Int> =
        feedRepository.scanFolderFeed(feedId)
}
