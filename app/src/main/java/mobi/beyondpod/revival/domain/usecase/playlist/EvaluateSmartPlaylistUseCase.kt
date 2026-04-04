package mobi.beyondpod.revival.domain.usecase.playlist

import kotlinx.coroutines.flow.Flow
import mobi.beyondpod.revival.data.local.entity.EpisodeEntity
import mobi.beyondpod.revival.data.local.entity.SmartPlaylistEntity
import mobi.beyondpod.revival.data.repository.PlaylistRepository
import javax.inject.Inject

/**
 * Evaluate a SmartPlaylist and return a live Flow of matching episodes.
 *
 * Supports both SEQUENTIAL_BLOCKS ("Standard") and FILTER_RULES ("Advanced") modes.
 * The Flow re-emits automatically when the underlying episode data changes.
 */
class EvaluateSmartPlaylistUseCase @Inject constructor(
    private val playlistRepository: PlaylistRepository
) {
    operator fun invoke(playlist: SmartPlaylistEntity): Flow<List<EpisodeEntity>> =
        playlistRepository.evaluateSmartPlaylist(playlist)
}
