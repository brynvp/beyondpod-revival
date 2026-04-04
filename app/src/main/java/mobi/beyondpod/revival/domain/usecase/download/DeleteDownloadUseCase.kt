package mobi.beyondpod.revival.domain.usecase.download

import mobi.beyondpod.revival.data.repository.DownloadRepository
import javax.inject.Inject

/**
 * Delete a downloaded episode file.
 * Returns failure if the episode isProtected — absolute veto, no exceptions.
 */
class DeleteDownloadUseCase @Inject constructor(
    private val downloadRepository: DownloadRepository
) {
    suspend operator fun invoke(episodeId: Long): Result<Unit> =
        downloadRepository.deleteDownload(episodeId)
}
