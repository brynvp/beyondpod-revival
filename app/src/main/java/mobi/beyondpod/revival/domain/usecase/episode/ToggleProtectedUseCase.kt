package mobi.beyondpod.revival.domain.usecase.episode

import mobi.beyondpod.revival.data.repository.EpisodeRepository
import javax.inject.Inject

/**
 * Toggle the isProtected flag on an episode.
 *
 * When isProtected = true, the episode is NEVER auto-deleted under any circumstances —
 * this is an absolute veto enforced at cleanup, download deletion, and manual delete prompts.
 * (CLAUDE.md Non-Negotiable Architecture Rule #4)
 */
class ToggleProtectedUseCase @Inject constructor(
    private val episodeRepository: EpisodeRepository
) {
    suspend operator fun invoke(episodeId: Long) = episodeRepository.toggleProtected(episodeId)
}
