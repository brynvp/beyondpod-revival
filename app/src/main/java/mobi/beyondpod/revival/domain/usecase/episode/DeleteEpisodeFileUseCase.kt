package mobi.beyondpod.revival.domain.usecase.episode

import mobi.beyondpod.revival.data.repository.EpisodeRepository
import javax.inject.Inject

/**
 * Delete the local downloaded file for an episode, keeping the episode record.
 * Returns failure if the episode is protected (isProtected absolute veto).
 */
class DeleteEpisodeFileUseCase @Inject constructor(
    private val episodeRepository: EpisodeRepository
) {
    suspend operator fun invoke(episodeId: Long): Result<Unit> =
        episodeRepository.deleteEpisodeFile(episodeId)
}
