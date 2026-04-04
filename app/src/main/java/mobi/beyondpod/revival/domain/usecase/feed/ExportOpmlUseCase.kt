package mobi.beyondpod.revival.domain.usecase.feed

import mobi.beyondpod.revival.data.repository.FeedRepository
import javax.inject.Inject

/** Serialize all subscriptions to an OPML 2.0 XML string. */
class ExportOpmlUseCase @Inject constructor(
    private val feedRepository: FeedRepository
) {
    suspend operator fun invoke(): Result<String> = feedRepository.exportToOpml()
}
