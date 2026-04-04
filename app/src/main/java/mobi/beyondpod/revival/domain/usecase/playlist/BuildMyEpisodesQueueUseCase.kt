package mobi.beyondpod.revival.domain.usecase.playlist

import kotlinx.coroutines.flow.first
import mobi.beyondpod.revival.data.local.entity.SmartPlaylistEntity
import mobi.beyondpod.revival.data.repository.EpisodeRepository
import mobi.beyondpod.revival.data.repository.PlaylistRepository
import javax.inject.Inject

/**
 * Build a frozen queue snapshot from the current contents of My Episodes.
 *
 * My Episodes IS the playback queue (§7.5 rule #2). This use case:
 *   1. Evaluates the current My Episodes ordered episode list
 *   2. Calls EpisodeRepository.buildQueueSnapshot() to persist it as a frozen snapshot
 *   3. Replaces the existing active snapshot atomically
 *
 * After this call, the queue is stable — it does not change until the user
 * explicitly regenerates it.
 */
class BuildMyEpisodesQueueUseCase @Inject constructor(
    private val playlistRepository: PlaylistRepository,
    private val episodeRepository: EpisodeRepository
) {
    suspend operator fun invoke(): Result<Unit> = runCatching {
        val myEpisodes: SmartPlaylistEntity = playlistRepository.getMyEpisodesPlaylist()
            ?: error("My Episodes playlist not found — run seedDefaultPlaylistsIfNeeded() first")

        // Evaluate the My Episodes rules to get the ordered episode list.
        // For the queue build we collect just once (snapshot is intentionally frozen).
        val episodeIds = playlistRepository.evaluateSmartPlaylist(myEpisodes)
            .first()
            .map { it.id }

        episodeRepository.buildQueueSnapshot(myEpisodes.id, episodeIds).getOrThrow()
    }
}
