package mobi.beyondpod.revival.domain.usecase.episode

import mobi.beyondpod.revival.data.repository.EpisodeRepository
import javax.inject.Inject

/**
 * Build a frozen queue snapshot from an ordered list of episode IDs.
 *
 * This is the central queue-building operation (§0.1, §7.4 Queue Immutability):
 *   1. Evaluates the episode list
 *   2. Persists it as a QueueSnapshotEntity + QueueSnapshotItemEntity rows
 *   3. Sets the new snapshot as active; deactivates the previous one
 *
 * After this call, the active queue is the snapshot — NOT a live query.
 * Feed updates, new downloads, and rule changes do NOT affect the snapshot.
 * The user must explicitly trigger "Regenerate Queue" to rebuild.
 */
class BuildQueueSnapshotUseCase @Inject constructor(
    private val episodeRepository: EpisodeRepository
) {
    suspend operator fun invoke(
        sourcePlaylistId: Long?,
        episodeIds: List<Long>
    ): Result<Unit> = episodeRepository.buildQueueSnapshot(sourcePlaylistId, episodeIds)
}
