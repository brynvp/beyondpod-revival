package mobi.beyondpod.revival.domain.usecase.feed

import mobi.beyondpod.revival.data.repository.FeedRepository
import javax.inject.Inject

/** Parse an OPML document and subscribe to all feeds it contains. */
class ImportOpmlUseCase @Inject constructor(
    private val feedRepository: FeedRepository
) {
    suspend operator fun invoke(opmlContent: String): Result<Int> =
        feedRepository.importFromOpml(opmlContent)
}
