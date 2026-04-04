package mobi.beyondpod.revival.domain.usecase.episode

import kotlinx.coroutines.flow.Flow
import mobi.beyondpod.revival.data.local.entity.EpisodeEntity
import mobi.beyondpod.revival.data.repository.EpisodeRepository
import javax.inject.Inject

/** Full-text search across episode title and description. */
class SearchEpisodesUseCase @Inject constructor(
    private val episodeRepository: EpisodeRepository
) {
    operator fun invoke(query: String): Flow<List<EpisodeEntity>> =
        episodeRepository.searchEpisodes(query)
}
