package mobi.beyondpod.revival.domain.usecase.episode

import mobi.beyondpod.revival.data.repository.EpisodeRepository
import javax.inject.Inject

class BatchMarkPlayedUseCase @Inject constructor(
    private val episodeRepository: EpisodeRepository
) {
    suspend operator fun invoke(episodeIds: List<Long>) =
        episodeRepository.batchMarkPlayed(episodeIds)
}
