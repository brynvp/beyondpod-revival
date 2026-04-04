package mobi.beyondpod.revival.domain.usecase.episode

import mobi.beyondpod.revival.data.repository.EpisodeRepository
import javax.inject.Inject

/**
 * Remove all episodes from My Episodes and deactivate the active queue.
 * Does NOT delete episode records or downloaded files.
 */
class ClearMyEpisodesUseCase @Inject constructor(
    private val episodeRepository: EpisodeRepository
) {
    suspend operator fun invoke() = episodeRepository.clearMyEpisodes()
}
