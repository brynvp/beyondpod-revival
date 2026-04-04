package mobi.beyondpod.revival.domain.usecase.episode

import mobi.beyondpod.revival.data.repository.EpisodeRepository
import javax.inject.Inject

class RemoveFromMyEpisodesUseCase @Inject constructor(
    private val episodeRepository: EpisodeRepository
) {
    suspend operator fun invoke(episodeId: Long) = episodeRepository.removeFromMyEpisodes(episodeId)
}
