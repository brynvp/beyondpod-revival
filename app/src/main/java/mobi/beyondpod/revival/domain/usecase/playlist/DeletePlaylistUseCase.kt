package mobi.beyondpod.revival.domain.usecase.playlist

import mobi.beyondpod.revival.data.local.entity.SmartPlaylistEntity
import mobi.beyondpod.revival.data.repository.PlaylistRepository
import javax.inject.Inject

/**
 * Delete a SmartPlaylist. Throws [IllegalStateException] if the playlist is My Episodes
 * (isDefault = true) — it is indestructible (§7.5 rule #3).
 */
class DeletePlaylistUseCase @Inject constructor(
    private val playlistRepository: PlaylistRepository
) {
    suspend operator fun invoke(playlist: SmartPlaylistEntity) =
        playlistRepository.deletePlaylist(playlist)
}
