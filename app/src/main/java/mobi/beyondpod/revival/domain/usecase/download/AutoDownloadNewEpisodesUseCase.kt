package mobi.beyondpod.revival.domain.usecase.download

import mobi.beyondpod.revival.data.repository.DownloadRepository
import javax.inject.Inject

/**
 * Apply the auto-download strategy for a feed after a feed refresh.
 *
 * Cleanup ALWAYS runs before new downloads — this is a mandatory ordering rule (§7.7, §9).
 * Protected episodes are never deleted during cleanup.
 */
class AutoDownloadNewEpisodesUseCase @Inject constructor(
    private val downloadRepository: DownloadRepository
) {
    suspend operator fun invoke(feedId: Long) =
        downloadRepository.autoDownloadNewEpisodes(feedId)
}
