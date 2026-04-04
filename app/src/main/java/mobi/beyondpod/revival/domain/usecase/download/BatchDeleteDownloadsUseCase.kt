package mobi.beyondpod.revival.domain.usecase.download

import mobi.beyondpod.revival.data.repository.DownloadRepository
import javax.inject.Inject

/**
 * Delete multiple downloaded files. Protected episodes are silently skipped.
 * Returns the count of episodes actually deleted.
 */
class BatchDeleteDownloadsUseCase @Inject constructor(
    private val downloadRepository: DownloadRepository
) {
    suspend operator fun invoke(episodeIds: List<Long>): Result<Int> =
        downloadRepository.batchDeleteDownloads(episodeIds)
}
