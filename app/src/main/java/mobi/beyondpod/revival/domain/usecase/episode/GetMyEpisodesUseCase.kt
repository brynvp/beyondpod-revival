package mobi.beyondpod.revival.domain.usecase.episode

import kotlinx.coroutines.flow.Flow
import mobi.beyondpod.revival.data.local.entity.EpisodeEntity
import mobi.beyondpod.revival.data.repository.EpisodeRepository
import javax.inject.Inject

/**
 * Returns a live Flow of My Episodes in their user-defined order.
 *
 * Source of truth: [EpisodeEntity.isInMyEpisodes] == true, ordered by
 * [ManualPlaylistEpisodeCrossRef.position]. This is NOT rule-driven — it reflects
 * the user's manually curated queue (§7.5 rule #1).
 *
 * Do NOT call [PlaylistRepository.evaluateSmartPlaylist] for My Episodes.
 */
class GetMyEpisodesUseCase @Inject constructor(
    private val episodeRepository: EpisodeRepository
) {
    operator fun invoke(): Flow<List<EpisodeEntity>> = episodeRepository.getMyEpisodes()
}
