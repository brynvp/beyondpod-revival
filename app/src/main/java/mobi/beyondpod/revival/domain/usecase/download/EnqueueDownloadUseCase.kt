package mobi.beyondpod.revival.domain.usecase.download

import mobi.beyondpod.revival.data.repository.DownloadRepository
import javax.inject.Inject

class EnqueueDownloadUseCase @Inject constructor(
    private val downloadRepository: DownloadRepository
) {
    suspend operator fun invoke(episodeId: Long) = downloadRepository.enqueueDownload(episodeId)
}
