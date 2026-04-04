package mobi.beyondpod.revival.domain.usecase.episode

import mobi.beyondpod.revival.data.repository.EpisodeRepository
import javax.inject.Inject

/**
 * Persist the current playback position and update playedFraction.
 *
 * Must be called every 5 seconds during playback and on every pause event.
 * Also handles state transitions: NEW → IN_PROGRESS, and IN_PROGRESS → PLAYED at ≥90%.
 * (§0.4 — position fidelity: data loss of listening progress is a critical bug.)
 */
class SavePlayPositionUseCase @Inject constructor(
    private val episodeRepository: EpisodeRepository
) {
    suspend operator fun invoke(episodeId: Long, positionMs: Long) {
        episodeRepository.savePlayPosition(episodeId, positionMs)
    }
}
