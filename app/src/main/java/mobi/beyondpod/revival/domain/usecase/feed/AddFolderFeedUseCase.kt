package mobi.beyondpod.revival.domain.usecase.feed

import mobi.beyondpod.revival.data.local.entity.FeedEntity
import mobi.beyondpod.revival.data.repository.FeedRepository
import javax.inject.Inject

/** Create a virtual folder feed from a SAF content:// tree URI and display name. */
class AddFolderFeedUseCase @Inject constructor(
    private val feedRepository: FeedRepository
) {
    suspend operator fun invoke(folderUri: String, displayName: String): Result<FeedEntity> =
        feedRepository.addFolderFeed(folderUri, displayName)
}
